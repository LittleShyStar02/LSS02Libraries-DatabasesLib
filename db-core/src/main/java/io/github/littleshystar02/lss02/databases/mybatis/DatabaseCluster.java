package io.github.littleshystar02.lss02.databases.mybatis;

import com.zaxxer.hikari.HikariDataSource;
import io.github.littleshystar02.lss02.databases.bootstrap.SchemaBootstrapper;
import io.github.littleshystar02.lss02.databases.cluster.ClusterConfigurationException;
import io.github.littleshystar02.lss02.databases.cluster.ClusterLoader;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseClusterConfiguration;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import io.github.littleshystar02.lss02.databases.connection.DataSourceFactory;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Entry point of the library: a set of named databases, each with its own connection pool and MyBatis
 * session factory.
 *
 * <p>Construction is eager. For every definition the cluster opens a pool, applies the schema
 * scripts when the definition configures any (see
 * {@link io.github.littleshystar02.lss02.databases.bootstrap.SchemaBootstrapper}) and builds the
 * session factory. If one database fails, the pools already opened are closed and the exception is
 * rethrown. Call {@link #close()} when the application stops.
 *
 * <p>Lookups are by database name and fail with {@link ClusterConfigurationException} for an unknown
 * name. The lookup methods only read data that is fixed after construction, so a cluster can be shared
 * between threads; each {@link SqlSession} must stay on one thread.
 */
public class DatabaseCluster implements AutoCloseable {

    /** Definitions keyed by database name, in configuration order. */
    private final Map<String, DatabaseDefinition> definitions;
    /** Open connection pools keyed by database name. */
    private final Map<String, HikariDataSource> dataSources = new LinkedHashMap<>();
    /** MyBatis session factories keyed by database name. */
    private final Map<String, SqlSessionFactory> sessionFactories = new LinkedHashMap<>();

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
        this(ClusterLoader.load(configuration), classLoader);
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
        this(indexByName(definitions), classLoader);
    }

    /**
     * Does the actual initialization of all databases. The context class loader is switched to
     * {@code classLoader} while pools, schemas and session factories are created, then restored.
     *
     * @param definitions the definitions keyed by name
     * @param classLoader the class loader to use during initialization, may be {@code null}
     */
    private DatabaseCluster(Map<String, DatabaseDefinition> definitions, ClassLoader classLoader) {
        this.definitions = definitions;
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        if (classLoader != null) {
            thread.setContextClassLoader(classLoader);
        }
        try {
            for (DatabaseDefinition definition : definitions.values()) {
                HikariDataSource dataSource = DataSourceFactory.create(definition);
                dataSources.put(definition.getName(), dataSource);

                if (definition.getSetupScript() != null || definition.getVersionedScriptsPath() != null) {
                    SchemaBootstrapper.bootstrap(
                            dataSource, definition.getSetupScript(), definition.getVersionedScriptsPath(), classLoader);
                }

                sessionFactories.put(definition.getName(), ClusterSqlSessionFactoryProvider.create(definition, dataSource));
            }
        } catch (RuntimeException e) {
            close();
            throw e;
        } finally {
            if (classLoader != null) {
                thread.setContextClassLoader(previous);
            }
        }
    }

    /**
     * Returns the definition a database was created from.
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
     */
    public DataSource getDataSource(String name) {
        return requireEntry(dataSources, name);
    }

    /**
     * Opens a MyBatis session with manual commit. The caller must commit if needed and close the session.
     *
     * @param name the database name
     * @return a new session
     * @throws ClusterConfigurationException if the name is unknown
     */
    public SqlSession openSession(String name) {
        return requireEntry(sessionFactories, name).openSession();
    }

    /**
     * Opens a MyBatis session with the chosen commit mode. The caller must close the session.
     *
     * @param name the database name
     * @param autoCommit {@code true} to commit after every statement
     * @return a new session
     * @throws ClusterConfigurationException if the name is unknown
     */
    public SqlSession openSession(String name, boolean autoCommit) {
        return requireEntry(sessionFactories, name).openSession(autoCommit);
    }

    /**
     * Runs an action with a mapper in a short session and returns its result. The session is committed
     * after the action returns and always closed.
     *
     * @param <T> the mapper type
     * @param <R> the result type
     * @param name the database name
     * @param mapperClass the mapper interface
     * @param action the code that uses the mapper
     * @return whatever the action returns
     * @throws ClusterConfigurationException if the name is unknown
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
     * committed after the action returns and always closed.
     *
     * @param <T> the mapper type
     * @param name the database name
     * @param mapperClass the mapper interface
     * @param action the code that uses the mapper
     * @throws ClusterConfigurationException if the name is unknown
     */
    public <T> void useMapper(String name, Class<T> mapperClass, Consumer<T> action) {
        try (SqlSession session = openSession(name)) {
            action.accept(session.getMapper(mapperClass));
            session.commit();
        }
    }

    /** Closes every connection pool of the cluster. The cluster must not be used afterwards. */
    @Override
    public void close() {
        for (HikariDataSource dataSource : dataSources.values()) {
            dataSource.close();
        }
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
            throw new ClusterConfigurationException("Unknown database '" + name + "' — not present in the cluster configuration");
        }
        return value;
    }
}
