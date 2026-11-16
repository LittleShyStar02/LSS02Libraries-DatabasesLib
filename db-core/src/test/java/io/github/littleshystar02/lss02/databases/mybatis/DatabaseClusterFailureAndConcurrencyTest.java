// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.mybatis;

import com.zaxxer.hikari.metrics.IMetricsTracker;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import io.github.littleshystar02.lss02.databases.bootstrap.SchemaMigrationException;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.cluster.PoolSettings;
import io.github.littleshystar02.lss02.databases.mybatis.fixtures.User;
import io.github.littleshystar02.lss02.databases.mybatis.fixtures.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Startup failures, concurrent use and shutdown of {@link DatabaseCluster} on SQLite. */
class DatabaseClusterFailureAndConcurrencyTest {

    private static final String MAPPERS = "io.github.littleshystar02.lss02.databases.mybatis.fixtures";

    /**
     * Builds a SQLite definition with the user table.
     *
     * @param dir the directory of the file
     * @param name the database name
     * @param poolSize the maximum pool size
     * @return the definition
     */
    private static DatabaseDefinition sqlite(Path dir, String name, int poolSize) {
        return DatabaseDefinition.builder()
                .name(name)
                .type(DatabaseType.SQLITE)
                .filePath(dir.resolve(name + ".db").toString().replace("\\", "/"))
                .setupScript("classpath:db/sqlite/setup.sql")
                .mapperBasePackage(MAPPERS)
                .pool(PoolSettings.builder().maximumPoolSize(poolSize).minimumIdle(1).connectionTimeoutMs(20000)
                        .idleTimeoutMs(0).maxLifetimeMs(0).keepaliveTimeMs(0).validationTimeoutMs(5000)
                        .initializationFailTimeoutMs(1).build())
                .build();
    }

    /**
     * Inserts one user.
     *
     * @param mapper the mapper
     * @param id the id
     */
    private static void insert(UserMapper mapper, int id) {
        User user = new User();
        user.setId(id);
        user.setName("user-" + id);
        mapper.insert(user);
    }

    /** Many threads writing through the same cluster lose no row and leak no connection. */
    @Test
    void concurrentWritersLoseNothing(@TempDir Path dir) throws Exception {
        int threads = 8;
        int perThread = 25;
        try (DatabaseCluster cluster = new DatabaseCluster(List.of(sqlite(dir, "main", 4)))) {
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    int base = t * perThread;
                    futures.add(executor.submit(() -> {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            int id = base + i;
                            cluster.useMapper("main", UserMapper.class, mapper -> insert(mapper, id));
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(60, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }
            assertEquals(threads * perThread, cluster.withMapper("main", UserMapper.class, UserMapper::findAll).size());
            assertEquals(0, cluster.health().get("main").activeConnections());
        }
    }

    /** Async calls submitted together all complete and see each other's writes afterwards. */
    @Test
    void concurrentAsyncCallsAllComplete(@TempDir Path dir) throws Exception {
        try (DatabaseCluster cluster = new DatabaseCluster(List.of(sqlite(dir, "main", 4)))) {
            List<CompletableFuture<Void>> inserts = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int id = i;
                inserts.add(cluster.useMapperAsync("main", UserMapper.class, mapper -> insert(mapper, id)));
            }
            CompletableFuture.allOf(inserts.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
            assertEquals(40, cluster.withMapperAsync("main", UserMapper.class, UserMapper::findAll)
                    .get(10, TimeUnit.SECONDS).size());
        }
    }

    /** Close waits for work already running and later calls fail with a clear error. */
    @Test
    void closeWaitsForRunningAsyncWork(@TempDir Path dir) throws Exception {
        DatabaseCluster cluster = new DatabaseCluster(List.of(sqlite(dir, "main", 2)));
        CountDownLatch running = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();
        CompletableFuture<Void> slow = cluster.useMapperAsync("main", UserMapper.class, mapper -> {
            running.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.incrementAndGet();
        });
        assertTrue(running.await(10, TimeUnit.SECONDS));

        cluster.close();

        assertTrue(slow.isDone());
        assertEquals(1, finished.get());
        assertThrows(IllegalStateException.class, () -> cluster.useMapper("main", UserMapper.class, m -> { }));
    }

    /** A single unreachable database fails the start with the connection error. */
    @Test
    void aSingleUnreachableDatabaseFailsTheStart() {
        DatabaseDefinition down = DatabaseDefinition.builder()
                .name("down").type(DatabaseType.MYSQL).host("127.0.0.1").port(1).database("none")
                .pool(PoolSettings.builder().maximumPoolSize(1).minimumIdle(0).connectionTimeoutMs(1000)
                        .idleTimeoutMs(600000).maxLifetimeMs(1800000).initializationFailTimeoutMs(1).build())
                .build();
        assertThrows(RuntimeException.class, () -> new DatabaseCluster(List.of(down)));
    }

    /** A broken schema script of the second database closes the pool of the first and rethrows. */
    @Test
    void aFailingBootstrapClosesEveryPool(@TempDir Path dir) throws Exception {
        Path migrations = Files.createDirectory(dir.resolve("migrations"));
        Files.writeString(migrations.resolve("V1__broken.sql"), "INSERT INTO missing_table VALUES (1);");
        DatabaseDefinition good = sqlite(dir, "good", 1);
        DatabaseDefinition broken = DatabaseDefinition.builder()
                .name("broken").type(DatabaseType.SQLITE)
                .filePath(dir.resolve("broken.db").toString().replace("\\", "/"))
                .versionedScriptsPath(migrations.toString())
                .build();

        assertThrows(SchemaMigrationException.class, () -> new DatabaseCluster(List.of(good, broken)));

        // Both pools are closed: the files can be removed even on Windows.
        Thread.sleep(300);
        Files.deleteIfExists(dir.resolve("good.db"));
        assertFalse(Files.exists(dir.resolve("good.db")));
    }

    /** A metrics factory handed to the cluster reaches every pool. */
    @Test
    void metricsFactoryReachesThePools(@TempDir Path dir) {
        MetricsTrackerFactory metrics = mock(MetricsTrackerFactory.class);
        when(metrics.create(anyString(), any())).thenReturn(mock(IMetricsTracker.class));
        try (DatabaseCluster cluster = new DatabaseCluster(List.of(sqlite(dir, "main", 1)), null, metrics)) {
            assertTrue(cluster.ping("main"));
        }
        verify(metrics).create(anyString(), any());
    }
}
