// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration.it;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.migration.DatabaseMigrationException;
import io.github.littleshystar02.lss02.databases.migration.DatabaseMigrator;
import io.github.littleshystar02.lss02.databases.migration.data.MigrationProgressListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.JdbcDatabaseContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema and data migration between SQLite and a real database server. A subclass supplies a running
 * Testcontainers container; these tests only run where Docker is available (the {@code integrationTest} task).
 */
abstract class AbstractMigrationIntegrationTest {

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
     * Opens a pool on the container database.
     *
     * @return the pool
     */
    HikariDataSource server() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(container().getJdbcUrl());
        config.setUsername(container().getUsername());
        config.setPassword(container().getPassword());
        config.setMaximumPoolSize(3);
        return new HikariDataSource(config);
    }

    /**
     * Opens a pool on a SQLite file.
     *
     * @param file the database file
     * @return the pool
     */
    static HikariDataSource sqlite(Path file) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + file.toString().replace("\\", "/"));
        config.setMaximumPoolSize(1);
        return new HikariDataSource(config);
    }

    /**
     * Drops every table of the server database so each test starts from nothing.
     *
     * @throws SQLException if the cleanup fails
     */
    @BeforeEach
    void cleanServer() throws SQLException {
        try (HikariDataSource dataSource = server(); Connection connection = dataSource.getConnection()) {
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
     * Runs statements.
     *
     * @param dataSource where to run
     * @param statements the SQL
     * @throws SQLException if one fails
     */
    static void run(HikariDataSource dataSource, String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /**
     * Quotes an identifier with the quote string of a connection.
     *
     * @param connection the connection
     * @param name the identifier
     * @return the quoted identifier
     * @throws SQLException if the metadata cannot be read
     */
    static String quoted(Connection connection, String name) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        return quote + name.replace(quote, quote + quote) + quote;
    }

    /**
     * Counts the rows of a table.
     *
     * @param dataSource the database
     * @param table the table name, unquoted
     * @return the row count
     * @throws SQLException if the query fails
     */
    static int count(HikariDataSource dataSource, String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + quoted(connection, table))) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** A SQLite table with text, integers, bytes and NULLs goes to the server and comes back unchanged. */
    @Test
    void sqliteToServerAndBack(@TempDir Path dir) throws Exception {
        try (HikariDataSource origin = sqlite(dir.resolve("origin.db"));
             HikariDataSource server = server();
             HikariDataSource back = sqlite(dir.resolve("back.db"))) {
            run(origin, "CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT, qty INTEGER, data BLOB)");
            try (Connection connection = origin.getConnection();
                 PreparedStatement insert = connection.prepareStatement("INSERT INTO items VALUES (?, ?, ?, ?)")) {
                insert.setInt(1, 1);
                insert.setString(2, "Ada");
                insert.setLong(3, 4_000_000_000L);
                insert.setBytes(4, new byte[] {0, 1, (byte) 254});
                insert.executeUpdate();
                insert.setInt(1, 2);
                insert.setObject(2, null);
                insert.setObject(3, null);
                insert.setObject(4, null);
                insert.executeUpdate();
            }

            DatabaseMigrator.migrateSchema(origin, server, type(), List.of("items"));
            DatabaseMigrator.migrateData(origin, server, List.of("items"), 1);
            assertEquals(2, count(server, "items"));

            DatabaseMigrator.migrateSchema(server, back, DatabaseType.SQLITE, List.of("items"));
            DatabaseMigrator.migrateData(server, back, List.of("items"), 1);

            try (Connection connection = back.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT id, name, qty, data FROM items ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals("Ada", rows.getString("name"));
                assertEquals(4_000_000_000L, rows.getLong("qty"));
                assertArrayEquals(new byte[] {0, 1, (byte) 254}, rows.getBytes("data"));
                assertTrue(rows.next());
                assertNull(rows.getString("name"));
                assertNull(rows.getBytes("data"));
            }
        }
    }

    /** Reserved words and mixed-case names are quoted on both sides, so they migrate and nothing is injected. */
    @Test
    void reservedWordsAndMixedCaseNamesMigrate(@TempDir Path dir) throws Exception {
        try (HikariDataSource origin = sqlite(dir.resolve("origin.db")); HikariDataSource server = server()) {
            run(origin,
                    "CREATE TABLE \"order\" (\"group\" INTEGER PRIMARY KEY, \"select\" TEXT)",
                    "INSERT INTO \"order\" VALUES (1, 'a')",
                    "INSERT INTO \"order\" VALUES (2, 'b')",
                    "CREATE TABLE \"Users\" (\"Id\" INTEGER PRIMARY KEY)",
                    "INSERT INTO \"Users\" VALUES (7)");

            List<String> tables = List.of("order", "Users");
            DatabaseMigrator.migrateSchema(origin, server, type(), tables);
            DatabaseMigrator.migrateData(origin, server, tables, 10);

            assertEquals(2, count(server, "order"));
            assertEquals(1, count(server, "Users"));
        }
    }

    /** A hostile table name is rejected by the metadata check before it reaches any SQL. */
    @Test
    void hostileTableNamesAreRejected(@TempDir Path dir) throws Exception {
        try (HikariDataSource origin = sqlite(dir.resolve("origin.db")); HikariDataSource server = server()) {
            run(origin, "CREATE TABLE victim (id INTEGER)");
            assertThrows(DatabaseMigrationException.class,
                    () -> DatabaseMigrator.migrateData(origin, server, List.of("victim; DROP TABLE victim"), 10));
            assertEquals(0, count(origin, "victim"));
        }
    }

    /** With skipNonEmptyTargets a rerun after a finished copy duplicates nothing. */
    @Test
    void aRerunSkipsTablesThatAlreadyHoldRows(@TempDir Path dir) throws Exception {
        try (HikariDataSource origin = sqlite(dir.resolve("origin.db")); HikariDataSource server = server()) {
            run(origin, "CREATE TABLE t (id INTEGER)", "INSERT INTO t VALUES (1)", "INSERT INTO t VALUES (2)");
            DatabaseMigrator.migrateSchema(origin, server, type(), List.of("t"));

            DatabaseMigrator.migrateData(origin, server, List.of("t"), 10, MigrationProgressListener.NO_OP, true);
            var second = DatabaseMigrator.migrateData(origin, server, List.of("t"), 10, MigrationProgressListener.NO_OP, true);

            assertEquals(2, count(server, "t"));
            assertEquals(0L, second.getRowsCopiedPerTable().get("t"));
        }
    }

    /** A batch that violates a key rolls the whole table back on the server. */
    @Test
    void aFailingCopyLeavesTheTargetTableEmpty(@TempDir Path dir) throws Exception {
        try (HikariDataSource origin = sqlite(dir.resolve("origin.db")); HikariDataSource server = server()) {
            run(origin, "CREATE TABLE dups (id INTEGER)",
                    "INSERT INTO dups VALUES (1)", "INSERT INTO dups VALUES (2)", "INSERT INTO dups VALUES (2)");
            run(server, "CREATE TABLE dups (id INT PRIMARY KEY)");

            DatabaseMigrationException error = assertThrows(DatabaseMigrationException.class,
                    () -> DatabaseMigrator.migrateData(origin, server, List.of("dups"), 2));
            assertTrue(error.getMessage().contains("dups"), error.getMessage());
            assertEquals(0, count(server, "dups"));
        }
    }
}
