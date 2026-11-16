// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.bootstrap;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs the bootstrap scenarios on every engine that is available without Docker: H2 in its MySQL and
 * PostgreSQL compatibility modes (a quick, indicative stand-in for the real servers, which the
 * Testcontainers tests cover) and SQLite.
 */
class SchemaBootstrapperEnginesTest {

    /** Engines the scenarios run on. */
    enum Engine {
        /** H2 pretending to be MySQL. */
        H2_MYSQL,
        /** H2 pretending to be PostgreSQL. */
        H2_POSTGRES,
        /** A SQLite file. */
        SQLITE
    }

    /**
     * Opens a pool on a fresh database of the given engine.
     *
     * @param engine the engine
     * @param dir directory for the SQLite file
     * @return the pool
     */
    private static HikariDataSource open(Engine engine, Path dir) {
        HikariConfig config = new HikariConfig();
        switch (engine) {
            case H2_MYSQL -> config.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            case H2_POSTGRES -> config.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            default -> config.setJdbcUrl("jdbc:sqlite:" + dir.resolve(UUID.randomUUID() + ".db").toString().replace("\\", "/"));
        }
        config.setMaximumPoolSize(engine == Engine.SQLITE ? 2 : 8);
        return new HikariDataSource(config);
    }

    /**
     * Counts the rows of a table.
     *
     * @param dataSource the database
     * @param table the table
     * @return the row count
     * @throws SQLException if the query fails
     */
    private static int count(DataSource dataSource, String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * Tells whether a table exists.
     *
     * @param dataSource the database
     * @param table the table name in lower case
     * @return {@code true} if it exists
     * @throws SQLException if the metadata cannot be read
     */
    private static boolean tableExists(DataSource dataSource, String table) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            for (String name : new String[] {table, table.toUpperCase()}) {
                try (ResultSet tables = connection.getMetaData().getTables(null, null, name, null)) {
                    if (tables.next()) {
                        return true;
                    }
                }
            }
            return false;
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
    private static Path scripts(Path dir, String... files) throws Exception {
        Path directory = Files.createDirectories(dir.resolve("migrations"));
        for (int i = 0; i < files.length; i += 2) {
            Files.writeString(directory.resolve(files[i]), files[i + 1]);
        }
        return directory;
    }

    /** Baseline, versioned scripts and a rerun behave the same on every engine. */
    @ParameterizedTest
    @EnumSource(Engine.class)
    void appliesBaselineThenVersionedScriptsOnce(Engine engine, @TempDir Path dir) throws Exception {
        Path setup = dir.resolve("setup.sql");
        Files.writeString(setup, "CREATE TABLE base (id INT PRIMARY KEY, label VARCHAR(40));");
        Path migrations = scripts(dir,
                "V1__rows.sql", "INSERT INTO base VALUES (1, 'a');",
                "V2__more_rows.sql", "INSERT INTO base VALUES (2, 'b; with semicolon');");

        try (HikariDataSource dataSource = open(engine, dir)) {
            SchemaBootstrapper.bootstrap(dataSource, setup.toString(), migrations.toString());
            assertEquals(2, count(dataSource, "base"));
            SchemaBootstrapper.bootstrap(dataSource, setup.toString(), migrations.toString());
            assertEquals(2, count(dataSource, "base"));
            assertTrue(tableExists(dataSource, "flyway_schema_history"));
        }
    }

    /** A modified applied script is rejected, with the script named in the message. */
    @ParameterizedTest
    @EnumSource(Engine.class)
    void rejectsAModifiedAppliedScript(Engine engine, @TempDir Path dir) throws Exception {
        Path migrations = scripts(dir, "V1__t.sql", "CREATE TABLE t (id INT);");
        try (HikariDataSource dataSource = open(engine, dir)) {
            SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString());
            Files.writeString(migrations.resolve("V1__t.sql"), "CREATE TABLE t (id INT, extra INT);");
            SchemaMigrationException error = assertThrows(SchemaMigrationException.class,
                    () -> SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString()));
            assertTrue(error.getMessage().contains("checksum mismatch") && error.getMessage().contains("version 1"),
                    error.getMessage());
        }
    }

    /** A script that fails reports its name and the broken statement, and later scripts never run. */
    @ParameterizedTest
    @EnumSource(Engine.class)
    void aFailingScriptStopsTheRun(Engine engine, @TempDir Path dir) throws Exception {
        Path migrations = scripts(dir,
                "V1__ok.sql", "CREATE TABLE ok (id INT);",
                "V2__bad.sql", "INSERT INTO missing_table VALUES (1);",
                "V3__never.sql", "CREATE TABLE never (id INT);");
        try (HikariDataSource dataSource = open(engine, dir)) {
            SchemaMigrationException error = assertThrows(SchemaMigrationException.class,
                    () -> SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString()));
            assertTrue(error.getMessage().contains("V2__bad.sql"), error.getMessage());
            assertTrue(error.getMessage().contains("missing_table"), error.getMessage());
            assertTrue(tableExists(dataSource, "ok"));
            assertFalse(tableExists(dataSource, "never"));
        }
    }

    /** A version added behind an already applied one is reported instead of being silently skipped. */
    @ParameterizedTest
    @EnumSource(Engine.class)
    void detectsAScriptAddedOutOfOrder(Engine engine, @TempDir Path dir) throws Exception {
        Path migrations = scripts(dir,
                "V1__a.sql", "CREATE TABLE a (id INT);",
                "V3__c.sql", "CREATE TABLE c (id INT);");
        try (HikariDataSource dataSource = open(engine, dir)) {
            SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString());
            Files.writeString(migrations.resolve("V2__b.sql"), "CREATE TABLE b (id INT);");
            assertThrows(SchemaMigrationException.class,
                    () -> SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString()));
            assertFalse(tableExists(dataSource, "b"));
        }
    }

    /** A database bootstrapped by a release without Flyway is adopted: only newer scripts run. */
    @ParameterizedTest
    @EnumSource(Engine.class)
    void convertsTheLegacyHistory(Engine engine, @TempDir Path dir) throws Exception {
        Path setup = dir.resolve("setup.sql");
        Files.writeString(setup, "CREATE TABLE base (id INT PRIMARY KEY);");
        Path migrations = scripts(dir,
                "V1__one.sql", "CREATE TABLE one (id INT PRIMARY KEY);",
                "V2__two.sql", "CREATE TABLE two (id INT PRIMARY KEY);");

        try (HikariDataSource dataSource = open(engine, dir)) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE base (id INT PRIMARY KEY)");
                statement.execute("CREATE TABLE one (id INT PRIMARY KEY)");
                statement.execute("CREATE TABLE lss02_schema_history (version INTEGER PRIMARY KEY,"
                        + " description VARCHAR(255) NOT NULL, script_name VARCHAR(255) NOT NULL,"
                        + " checksum VARCHAR(64) NOT NULL, applied_at VARCHAR(64) NOT NULL)");
                statement.execute("INSERT INTO lss02_schema_history VALUES (0, 'baseline', 'setup.sql', 'x', 't')");
                statement.execute("INSERT INTO lss02_schema_history VALUES (1, 'one', 'V1__one.sql', 'y', 't')");
            }
            SchemaBootstrapper.bootstrap(dataSource, setup.toString(), migrations.toString());

            assertTrue(tableExists(dataSource, "two"), "the newer script must run");
            assertEquals(2, count(dataSource, "lss02_schema_history"), "the old table is left untouched");
        }
    }

    /** An empty legacy history table is adopted at version 0 and every script runs. */
    @Test
    void anEmptyLegacyHistoryRunsEverything(@TempDir Path dir) throws Exception {
        Path migrations = scripts(dir, "V1__one.sql", "CREATE TABLE one (id INT);");
        try (HikariDataSource dataSource = open(Engine.H2_MYSQL, dir)) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE lss02_schema_history (version INTEGER PRIMARY KEY)");
            }
            SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString());
            assertTrue(tableExists(dataSource, "one"));
        }
    }

    /** Simultaneous bootstraps of one database apply each script exactly once, whatever the engine. */
    @ParameterizedTest
    @EnumSource(Engine.class)
    void concurrentBootstrapsApplyEachScriptOnce(Engine engine, @TempDir Path dir) throws Exception {
        Path migrations = scripts(dir,
                "V1__t.sql", "CREATE TABLE t (id INT PRIMARY KEY);",
                "V2__row.sql", "INSERT INTO t VALUES (1);");

        try (HikariDataSource dataSource = open(engine, dir)) {
            int threads = 4;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Void>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    Callable<Void> task = () -> {
                        start.await();
                        SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString());
                        return null;
                    };
                    futures.add(executor.submit(task));
                }
                start.countDown();
                for (Future<Void> future : futures) {
                    future.get();
                }
            } finally {
                executor.shutdownNow();
            }
            assertEquals(1, count(dataSource, "t"));
        }
    }

    /** A database that cannot be reached surfaces as a SchemaMigrationException. */
    @Test
    void anUnreachableDatabaseIsReportedClearly() throws Exception {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("connection refused"));
        SchemaMigrationException error = assertThrows(SchemaMigrationException.class,
                () -> SchemaBootstrapper.bootstrap(broken, null, null));
        assertTrue(error.getCause() instanceof SQLException);
    }

    /** A missing baseline file fails before the database is touched. */
    @Test
    void aMissingSetupScriptFails(@TempDir Path dir) throws Exception {
        try (HikariDataSource dataSource = open(Engine.H2_MYSQL, dir)) {
            assertThrows(RuntimeException.class,
                    () -> SchemaBootstrapper.bootstrap(dataSource, dir.resolve("nope.sql").toString(), null));
        }
    }
}
