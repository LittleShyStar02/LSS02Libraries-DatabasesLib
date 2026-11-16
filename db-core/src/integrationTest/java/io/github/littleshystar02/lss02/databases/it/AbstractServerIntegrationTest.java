// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.it;

import com.zaxxer.hikari.HikariDataSource;
import io.github.littleshystar02.lss02.databases.bootstrap.SchemaBootstrapper;
import io.github.littleshystar02.lss02.databases.bootstrap.SchemaMigrationException;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.cluster.PoolSettings;
import io.github.littleshystar02.lss02.databases.connection.DataSourceFactory;
import io.github.littleshystar02.lss02.databases.connection.DatabaseHealth;
import io.github.littleshystar02.lss02.databases.mybatis.DatabaseCluster;
import io.github.littleshystar02.lss02.databases.mybatis.fixtures.User;
import io.github.littleshystar02.lss02.databases.mybatis.fixtures.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.JdbcDatabaseContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios that must hold on a real database server. A subclass supplies a running Testcontainers
 * container; these tests only run where Docker is available (the {@code integrationTest} task).
 */
abstract class AbstractServerIntegrationTest {

    /**
     * Returns the running container of the engine under test.
     *
     * @return the container
     */
    abstract JdbcDatabaseContainer<?> container();

    /**
     * Returns the engine under test.
     *
     * @return the database type
     */
    abstract DatabaseType type();

    /**
     * Returns the port the server listens on inside the container.
     *
     * @return the container port
     */
    abstract int containerPort();

    /**
     * Returns the driver options a structured (host/port/database) definition needs for this server.
     *
     * @return the options, possibly empty
     */
    Map<String, String> options() {
        return Map.of();
    }

    /**
     * Opens a small pool on the container database.
     *
     * @return the pool
     */
    HikariDataSource open() {
        DatabaseDefinition definition = DatabaseDefinition.builder()
                .name("it")
                .type(type())
                .jdbcUrl(container().getJdbcUrl())
                .username(container().getUsername())
                .password(container().getPassword())
                .pool(PoolSettings.builder().maximumPoolSize(6).minimumIdle(1).connectionTimeoutMs(30000)
                        .idleTimeoutMs(600000).maxLifetimeMs(1800000).keepaliveTimeMs(0).validationTimeoutMs(5000)
                        .initializationFailTimeoutMs(1).build())
                .build();
        return DataSourceFactory.create(definition);
    }

    /**
     * Drops every table of the test database so each test starts from nothing (the Flyway history
     * table included).
     *
     * @throws SQLException if the cleanup fails
     */
    @BeforeEach
    void cleanDatabase() throws SQLException {
        try (HikariDataSource dataSource = open(); Connection connection = dataSource.getConnection()) {
            String quote = connection.getMetaData().getIdentifierQuoteString();
            List<String> tables = new ArrayList<>();
            try (ResultSet rows = connection.getMetaData().getTables(
                    connection.getCatalog(), connection.getSchema(), null, new String[] {"TABLE"})) {
                while (rows.next()) {
                    tables.add(rows.getString("TABLE_NAME"));
                }
            }
            try (Statement statement = connection.createStatement()) {
                for (String table : tables) {
                    statement.execute("DROP TABLE IF EXISTS " + quote + table + quote + " CASCADE");
                }
            }
        }
    }

    /**
     * Counts the rows of a table.
     *
     * @param dataSource the database
     * @param table the table
     * @return the row count
     * @throws SQLException if the query fails
     */
    static int count(HikariDataSource dataSource, String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * Creates a directory of migrations.
     *
     * @param dir the parent
     * @param files alternating file name and content
     * @return the directory
     * @throws Exception if a file cannot be written
     */
    static Path scripts(Path dir, String... files) throws Exception {
        Path directory = Files.createDirectories(dir.resolve("migrations"));
        for (int i = 0; i < files.length; i += 2) {
            Files.writeString(directory.resolve(files[i]), files[i + 1]);
        }
        return directory;
    }

    /** Baseline plus versioned scripts apply once, in order, and a rerun does nothing. */
    @Test
    void bootstrapAppliesScriptsOnceInOrder(@TempDir Path dir) throws Exception {
        Path setup = dir.resolve("setup.sql");
        Files.writeString(setup, "CREATE TABLE base (id INT PRIMARY KEY, label VARCHAR(60));");
        Path migrations = scripts(dir,
                "V1__first.sql", "INSERT INTO base VALUES (1, 'a; with semicolon');",
                "V2__second.sql", "INSERT INTO base VALUES (2, 'b');");
        try (HikariDataSource dataSource = open()) {
            SchemaBootstrapper.bootstrap(dataSource, setup.toString(), migrations.toString());
            SchemaBootstrapper.bootstrap(dataSource, setup.toString(), migrations.toString());
            assertEquals(2, count(dataSource, "base"));
        }
    }

    /** Two pools bootstrapping the same database at the same time apply each script exactly once (Flyway lock). */
    @Test
    void twoSimultaneousBootstrapsApplyEachScriptOnce(@TempDir Path dir) throws Exception {
        Path migrations = scripts(dir,
                "V1__table.sql", "CREATE TABLE shared (id INT PRIMARY KEY);",
                "V2__row.sql", "INSERT INTO shared VALUES (1);",
                "V3__more.sql", "INSERT INTO shared VALUES (2);");
        try (HikariDataSource first = open(); HikariDataSource second = open()) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Void>> futures = new ArrayList<>();
                for (HikariDataSource source : List.of(first, second)) {
                    Callable<Void> task = () -> {
                        start.await();
                        SchemaBootstrapper.bootstrap(source, null, migrations.toString());
                        return null;
                    };
                    futures.add(executor.submit(task));
                }
                start.countDown();
                for (Future<Void> future : futures) {
                    future.get(120, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }
            assertEquals(2, count(first, "shared"));
        }
    }

    /** A modified applied script is refused on the next start. */
    @Test
    void aModifiedScriptIsRejected(@TempDir Path dir) throws Exception {
        Path migrations = scripts(dir, "V1__t.sql", "CREATE TABLE t (id INT);");
        try (HikariDataSource dataSource = open()) {
            SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString());
            Files.writeString(migrations.resolve("V1__t.sql"), "CREATE TABLE t (id INT, extra INT);");
            assertThrows(SchemaMigrationException.class,
                    () -> SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString()));
        }
    }

    /** A failing script names itself and the statement, and the scripts after it never run. */
    @Test
    void aFailingScriptStopsTheRun(@TempDir Path dir) throws Exception {
        Path migrations = scripts(dir,
                "V1__ok.sql", "CREATE TABLE ok (id INT);",
                "V2__bad.sql", "INSERT INTO missing_table VALUES (1);",
                "V3__never.sql", "CREATE TABLE never (id INT);");
        try (HikariDataSource dataSource = open()) {
            SchemaMigrationException error = assertThrows(SchemaMigrationException.class,
                    () -> SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString()));
            assertTrue(error.getMessage().contains("V2__bad.sql"), error.getMessage());
            assertTrue(error.getMessage().contains("missing_table"), error.getMessage());
            try (Connection connection = dataSource.getConnection();
                 ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(), "never", null)) {
                assertFalse(tables.next());
            }
        }
    }

    /** A database made by a release that used lss02_schema_history is adopted: only newer scripts run. */
    @Test
    void theLegacyHistoryIsAdopted(@TempDir Path dir) throws Exception {
        Path setup = dir.resolve("setup.sql");
        Files.writeString(setup, "CREATE TABLE base (id INT PRIMARY KEY);");
        Path migrations = scripts(dir,
                "V1__one.sql", "CREATE TABLE one (id INT PRIMARY KEY);",
                "V2__two.sql", "CREATE TABLE two (id INT PRIMARY KEY);");
        try (HikariDataSource dataSource = open()) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE base (id INT PRIMARY KEY)");
                statement.execute("CREATE TABLE one (id INT PRIMARY KEY)");
                statement.execute("CREATE TABLE lss02_schema_history (version INT PRIMARY KEY,"
                        + " description VARCHAR(255) NOT NULL, script_name VARCHAR(255) NOT NULL,"
                        + " checksum VARCHAR(64) NOT NULL, applied_at VARCHAR(64) NOT NULL)");
                statement.execute("INSERT INTO lss02_schema_history VALUES (0, 'baseline', 'setup.sql', 'x', 't')");
                statement.execute("INSERT INTO lss02_schema_history VALUES (1, 'one', 'V1__one.sql', 'y', 't')");
            }
            SchemaBootstrapper.bootstrap(dataSource, setup.toString(), migrations.toString());
            try (Connection connection = dataSource.getConnection();
                 ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(), "two", null)) {
                assertTrue(tables.next(), "the newer script must run");
            }
            assertEquals(2, count(dataSource, "lss02_schema_history"));
        }
    }

    /** A cluster built from host, port and database bootstraps, serves mappers, answers health checks and closes. */
    @Test
    void aClusterWorksEndToEnd() throws Exception {
        DatabaseDefinition definition = DatabaseDefinition.builder()
                .name("main")
                .type(type())
                .host(container().getHost())
                .port(container().getMappedPort(containerPort()))
                .database(container().getDatabaseName())
                .username(container().getUsername())
                .password(container().getPassword())
                .options(options())
                .setupScript("classpath:db/it/setup.sql")
                .mapperBasePackage("io.github.littleshystar02.lss02.databases.mybatis.fixtures")
                .pool(PoolSettings.builder().maximumPoolSize(4).minimumIdle(2).connectionTimeoutMs(30000)
                        .idleTimeoutMs(600000).maxLifetimeMs(1800000).keepaliveTimeMs(0).leakDetectionThresholdMs(20000)
                        .validationTimeoutMs(5000).initializationFailTimeoutMs(1).build())
                .build();

        try (DatabaseCluster cluster = new DatabaseCluster(List.of(definition))) {
            User user = new User();
            user.setId(1);
            user.setName("Ada");
            cluster.useMapper("main", UserMapper.class, mapper -> mapper.insert(user));
            assertEquals("Ada", cluster.withMapper("main", UserMapper.class, UserMapper::findAll).get(0).getName());
            assertEquals(1, cluster.useMapperAsync("main", UserMapper.class, mapper -> { })
                    .thenApply(ignored -> 1).get(30, TimeUnit.SECONDS));

            DatabaseHealth health = cluster.health().get("main");
            assertTrue(health.up(), String.valueOf(health.error()));
            assertEquals(0, health.activeConnections());
            assertTrue(cluster.ping("main"));
        }
    }
}
