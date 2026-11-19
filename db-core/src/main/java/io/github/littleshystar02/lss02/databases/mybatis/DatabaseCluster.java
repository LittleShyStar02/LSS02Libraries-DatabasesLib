// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.mybatis;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import io.github.littleshystar02.lss02.databases.bootstrap.SchemaBootstrapper;
import io.github.littleshystar02.lss02.databases.cluster.ClusterConfigurationException;
import io.github.littleshystar02.lss02.databases.cluster.ClusterLoader;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseClusterConfiguration;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import io.github.littleshystar02.lss02.databases.cluster.PoolSettings;
import io.github.littleshystar02.lss02.databases.connection.DataSourceFactory;
import io.github.littleshystar02.lss02.databases.connection.DatabaseHealth;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Entry point of the library: a set of named databases, each with its own connection pool and MyBatis
 * session factory.
 *
 * <p>Construction is eager. The pools of all databases are opened in parallel, so several unreachable
 * servers delay the start by one connection timeout instead of one per server, and the whole pool phase
 * is bounded by the largest configured connection timeout plus a small margin. Then, in configuration
 * order, the schema scripts are applied when the definition configures any (see
 * {@link io.github.littleshystar02.lss02.databases.bootstrap.SchemaBootstrapper}) and the session
 * factory is built. If one database fails, every pool already opened is closed and the exception is
 * rethrown. Call {@link #close()} when the application stops.
 *
 * <p>Lookups are by database name and fail with {@link ClusterConfigurationException} for an unknown
 * name. After {@link #close()} every access to a database (data source, sessions, mappers, health)
 * fails with an {@link IllegalStateException}. The lookup methods only read data that is fixed after
 * construction, so a cluster can be shared between threads; each {@link SqlSession} must stay on one thread.
 *
 * <p>The blocking {@code withMapper}/{@code useMapper} methods run on the calling thread, which is a
 * problem on a game server main thread: the {@code ...Async} variants run the same work on a dedicated
 * executor owned by the cluster.
 */
public class DatabaseCluster implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseCluster.class);

    /** Extra time on top of the largest connection timeout that the parallel pool start may take. */
    private static final long STARTUP_MARGIN_MS = 5000;
    /** Upper bound of the threads used to open pools in parallel. */
    private static final int MAX_STARTUP_THREADS = 16;
    /** Upper bound of the threads of the async executor. */
    private static final int MAX_ASYNC_THREADS = 32;
    /** How long {@link #close()} waits for running asynchronous work before interrupting it. */
    private static final long CLOSE_WAIT_SECONDS = 5;

    /** Definitions keyed by database name, in configuration order; unmodifiable. */
    private final Map<String, DatabaseDefinition> definitions;
    /** Open connection pools keyed by database name; unmodifiable. */
    private final Map<String, HikariDataSource> dataSources;
    /** MyBatis session factories keyed by database name; unmodifiable. */
    private final Map<String, SqlSessionFactory> sessionFactories;
    /** Set once by {@link #close()}. */
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Dedicated executor of the {@code ...Async} methods; its threads are created on demand and expire when idle. */
    private final ThreadPoolExecutor asyncExecutor;
    /** Queued async tasks with their public futures, so a forced close can fail the discarded ones. */
    private final Map<Runnable, CompletableFuture<?>> queuedTasks = new ConcurrentHashMap<>();

    /**
     * Creates a cluster from a loaded configuration file, using the thread context class loader.
     *
     * @param configuration the loaded cluster configuration
     * @throws ClusterConfigurationException if the configuration is invalid
     */
    public DatabaseCluster(DatabaseClusterConfiguration configuration) {
        this(configuration, null);
    }

    /**
     * Creates a cluster from a loaded configuration file.
     *
     * @param configuration the loaded cluster configuration
     * @param classLoader the class loader used to find JDBC drivers, mapper packages and
     *                    {@code classpath:} scripts, for example the loader of a plugin; {@code null}
     *                    keeps the current thread context class loader
     * @throws ClusterConfigurationException if the configuration is invalid
     */
    public DatabaseCluster(DatabaseClusterConfiguration configuration, ClassLoader classLoader) {
        this(ClusterLoader.load(configuration), classLoader, null);
    }

    /**
     * Creates a cluster from a loaded configuration file and plugs a metrics tracker into every pool.
     *
     * @param configuration the loaded cluster configuration
     * @param classLoader the class loader for drivers, mapper packages and {@code classpath:} scripts;
     *                    {@code null} keeps the thread context class loader
     * @param metricsTrackerFactory the HikariCP metrics hook, or {@code null} for none
     * @throws ClusterConfigurationException if the configuration is invalid
     */
    public DatabaseCluster(DatabaseClusterConfiguration configuration, ClassLoader classLoader,
                           MetricsTrackerFactory metricsTrackerFactory) {
        this(ClusterLoader.load(configuration), classLoader, metricsTrackerFactory);
    }

    /**
     * Creates a cluster from definitions built in code, using the thread context class loader.
     *
     * @param definitions the databases of the cluster, each with a unique non-null name
     * @throws ClusterConfigurationException if a name is missing or duplicated
     */
    public DatabaseCluster(Collection<DatabaseDefinition> definitions) {
        this(definitions, null);
    }

    /**
     * Creates a cluster from definitions built in code.
     *
     * @param definitions the databases of the cluster, each with a unique non-null name
     * @param classLoader the class loader used to find JDBC drivers, mapper packages and
     *                    {@code classpath:} scripts; {@code null} keeps the thread context class loader
     * @throws ClusterConfigurationException if a name is missing or duplicated
     */
    public DatabaseCluster(Collection<DatabaseDefinition> definitions, ClassLoader classLoader) {
        this(definitions, classLoader, null);
    }

    /**
     * Creates a cluster from definitions built in code and plugs a metrics tracker into every pool.
     *
     * @param definitions the databases of the cluster, each with a unique non-null name
     * @param classLoader the class loader for drivers, mapper packages and {@code classpath:} scripts;
     *                    {@code null} keeps the thread context class loader
     * @param metricsTrackerFactory the HikariCP metrics hook, or {@code null} for none
     * @throws ClusterConfigurationException if a name is missing or duplicated
     */
    public DatabaseCluster(Collection<DatabaseDefinition> definitions, ClassLoader classLoader,
                           MetricsTrackerFactory metricsTrackerFactory) {
        this(indexByName(definitions), classLoader, metricsTrackerFactory);
    }

    /**
     * Does the actual initialization of all databases (see the class description).
     *
     * @param definitions the definitions keyed by name
     * @param classLoader the class loader to use during initialization, may be {@code null}
     * @param metricsTrackerFactory the metrics hook, may be {@code null}
     */
    private DatabaseCluster(Map<String, DatabaseDefinition> definitions, ClassLoader classLoader,
                            MetricsTrackerFactory metricsTrackerFactory) {
        long started = System.nanoTime();
        Map<String, HikariDataSource> pools = openPools(definitions, classLoader, metricsTrackerFactory);
        Map<String, SqlSessionFactory> factories = new LinkedHashMap<>();

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        if (classLoader != null) {
            thread.setContextClassLoader(classLoader);
        }
        try {
            for (DatabaseDefinition definition : definitions.values()) {
                HikariDataSource dataSource = pools.get(definition.getName());
                if (definition.getSetupScript() != null || definition.getVersionedScriptsPath() != null) {
                    SchemaBootstrapper.bootstrap(
                            dataSource, definition.getSetupScript(), definition.getVersionedScriptsPath(), classLoader);
                }
                factories.put(definition.getName(), ClusterSqlSessionFactoryProvider.create(definition, dataSource));
            }
        } catch (RuntimeException | Error e) {
            LOG.error("Database cluster start failed, closing the opened pools", e);
            closePools(pools.values());
            throw e;
        } finally {
            if (classLoader != null) {
                thread.setContextClassLoader(previous);
            }
        }

        this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        this.dataSources = Collections.unmodifiableMap(pools);
        this.sessionFactories = Collections.unmodifiableMap(factories);
        this.asyncExecutor = createAsyncExecutor(definitions);
        LOG.info("Database cluster started with {} database(s) {} in {} ms", pools.size(), pools.keySet(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    /**
     * Opens the pool of every definition in parallel and waits for all of them, within the largest
     * connection timeout plus a margin. If one pool fails or the time runs out, the pools opened so far
     * are closed, pools still being opened close themselves when they finish, and the failure is rethrown.
     *
     * @param definitions the databases to open
     * @param classLoader class loader for the driver lookup, may be {@code null}
     * @param metrics metrics hook, may be {@code null}
     * @return the open pools keyed by name, in definition order
     */
    private static Map<String, HikariDataSource> openPools(Map<String, DatabaseDefinition> definitions,
                                                           ClassLoader classLoader, MetricsTrackerFactory metrics) {
        Startup startup = new Startup();
        if (definitions.size() <= 1) {
            try {
                for (DatabaseDefinition definition : definitions.values()) {
                    openPool(definition, classLoader, metrics, startup);
                }
            } catch (RuntimeException | Error e) {
                startup.abort();
                throw e;
            }
            return startup.finish(definitions.keySet());
        }

        long budgetMs = STARTUP_MARGIN_MS;
        for (DatabaseDefinition definition : definitions.values()) {
            budgetMs = Math.max(budgetMs, connectionTimeoutOf(definition) + STARTUP_MARGIN_MS);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);

        int threads = Math.min(definitions.size(), MAX_STARTUP_THREADS);
        ThreadPoolExecutor starter = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), daemonFactory("lss02-db-start"));
        try {
            ExecutorCompletionService<Void> completion = new ExecutorCompletionService<>(starter);
            for (DatabaseDefinition definition : definitions.values()) {
                completion.submit(() -> {
                    openPool(definition, classLoader, metrics, startup);
                    return null;
                });
            }
            for (int i = 0; i < definitions.size(); i++) {
                Future<Void> done = completion.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (done == null) {
                    List<String> missing = startup.missing(definitions.keySet());
                    startup.abort();
                    throw new IllegalStateException("Timed out after " + budgetMs
                            + " ms while opening the connection pools of " + missing);
                }
                done.get();
            }
            return startup.finish(definitions.keySet());
        } catch (ExecutionException e) {
            startup.abort();
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Could not open a connection pool", cause);
        } catch (InterruptedException e) {
            startup.abort();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while opening the connection pools", e);
        } catch (RuntimeException | Error e) {
            startup.abort();
            throw e;
        } finally {
            starter.shutdown();
        }
    }

    /**
     * Opens one pool with the class loader set as thread context class loader and hands it to the startup tracker.
     *
     * @param definition the database
     * @param classLoader the class loader to use, may be {@code null}
     * @param metrics the metrics hook, may be {@code null}
     * @param startup the tracker that owns the pool from now on
     */
    private static void openPool(DatabaseDefinition definition, ClassLoader classLoader,
                                 MetricsTrackerFactory metrics, Startup startup) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        if (classLoader != null) {
            thread.setContextClassLoader(classLoader);
        }
        try {
            HikariDataSource dataSource = DataSourceFactory.create(definition, metrics);
            LOG.info("Opened pool '{}'", definition.getName());
            startup.offer(definition.getName(), dataSource);
        } catch (RuntimeException | Error e) {
            LOG.error("Could not open pool '{}': {}", definition.getName(), e.toString());
            throw e;
        } finally {
            if (classLoader != null) {
                thread.setContextClassLoader(previous);
            }
        }
    }

    /**
     * Gives the pool settings a definition will use.
     *
     * @param definition the database
     * @return its own settings, or the defaults of its engine
     */
    private static PoolSettings poolOf(DatabaseDefinition definition) {
        return definition.getPool() != null ? definition.getPool() : PoolSettings.defaultsFor(definition.getType());
    }

    /**
     * Gives the connection timeout a definition will use.
     *
     * @param definition the database
     * @return the timeout in milliseconds
     */
    private static long connectionTimeoutOf(DatabaseDefinition definition) {
        return poolOf(definition).getConnectionTimeoutMs();
    }

    /** Collects the pools opened by the parallel start, so that none is leaked when the start is aborted. */
    private static final class Startup {
        private final Object lock = new Object();
        private final Map<String, HikariDataSource> opened = new LinkedHashMap<>();
        private boolean aborted;

        /**
         * Takes ownership of an opened pool, or closes it right away when the start was already aborted.
         *
         * @param name the database name
         * @param dataSource the open pool
         */
        void offer(String name, HikariDataSource dataSource) {
            synchronized (lock) {
                if (aborted) {
                    dataSource.close();
                } else {
                    opened.put(name, dataSource);
                }
            }
        }

        /**
         * Lists the databases whose pool is not open yet.
         *
         * @param names all database names
         * @return the names without a pool
         */
        List<String> missing(Collection<String> names) {
            synchronized (lock) {
                List<String> result = new ArrayList<>();
                for (String name : names) {
                    if (!opened.containsKey(name)) {
                        result.add(name);
                    }
                }
                return result;
            }
        }

        /** Closes every pool collected so far and makes later arrivals close themselves. */
        void abort() {
            synchronized (lock) {
                aborted = true;
                closePools(opened.values());
                opened.clear();
            }
        }

        /**
         * Returns the pools in the given order.
         *
         * @param order the database names in definition order
         * @return a new ordered map
         */
        Map<String, HikariDataSource> finish(Collection<String> order) {
            synchronized (lock) {
                Map<String, HikariDataSource> ordered = new LinkedHashMap<>();
                for (String name : order) {
                    ordered.put(name, opened.get(name));
                }
                return ordered;
            }
        }
    }

    /**
     * Creates the executor of the asynchronous methods, sized on the pools: as many threads as
     * connections, at most {@value #MAX_ASYNC_THREADS}, so a thread rarely waits for a connection.
     *
     * @param definitions the definitions, to sum the pool sizes
     * @return the executor
     */
    private static ThreadPoolExecutor createAsyncExecutor(Map<String, DatabaseDefinition> definitions) {
        int connections = 0;
        for (DatabaseDefinition definition : definitions.values()) {
            connections += poolOf(definition).getMaximumPoolSize();
        }
        int threads = Math.max(1, Math.min(MAX_ASYNC_THREADS, connections));
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), daemonFactory("lss02-db-async"));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * Creates a private factory of numbered daemon threads that report uncaught errors to the logger.
     *
     * @param prefix the thread name prefix
     * @return the factory
     */
    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) -> LOG.error("Uncaught exception in thread {}", t.getName(), e));
            return thread;
        };
    }

    /**
     * Returns the definition a database was created from. Still available after {@link #close()}.
     *
     * @param name the database name
     * @return the definition
     * @throws ClusterConfigurationException if the name is unknown
     */
    public DatabaseDefinition getDefinition(String name) {
        return requireEntry(definitions, name);
    }

    /**
     * Returns the connection pool of a database, for plain JDBC access. The pool is owned by the cluster
     * and must not be closed by the caller.
     *
     * @param name the database name
     * @return the pooled data source
     * @throws ClusterConfigurationException if the name is unknown
     * @throws IllegalStateException if the cluster is closed
     */
    public DataSource getDataSource(String name) {
        return lookup(dataSources, name);
    }

    /**
     * Opens a MyBatis session with manual commit. This is the low-level, manual entry point: the caller
     * must commit if needed and <b>always close the session</b> (use try-with-resources), otherwise the
     * connection never returns to the pool. Prefer {@link #withMapper} or {@link #useMapper}, which
     * manage the session themselves.
     *
     * @param name the database name
     * @return a new session
     * @throws ClusterConfigurationException if the name is unknown
     * @throws IllegalStateException if the cluster is closed
     */
    public SqlSession openSession(String name) {
        return lookup(sessionFactories, name).openSession();
    }

    /**
     * Opens a MyBatis session with the chosen commit mode. As for {@link #openSession(String)}, the
     * caller must close the session.
     *
     * @param name the database name
     * @param autoCommit {@code true} to commit after every statement
     * @return a new session
     * @throws ClusterConfigurationException if the name is unknown
     * @throws IllegalStateException if the cluster is closed
     */
    public SqlSession openSession(String name, boolean autoCommit) {
        return lookup(sessionFactories, name).openSession(autoCommit);
    }

    /**
     * Runs an action with a mapper in a short session and returns its result. The session is committed
     * after the action returns and always closed. The call blocks the calling thread; see
     * {@link #withMapperAsync} for a non-blocking variant.
     *
     * @param <T> the mapper type
     * @param <R> the result type
     * @param name the database name
     * @param mapperClass the mapper interface
     * @param action the code that uses the mapper
     * @return whatever the action returns
     * @throws ClusterConfigurationException if the name is unknown
     * @throws IllegalStateException if the cluster is closed
     */
    public <T, R> R withMapper(String name, Class<T> mapperClass, Function<T, R> action) {
        try (SqlSession session = openSession(name)) {
            R result = action.apply(session.getMapper(mapperClass));
            session.commit();
            return result;
        }
    }

    /**
     * Runs an action with a mapper in a short session, for operations without a result. The session is
     * committed after the action returns and always closed. The call blocks the calling thread; see
     * {@link #useMapperAsync} for a non-blocking variant.
     *
     * @param <T> the mapper type
     * @param name the database name
     * @param mapperClass the mapper interface
     * @param action the code that uses the mapper
     * @throws ClusterConfigurationException if the name is unknown
     * @throws IllegalStateException if the cluster is closed
     */
    public <T> void useMapper(String name, Class<T> mapperClass, Consumer<T> action) {
        try (SqlSession session = openSession(name)) {
            action.accept(session.getMapper(mapperClass));
            session.commit();
        }
    }

    /**
     * Like {@link #withMapper}, but runs on the cluster's dedicated executor and returns immediately, so
     * it is safe to call from a thread that must not block (for example a server main thread). The
     * action must not touch thread-confined state of the caller.
     *
     * <p>The name is checked right away; a failure of the action completes the future exceptionally.
     * Work still queued when {@link #close()} runs fails with an {@link IllegalStateException}.
     *
     * @param <T> the mapper type
     * @param <R> the result type
     * @param name the database name
     * @param mapperClass the mapper interface
     * @param action the code that uses the mapper
     * @return a future of whatever the action returns
     * @throws ClusterConfigurationException if the name is unknown
     * @throws IllegalStateException if the cluster is closed
     */
    public <T, R> CompletableFuture<R> withMapperAsync(String name, Class<T> mapperClass, Function<T, R> action) {
        lookup(sessionFactories, name);
        try {
            return submitAsync(() -> withMapper(name, mapperClass, action));
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(new IllegalStateException("The database cluster is closed", e));
        }
    }

    /**
     * Runs a supplier on the async executor and returns the public future. The queued task is kept
     * together with its future, so a forced close can complete exceptionally the work it discards.
     *
     * @param <R> the result type
     * @param supplier the work
     * @return the future of the result
     * @throws RejectedExecutionException if the executor no longer accepts work
     */
    private <R> CompletableFuture<R> submitAsync(Supplier<R> supplier) {
        CompletableFuture<R> future = new CompletableFuture<>();
        Runnable[] holder = new Runnable[1];
        Runnable task = () -> {
            queuedTasks.remove(holder[0]);
            if (future.isDone()) {
                return;
            }
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
        holder[0] = task;
        queuedTasks.put(task, future);
        try {
            asyncExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            queuedTasks.remove(task);
            throw e;
        }
        return future;
    }

    /**
     * Completes exceptionally the futures of tasks that a forced shutdown removed from the queue.
     *
     * @param discarded the tasks returned by {@code shutdownNow()}
     */
    private void failDiscarded(List<Runnable> discarded) {
        for (Runnable task : discarded) {
            CompletableFuture<?> future = queuedTasks.remove(task);
            if (future != null) {
                future.completeExceptionally(new IllegalStateException("The database cluster was closed before the work started"));
            }
        }
    }

    /**
     * Like {@link #useMapper}, but runs on the cluster's dedicated executor and returns immediately.
     * See {@link #withMapperAsync} for the contract.
     *
     * @param <T> the mapper type
     * @param name the database name
     * @param mapperClass the mapper interface
     * @param action the code that uses the mapper
     * @return a future that completes when the action has run and the session is committed
     * @throws ClusterConfigurationException if the name is unknown
     * @throws IllegalStateException if the cluster is closed
     */
    public <T> CompletableFuture<Void> useMapperAsync(String name, Class<T> mapperClass, Consumer<T> action) {
        lookup(sessionFactories, name);
        try {
            return submitAsync(() -> {
                useMapper(name, mapperClass, action);
                return null;
            });
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(new IllegalStateException("The database cluster is closed", e));
        }
    }

    /**
     * Checks that a database is reachable: borrows a connection and validates it.
     *
     * @param name the database name
     * @return {@code true} if a valid connection was obtained, {@code false} otherwise
     * @throws ClusterConfigurationException if the name is unknown
     * @throws IllegalStateException if the cluster is closed
     */
    public boolean ping(String name) {
        return check(name, lookup(dataSources, name)).up();
    }

    /**
     * Checks every database and reports the pool statistics next to the result, for health endpoints and
     * diagnostics. Databases are checked one after the other; each check is bounded by the pool's
     * connection timeout.
     *
     * @return the health of each database keyed by name, in configuration order
     * @throws IllegalStateException if the cluster is closed
     */
    public Map<String, DatabaseHealth> health() {
        ensureOpen();
        Map<String, DatabaseHealth> result = new LinkedHashMap<>();
        for (Map.Entry<String, HikariDataSource> entry : dataSources.entrySet()) {
            result.put(entry.getKey(), check(entry.getKey(), entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Validates a connection of one pool and reads its statistics.
     *
     * @param name the database name
     * @param dataSource the pool
     * @return the health snapshot
     */
    private static DatabaseHealth check(String name, HikariDataSource dataSource) {
        long begin = System.nanoTime();
        boolean up = false;
        String error = null;
        try (Connection connection = dataSource.getConnection()) {
            up = connection.isValid(Math.max(1, (int) (dataSource.getValidationTimeout() / 1000)));
            if (!up) {
                error = "The connection did not pass validation";
            }
        } catch (SQLException | RuntimeException e) {
            error = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        return new DatabaseHealth(name, up, latency,
                pool != null ? pool.getActiveConnections() : -1,
                pool != null ? pool.getIdleConnections() : -1,
                pool != null ? pool.getTotalConnections() : -1,
                pool != null ? pool.getThreadsAwaitingConnection() : -1,
                error);
    }

    /**
     * Closes the cluster: stops the async executor (waiting briefly for running work), then closes every
     * connection pool. Calling it again has no effect. If a pool fails to close, the others are still
     * closed and the first failure is rethrown with the rest attached as suppressed exceptions. After
     * this call every access to a database fails with an {@link IllegalStateException}.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                failDiscarded(asyncExecutor.shutdownNow());
            }
        } catch (InterruptedException e) {
            failDiscarded(asyncExecutor.shutdownNow());
            Thread.currentThread().interrupt();
        }
        RuntimeException failure = closePools(dataSources.values());
        LOG.info("Database cluster closed");
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Closes every pool, continuing after a failure.
     *
     * @param pools the pools to close
     * @return the first failure with the later ones suppressed, or {@code null} if all closed cleanly
     */
    private static RuntimeException closePools(Collection<HikariDataSource> pools) {
        RuntimeException failure = null;
        for (HikariDataSource dataSource : pools) {
            if (dataSource == null) {
                continue;
            }
            try {
                dataSource.close();
            } catch (RuntimeException e) {
                LOG.warn("Could not close pool '{}': {}", dataSource.getPoolName(), e.toString());
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        return failure;
    }

    /**
     * Fails with a clear message once the cluster is closed.
     *
     * @throws IllegalStateException if {@link #close()} was called
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("The database cluster is closed");
        }
    }

    /**
     * Looks up a per-database value of an open cluster.
     *
     * @param <T> the value type
     * @param map the map to search
     * @param name the database name
     * @return the value, never {@code null}
     * @throws IllegalStateException if the cluster is closed
     * @throws ClusterConfigurationException if the name is not in the map
     */
    private <T> T lookup(Map<String, T> map, String name) {
        ensureOpen();
        return requireEntry(map, name);
    }

    /**
     * Indexes definitions by their name, keeping the given order.
     *
     * @param definitions the definitions to index
     * @return a map from name to definition
     * @throws ClusterConfigurationException if a name is {@code null} or used twice
     */
    private static Map<String, DatabaseDefinition> indexByName(Collection<DatabaseDefinition> definitions) {
        Map<String, DatabaseDefinition> indexed = new LinkedHashMap<>();
        for (DatabaseDefinition definition : definitions) {
            if (definition.getName() == null) {
                throw new ClusterConfigurationException("Every database definition needs a name");
            }
            if (indexed.put(definition.getName(), definition) != null) {
                throw new ClusterConfigurationException("Duplicate database name '" + definition.getName() + "'");
            }
        }
        return indexed;
    }

    /**
     * Looks up a database-specific value and fails with a clear message if the name is unknown.
     *
     * @param <T> the value type
     * @param map the map to search
     * @param name the database name
     * @return the value, never {@code null}
     * @throws ClusterConfigurationException if the name is not in the map
     */
    private static <T> T requireEntry(Map<String, T> map, String name) {
        T value = map.get(name);
        if (value == null) {
            throw new ClusterConfigurationException("Unknown database '" + name + "': not present in the cluster configuration");
        }
        return value;
    }
}
