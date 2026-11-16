// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.connection;

import com.zaxxer.hikari.HikariDataSource;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.cluster.PoolSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link DataSourceFactory} pool configuration, run against a temporary SQLite file. */
class DataSourceFactoryTest {

    /**
     * Creates a pool for a temporary SQLite database with the given pool settings.
     *
     * @param tempDir the directory that holds the database file
     * @param pool the pool settings to apply
     * @return the open pool, which the test must close
     */
    private HikariDataSource create(Path tempDir, PoolSettings pool) {
        DatabaseDefinition definition = DatabaseDefinition.builder()
                .name("pool-test")
                .type(DatabaseType.SQLITE)
                .filePath(tempDir.resolve("pool.db").toString().replace("\\", "/"))
                .pool(pool)
                .build();
        return DataSourceFactory.create(definition);
    }

    /** The configured keep-alive time reaches the Hikari configuration. */
    @Test
    void appliesTheConfiguredKeepaliveTime(@TempDir Path tempDir) {
        PoolSettings pool = PoolSettings.builder()
                .maximumPoolSize(2)
                .minimumIdle(0)
                .connectionTimeoutMs(30000)
                .idleTimeoutMs(600000)
                .maxLifetimeMs(1800000)
                .keepaliveTimeMs(60000)
                .build();

        try (HikariDataSource dataSource = create(tempDir, pool)) {
            assertEquals(60000, dataSource.getKeepaliveTime());
        }
    }

    /** Keep-alive stays disabled for the SQLite defaults. */
    @Test
    void leavesKeepaliveDisabledForSqliteDefaults(@TempDir Path tempDir) {
        try (HikariDataSource dataSource = create(tempDir, PoolSettings.defaultsFor(DatabaseType.SQLITE))) {
            assertEquals(0, dataSource.getKeepaliveTime());
        }
    }

    /** Without explicit settings a SQLite file gets one permanent connection, WAL and a busy timeout. */
    @Test
    void usesFileFriendlyDefaultsForSqlite(@TempDir Path tempDir) throws Exception {
        try (HikariDataSource dataSource = create(tempDir, null)) {
            assertEquals(1, dataSource.getMaximumPoolSize());
            assertEquals(1, dataSource.getMinimumIdle());
            assertEquals(0, dataSource.getMaxLifetime());
            assertTrue(dataSource.getJdbcUrl().contains("journal_mode=WAL"));
            assertTrue(dataSource.getJdbcUrl().contains("busy_timeout=5000"));
            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement();
                 var resultSet = statement.executeQuery("PRAGMA journal_mode")) {
                resultSet.next();
                assertEquals("wal", resultSet.getString(1).toLowerCase());
            }
        }
    }

    /** Options set by the user win over the SQLite defaults. */
    @Test
    void keepsUserSqliteOptions(@TempDir Path tempDir) {
        DatabaseDefinition definition = DatabaseDefinition.builder()
                .name("opts")
                .type(DatabaseType.SQLITE)
                .filePath(tempDir.resolve("o.db").toString().replace("\\", "/"))
                .options(java.util.Map.of("busy_timeout", "100"))
                .build();
        try (HikariDataSource dataSource = DataSourceFactory.create(definition)) {
            assertTrue(dataSource.getJdbcUrl().contains("busy_timeout=100"));
            assertFalse(dataSource.getJdbcUrl().contains("busy_timeout=5000"));
        }
    }

    /** Server engines keep the larger default pool with a few idle connections and keep-alive. */
    @Test
    void serverDefaultsKeepAPoolWithKeepalive() {
        PoolSettings defaults = PoolSettings.defaultsFor(DatabaseType.POSTGRESQL);
        assertEquals(10, defaults.getMaximumPoolSize());
        assertEquals(2, defaults.getMinimumIdle());
        assertTrue(defaults.getKeepaliveTimeMs() > 0 && defaults.getKeepaliveTimeMs() < defaults.getMaxLifetimeMs());
    }

    /** Leak detection, validation timeout and init SQL reach the Hikari configuration. */
    @Test
    void appliesLeakDetectionAndInitSettings(@TempDir Path tempDir) {
        PoolSettings pool = PoolSettings.builder()
                .maximumPoolSize(2)
                .minimumIdle(0)
                .connectionTimeoutMs(30000)
                .idleTimeoutMs(600000)
                .maxLifetimeMs(1800000)
                .leakDetectionThresholdMs(7000)
                .initializationFailTimeoutMs(-1)
                .validationTimeoutMs(3000)
                .connectionInitSql("SELECT 1")
                .build();

        try (HikariDataSource dataSource = create(tempDir, pool)) {
            assertEquals(7000, dataSource.getLeakDetectionThreshold());
            assertEquals(-1, dataSource.getInitializationFailTimeout());
            assertEquals(3000, dataSource.getValidationTimeout());
            assertEquals("SELECT 1", dataSource.getConnectionInitSql());
        }
    }
}
