// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.bootstrap;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link SchemaBootstrapper} against H2 and SQLite databases. */
class SchemaBootstrapperTest {

    /**
     * Opens a pool on a private in-memory H2 database.
     *
     * @return the pool; the database lives as long as one connection is open
     */
    private static HikariDataSource h2() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(12);
        return new HikariDataSource(config);
    }

    /**
     * Opens a pool on a SQLite file.
     *
     * @param file the database file
     * @param poolSize the maximum pool size
     * @return the pool
     */
    private static HikariDataSource sqlite(Path file, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + file.toString().replace("\\", "/"));
        config.setMaximumPoolSize(poolSize);
        return new HikariDataSource(config);
    }

    /**
     * Writes a script file.
     *
     * @param directory the target directory
     * @param name the file name
     * @param content the script text
     * @throws IOException if the file cannot be written
     */
    private static void write(Path directory, String name, String content) throws IOException {
        Files.writeString(directory.resolve(name), content);
    }

    /**
     * Counts the rows of a table.
     *
     * @param dataSource the database
     * @param table the table
     * @return the row count
     * @throws SQLException if the query fails
     */
    private static int count(HikariDataSource dataSource, String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /** The setup script runs as the baseline, then the versioned scripts run in version order. */
    @Test
    void runsSetupScriptThenVersionedScriptsInOrder(@TempDir Path dir) throws Exception {
        Path migrations = Files.createDirectory(dir.resolve("migrations"));
        Path setup = dir.resolve("setup.sql");
        Files.writeString(setup, "CREATE TABLE base (id INT PRIMARY KEY);");
        write(migrations, "V2__fill.sql", "INSERT INTO extra VALUES (1);");
        write(migrations, "V1__extra.sql", "CREATE TABLE extra (id INT PRIMARY KEY);");
        write(migrations, "notes.txt", "ignored");

        try (HikariDataSource dataSource = h2()) {
            SchemaBootstrapper.bootstrap(dataSource, setup.toString(), migrations.toString());
            assertEquals(0, count(dataSource, "base"));
            assertEquals(1, count(dataSource, "extra"));

            // a second run is a no-op
            SchemaBootstrapper.bootstrap(dataSource, setup.toString(), migrations.toString());
            assertEquals(1, count(dataSource, "extra"));
        }
    }

    /** Semicolons inside string literals and comments do not split statements. */
    @Test
    void keepsSemicolonsInsideStringsAndComments(@TempDir Path dir) throws Exception {
        Path migrations = Files.createDirectory(dir.resolve("migrations"));
        write(migrations, "V1__text.sql", """
                -- a comment; with a semicolon
                CREATE TABLE notes (id INT PRIMARY KEY, body VARCHAR(100));
                INSERT INTO notes VALUES (1, 'first; second; third');
                """);

        try (HikariDataSource dataSource = h2()) {
            SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString());
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT body FROM notes")) {
                assertTrue(resultSet.next());
                assertEquals("first; second; third", resultSet.getString(1));
            }
        }
    }

    /** Two scripts with the same version are rejected before anything runs. */
    @Test
    void rejectsDuplicateVersionsWithoutRunningAnything(@TempDir Path dir) throws Exception {
        Path migrations = Files.createDirectory(dir.resolve("migrations"));
        write(migrations, "V2__a.sql", "CREATE TABLE a (id INT);");
        write(migrations, "V2__b.sql", "CREATE TABLE b (id INT);");

        try (HikariDataSource dataSource = h2()) {
            SchemaMigrationException error = assertThrows(SchemaMigrationException.class,
                    () -> SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString()));
            assertTrue(error.getMessage().contains("V2__a.sql") && error.getMessage().contains("V2__b.sql"),
                    error.getMessage());
            try (Connection connection = dataSource.getConnection();
                 ResultSet tables = connection.getMetaData().getTables(null, null, "A", null)) {
                assertTrue(!tables.next(), "no script must have run");
            }
        }
    }

    /** A failing script reports its name and the failing statement. */
    @Test
    void failureNamesTheScriptAndTheStatement(@TempDir Path dir) throws Exception {
        Path migrations = Files.createDirectory(dir.resolve("migrations"));
        write(migrations, "V1__broken.sql", "CREATE TABLE ok (id INT);\nINSERT INTO missing_table VALUES (1);");

        try (HikariDataSource dataSource = h2()) {
            SchemaMigrationException error = assertThrows(SchemaMigrationException.class,
                    () -> SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString()));
            assertTrue(error.getMessage().contains("V1__broken.sql"), error.getMessage());
            assertTrue(error.getMessage().contains("missing_table"), error.getMessage());
        }
    }

    /** Changing an applied script makes the next run fail on the checksum. */
    @Test
    void failsWhenAnAppliedScriptChanges(@TempDir Path dir) throws Exception {
        Path migrations = Files.createDirectory(dir.resolve("migrations"));
        write(migrations, "V1__t.sql", "CREATE TABLE t (id INT);");

        try (HikariDataSource dataSource = h2()) {
            SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString());
            write(migrations, "V1__t.sql", "CREATE TABLE t (id INT, extra INT);");
            assertThrows(SchemaMigrationException.class,
                    () -> SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString()));
        }
    }

    /** A script that fails midway is rolled back on an engine with transactional DDL (SQLite). */
    @Test
    void rollsBackAFailedScriptOnSqlite(@TempDir Path dir) throws Exception {
        Path migrations = Files.createDirectory(dir.resolve("migrations"));
        write(migrations, "V1__broken.sql", "CREATE TABLE half (id INTEGER);\nINSERT INTO nowhere VALUES (1);");

        try (HikariDataSource dataSource = sqlite(dir.resolve("a.db"), 2)) {
            assertThrows(SchemaMigrationException.class,
                    () -> SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString()));
            try (Connection connection = dataSource.getConnection();
                 ResultSet tables = connection.getMetaData().getTables(null, null, "half", null)) {
                assertTrue(!tables.next(), "the partial table must be rolled back");
            }
        }
    }

    /**
     * A database created by a release that used {@code lss02_schema_history} is adopted as a baseline:
     * the setup script and the applied versions do not run again, only newer scripts do.
     */
    @Test
    void adoptsALegacyHistoryWithoutRerunningScripts(@TempDir Path dir) throws Exception {
        Path migrations = Files.createDirectory(dir.resolve("migrations"));
        Path setup = dir.resolve("setup.sql");
        Files.writeString(setup, "CREATE TABLE base (id INT PRIMARY KEY);");
        write(migrations, "V1__extra.sql", "CREATE TABLE extra (id INT PRIMARY KEY);");
        write(migrations, "V2__more.sql", "CREATE TABLE more (id INT PRIMARY KEY);");

        try (HikariDataSource dataSource = h2()) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE base (id INT PRIMARY KEY)");
                statement.execute("CREATE TABLE extra (id INT PRIMARY KEY)");
                statement.execute("CREATE TABLE lss02_schema_history (version INTEGER PRIMARY KEY, description VARCHAR(255) NOT NULL,"
                        + " script_name VARCHAR(255) NOT NULL, checksum VARCHAR(64) NOT NULL, applied_at VARCHAR(64) NOT NULL)");
                statement.execute("INSERT INTO lss02_schema_history VALUES (0, 'baseline', 'setup.sql', 'x', 't')");
                statement.execute("INSERT INTO lss02_schema_history VALUES (1, 'extra', 'V1__extra.sql', 'y', 't')");
            }

            SchemaBootstrapper.bootstrap(dataSource, setup.toString(), migrations.toString());

            assertEquals(0, count(dataSource, "more"));
            assertEquals(2, count(dataSource, "lss02_schema_history"));
        }
    }

    /** The same conversion works on SQLite, where only the baseline script had been applied. */
    @Test
    void adoptsALegacyHistoryOnSqlite(@TempDir Path dir) throws Exception {
        Path migrations = Files.createDirectory(dir.resolve("migrations"));
        Path setup = dir.resolve("setup.sql");
        Files.writeString(setup, "CREATE TABLE base (id INTEGER PRIMARY KEY);");
        write(migrations, "V1__extra.sql", "CREATE TABLE extra (id INTEGER PRIMARY KEY);");

        try (HikariDataSource dataSource = sqlite(dir.resolve("legacy.db"), 2)) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE base (id INTEGER PRIMARY KEY)");
                statement.execute("CREATE TABLE lss02_schema_history (version INTEGER PRIMARY KEY, description VARCHAR(255) NOT NULL,"
                        + " script_name VARCHAR(255) NOT NULL, checksum VARCHAR(64) NOT NULL, applied_at VARCHAR(64) NOT NULL)");
                statement.execute("INSERT INTO lss02_schema_history VALUES (0, 'baseline', 'setup.sql', 'x', 't')");
            }

            SchemaBootstrapper.bootstrap(dataSource, setup.toString(), migrations.toString());
            assertEquals(0, count(dataSource, "extra"));
            // and a second start stays quiet
            SchemaBootstrapper.bootstrap(dataSource, setup.toString(), migrations.toString());
        }
    }

    /** A pool limited to one connection does not deadlock the bootstrap. */
    @Test
    void worksWithASingleConnectionPool(@TempDir Path dir) throws Exception {
        Path migrations = Files.createDirectory(dir.resolve("migrations"));
        write(migrations, "V1__t.sql", "CREATE TABLE t (id INTEGER PRIMARY KEY);");

        try (HikariDataSource dataSource = sqlite(dir.resolve("single.db"), 1)) {
            SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString());
            assertEquals(0, count(dataSource, "t"));
        }
    }

    /** Several simultaneous bootstraps of one H2 database apply every script exactly once. */
    @Test
    void concurrentBootstrapsOnH2ApplyEachScriptOnce(@TempDir Path dir) throws Exception {
        Path migrations = Files.createDirectory(dir.resolve("migrations"));
        write(migrations, "V1__t.sql", "CREATE TABLE t (id INT PRIMARY KEY);");
        write(migrations, "V2__row.sql", "INSERT INTO t VALUES (1);");

        try (HikariDataSource dataSource = h2()) {
            runConcurrently(4, () -> {
                SchemaBootstrapper.bootstrap(dataSource, null, migrations.toString());
                return null;
            });
            assertEquals(1, count(dataSource, "t"));
        }
    }

    /** Several simultaneous bootstraps of one SQLite file apply every script exactly once. */
    @Test
    void concurrentBootstrapsOnSqliteApplyEachScriptOnce(@TempDir Path dir) throws Exception {
        Path migrations = Files.createDirectory(dir.resolve("migrations"));
        write(migrations, "V1__t.sql", "CREATE TABLE t (id INTEGER PRIMARY KEY);");
        write(migrations, "V2__row.sql", "INSERT INTO t VALUES (1);");
        Path file = dir.resolve("shared.db");

        try (HikariDataSource first = sqlite(file, 2); HikariDataSource second = sqlite(file, 2)) {
            List<HikariDataSource> sources = List.of(first, second, first, second);
            int[] next = {0};
            runConcurrently(4, () -> {
                HikariDataSource source;
                synchronized (next) {
                    source = sources.get(next[0]++);
                }
                SchemaBootstrapper.bootstrap(source, null, migrations.toString());
                return null;
            });
            assertEquals(1, count(first, "t"));
        }
    }

    /**
     * Runs a task on several threads released at the same moment and rethrows the first failure.
     *
     * @param threads the number of threads
     * @param task the task each thread runs
     * @throws Exception the first failure
     */
    private static void runConcurrently(int threads, Callable<Void> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
