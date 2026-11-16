// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.connection;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import io.github.littleshystar02.lss02.databases.cluster.ClusterConfigurationException;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.cluster.PoolSettings;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests for the pool creation and the URL rules of {@link DataSourceFactory} that need no server. */
class DataSourceFactoryConfigTest {

    /**
     * Builds a server definition.
     *
     * @param type the engine
     * @param host the host
     * @param options the driver options, may be {@code null}
     * @return the definition
     */
    private static DatabaseDefinition server(DatabaseType type, String host, Map<String, String> options) {
        return DatabaseDefinition.builder().name("db").type(type).host(host).database("app").options(options).build();
    }

    /** An explicit JDBC URL is used as is, even without a type. */
    @Test
    void anExplicitUrlIsUsedVerbatim() {
        DatabaseDefinition definition = DatabaseDefinition.builder().name("db").jdbcUrl("jdbc:h2:mem:x;MODE=MySQL").build();
        assertEquals("jdbc:h2:mem:x;MODE=MySQL", DataSourceFactory.resolveJdbcUrl(definition));
    }

    /** A definition with neither URL nor type is a configuration error that names the database. */
    @Test
    void aDefinitionWithoutUrlOrTypeFails() {
        ClusterConfigurationException error = assertThrows(ClusterConfigurationException.class,
                () -> DataSourceFactory.resolveJdbcUrl(DatabaseDefinition.builder().name("lonely").build()));
        assertTrue(error.getMessage().contains("lonely"), error.getMessage());
    }

    /** SQLite needs a usable file path. */
    @Test
    void sqliteNeedsAValidFilePath() {
        DatabaseDefinition missing = DatabaseDefinition.builder().name("db").type(DatabaseType.SQLITE).build();
        assertThrows(ClusterConfigurationException.class, () -> DataSourceFactory.resolveJdbcUrl(missing));
        for (String bad : new String[] {" ", "a.db;DROP", "a\nb.db"}) {
            DatabaseDefinition definition = DatabaseDefinition.builder().name("db").type(DatabaseType.SQLITE).filePath(bad).build();
            assertThrows(ClusterConfigurationException.class, () -> DataSourceFactory.resolveJdbcUrl(definition), bad);
        }
    }

    /** In-memory SQLite keeps the options untouched, file databases get WAL and a busy timeout. */
    @Test
    void sqliteDefaultsApplyOnlyToFiles() {
        DatabaseDefinition memory = DatabaseDefinition.builder().name("db").type(DatabaseType.SQLITE).filePath(":memory:").build();
        assertEquals("jdbc:sqlite::memory:", DataSourceFactory.resolveJdbcUrl(memory));
        DatabaseDefinition file = DatabaseDefinition.builder().name("db").type(DatabaseType.SQLITE).filePath("data/a.db").build();
        String url = DataSourceFactory.resolveJdbcUrl(file);
        assertTrue(url.contains("journal_mode=WAL") && url.contains("busy_timeout=5000"), url);
    }

    /** Server engines need a host and a database name. */
    @Test
    void serverEnginesNeedHostAndDatabase() {
        DatabaseDefinition noHost = DatabaseDefinition.builder().name("db").type(DatabaseType.MYSQL).database("app").build();
        DatabaseDefinition noDatabase = DatabaseDefinition.builder().name("db").type(DatabaseType.MYSQL).host("h").build();
        assertThrows(ClusterConfigurationException.class, () -> DataSourceFactory.resolveJdbcUrl(noHost));
        assertThrows(ClusterConfigurationException.class, () -> DataSourceFactory.resolveJdbcUrl(noDatabase));
    }

    /** Each engine gets its standard port when none is configured. */
    @Test
    void standardPortsAreUsed() {
        assertEquals("jdbc:mysql://localhost:3306/app", DataSourceFactory.resolveJdbcUrl(server(DatabaseType.MYSQL, "localhost", null)));
        assertEquals("jdbc:mariadb://localhost:3306/app", DataSourceFactory.resolveJdbcUrl(server(DatabaseType.MARIADB, "localhost", null)));
        assertEquals("jdbc:postgresql://localhost:5432/app", DataSourceFactory.resolveJdbcUrl(server(DatabaseType.POSTGRESQL, "localhost", null)));
    }

    /**
     * Remote hosts without TLS and risky driver options only produce warnings: the URL is still built.
     * Every spelling of "TLS on" is accepted.
     */
    @Test
    void warningsDoNotChangeTheUrl() {
        String insecure = DataSourceFactory.resolveJdbcUrl(server(DatabaseType.MYSQL, "db.example.org", Map.of("allowMultiQueries", "true")));
        assertEquals("jdbc:mysql://db.example.org:3306/app?allowMultiQueries=true", insecure);

        for (Map<String, String> tls : List.of(
                Map.of("sslMode", "REQUIRED"), Map.of("useSSL", "true"), Map.of("ssl", "true"), Map.of("sslmode", "verify-full"))) {
            String url = DataSourceFactory.resolveJdbcUrl(server(DatabaseType.POSTGRESQL, "db.example.org", tls));
            assertTrue(url.startsWith("jdbc:postgresql://db.example.org:5432/app?"), url);
        }
        for (String off : new String[] {"disable", "prefer"}) {
            assertNotNull(DataSourceFactory.resolveJdbcUrl(server(DatabaseType.POSTGRESQL, "[::1]", Map.of("sslmode", off))));
        }
        assertNotNull(DataSourceFactory.resolveJdbcUrl(server(DatabaseType.MYSQL, "127.0.0.1", Map.of("useSSL", "false"))));
    }

    /** The pool takes the credentials, the init statement and the metrics hook from the definition. */
    @Test
    void createAppliesCredentialsInitSqlAndMetrics() throws Exception {
        MetricsTrackerFactory metrics = mock(MetricsTrackerFactory.class);
        when(metrics.create(anyString(), any())).thenReturn(mock(IMetricsTracker.class));
        DatabaseDefinition definition = DatabaseDefinition.builder()
                .name("full")
                .jdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .pool(PoolSettings.builder().maximumPoolSize(2).minimumIdle(1).connectionTimeoutMs(5000)
                        .idleTimeoutMs(600000).maxLifetimeMs(1800000).keepaliveTimeMs(0).validationTimeoutMs(1000)
                        .initializationFailTimeoutMs(1).connectionInitSql("SELECT 1").registerMbeans(false).build())
                .build();

        try (HikariDataSource dataSource = DataSourceFactory.create(definition, metrics)) {
            assertEquals("full", dataSource.getPoolName());
            assertEquals("sa", dataSource.getUsername());
            assertEquals("SELECT 1", dataSource.getConnectionInitSql());
            try (Connection connection = dataSource.getConnection()) {
                assertTrue(connection.isValid(2));
            }
            verify(metrics).create(anyString(), any());
        }
    }
}
