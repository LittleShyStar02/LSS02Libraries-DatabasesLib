// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.mybatis;

import io.github.littleshystar02.lss02.databases.cluster.ClusterConfigurationException;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.cluster.PoolSettings;
import io.github.littleshystar02.lss02.databases.connection.DatabaseHealth;
import io.github.littleshystar02.lss02.databases.mybatis.fixtures.User;
import io.github.littleshystar02.lss02.databases.mybatis.fixtures.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the lifecycle, async API, health check and parallel start of {@link DatabaseCluster}. */
class DatabaseClusterLifecycleTest {

    /**
     * Builds the definition of a SQLite database with the user table and mapper.
     *
     * @param tempDir the directory of the database file
     * @param name the database name
     * @return the definition
     */
    private DatabaseDefinition sqlite(Path tempDir, String name) {
        return DatabaseDefinition.builder()
                .name(name)
                .type(DatabaseType.SQLITE)
                .filePath(tempDir.resolve(name + ".db").toString().replace("\\", "/"))
                .setupScript("classpath:db/sqlite/setup.sql")
                .mapperBasePackage("io.github.littleshystar02.lss02.databases.mybatis.fixtures")
                .build();
    }

    /**
     * Builds a MySQL database that refuses connections immediately (nothing listens on port 1).
     *
     * @param name the database name
     * @return the definition
     */
    private DatabaseDefinition unreachable(String name) {
        return DatabaseDefinition.builder()
                .name(name)
                .type(DatabaseType.MYSQL)
                .host("127.0.0.1")
                .port(1)
                .database("none")
                .pool(PoolSettings.builder().maximumPoolSize(1).minimumIdle(0).connectionTimeoutMs(1000)
                        .idleTimeoutMs(600000).maxLifetimeMs(1800000).initializationFailTimeoutMs(1).build())
                .build();
    }

    /** Closing twice is harmless, and later access fails with a clear IllegalStateException. */
    @Test
    void closeIsIdempotentAndAccessAfterCloseFails(@TempDir Path tempDir) {
        DatabaseCluster cluster = new DatabaseCluster(List.of(sqlite(tempDir, "main")));
        cluster.close();
        cluster.close();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> cluster.openSession("main"));
        assertTrue(e.getMessage().contains("closed"));
        assertThrows(IllegalStateException.class, () -> cluster.getDataSource("main"));
        assertThrows(IllegalStateException.class, () -> cluster.withMapper("main", UserMapper.class, UserMapper::findAll));
        assertThrows(IllegalStateException.class, () -> cluster.withMapperAsync("main", UserMapper.class, UserMapper::findAll));
        assertThrows(IllegalStateException.class, cluster::health);
        assertEquals("main", cluster.getDefinition("main").getName());
    }

    /** An unknown name is still reported as a configuration problem while the cluster is open. */
    @Test
    void unknownNameFailsWithConfigurationException(@TempDir Path tempDir) {
        try (DatabaseCluster cluster = new DatabaseCluster(List.of(sqlite(tempDir, "main")))) {
            assertThrows(ClusterConfigurationException.class,
                    () -> cluster.withMapperAsync("nope", UserMapper.class, m -> null));
        }
    }

    /** The async variants run off the calling thread and give back the result. */
    @Test
    void asyncMethodsRunOnADedicatedExecutor(@TempDir Path tempDir) throws Exception {
        try (DatabaseCluster cluster = new DatabaseCluster(List.of(sqlite(tempDir, "main")))) {
            Thread caller = Thread.currentThread();
            CompletableFuture<Thread> insert = cluster.withMapperAsync("main", UserMapper.class, mapper -> {
                User user = new User();
                user.setId(1);
                user.setName("Ada");
                mapper.insert(user);
                return Thread.currentThread();
            });
            Thread worker = insert.get(10, TimeUnit.SECONDS);
            assertNotEquals(caller, worker);
            assertTrue(worker.getName().startsWith("lss02-db-async"));
            assertTrue(worker.isDaemon());

            cluster.useMapperAsync("main", UserMapper.class, mapper -> {
                User user = new User();
                user.setId(2);
                user.setName("Bob");
                mapper.insert(user);
            }).get(10, TimeUnit.SECONDS);

            assertEquals(2, cluster.withMapperAsync("main", UserMapper.class, UserMapper::findAll)
                    .get(10, TimeUnit.SECONDS).size());
        }
    }

    /** A failing action completes the future exceptionally and does not leak the connection. */
    @Test
    void asyncFailureCompletesExceptionally(@TempDir Path tempDir) {
        try (DatabaseCluster cluster = new DatabaseCluster(List.of(sqlite(tempDir, "main")))) {
            CompletableFuture<Object> future = cluster.withMapperAsync("main", UserMapper.class, m -> {
                throw new IllegalArgumentException("boom");
            });
            ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof IllegalArgumentException);
            assertTrue(cluster.ping("main"));
        }
    }

    /** The health report covers every database and the pool counters. */
    @Test
    void healthReportsEveryDatabase(@TempDir Path tempDir) {
        try (DatabaseCluster cluster = new DatabaseCluster(List.of(sqlite(tempDir, "a"), sqlite(tempDir, "b")))) {
            Map<String, DatabaseHealth> health = cluster.health();
            assertEquals(List.of("a", "b"), List.copyOf(health.keySet()));
            DatabaseHealth a = health.get("a");
            assertTrue(a.up());
            assertNull(a.error());
            assertTrue(a.totalConnections() >= 1);
            assertTrue(cluster.ping("b"));
            assertThrows(UnsupportedOperationException.class, () -> health.put("x", a));
        }
    }

    /** One unreachable database aborts the start quickly and every other pool is closed. */
    @Test
    void failedParallelStartClosesTheOpenedPools(@TempDir Path tempDir) throws Exception {
        DatabaseDefinition good = sqlite(tempDir, "good");
        long begin = System.nanoTime();

        assertThrows(RuntimeException.class,
                () -> new DatabaseCluster(List.of(good, unreachable("bad1"), unreachable("bad2"))));

        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - begin) < 20);
        // The SQLite pool must have been closed: the file can be removed even on Windows.
        Thread.sleep(300);
        Files.deleteIfExists(tempDir.resolve("good.db"));
        assertFalse(Files.exists(tempDir.resolve("good.db")));
    }
}
