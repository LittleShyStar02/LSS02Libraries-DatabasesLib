// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration.data;

import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.migration.DatabaseMigrationException;
import io.github.littleshystar02.lss02.databases.migration.DatabaseMigrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link DataCopier} and {@link SqlIdentifiers} on temporary SQLite databases. */
class DataCopierTest {

    /** A table and column name made of a reserved word, a space and a quote character. */
    private static final String HOSTILE_TABLE = "order \"x\"; DROP TABLE victim;--";

    /**
     * Creates a data source for a SQLite file.
     *
     * @param file the database file
     * @return the data source
     */
    private SQLiteDataSource dataSource(Path file) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file);
        return dataSource;
    }

    /**
     * Runs statements on a data source.
     *
     * @param dataSource where to run
     * @param statements the SQL
     * @throws SQLException if one fails
     */
    private void run(SQLiteDataSource dataSource, String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /**
     * Counts the rows of a table.
     *
     * @param dataSource the database
     * @param quotedTable the quoted table name
     * @return the row count
     * @throws SQLException if the query fails
     */
    private int count(SQLiteDataSource dataSource, String quotedTable) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + quotedTable)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** Identifiers are quoted and embedded quote characters are doubled. */
    @Test
    void quotesAndEscapesIdentifiers(@TempDir Path tempDir) throws SQLException {
        try (Connection connection = dataSource(tempDir.resolve("q.db")).getConnection()) {
            assertEquals("\"user\"", SqlIdentifiers.quote(connection, "user"));
            assertEquals("\"a\"\"b\"", SqlIdentifiers.quote(connection, "a\"b"));
            assertThrows(DatabaseMigrationException.class, () -> SqlIdentifiers.quote(connection, ""));
        }
    }

    /** Tables and columns with reserved words, spaces and quotes migrate, and nothing is injected. */
    @Test
    void copiesTablesWithHostileNames(@TempDir Path tempDir) throws SQLException {
        SQLiteDataSource source = dataSource(tempDir.resolve("s.db"));
        SQLiteDataSource target = dataSource(tempDir.resolve("t.db"));
        String quoted = "\"" + HOSTILE_TABLE.replace("\"", "\"\"") + "\"";
        run(source,
                "CREATE TABLE victim (id INTEGER)",
                "INSERT INTO victim VALUES (1)",
                "CREATE TABLE " + quoted + " (\"group\" INTEGER NOT NULL, \"my col\" TEXT)",
                "INSERT INTO " + quoted + " VALUES (1, 'a')",
                "INSERT INTO " + quoted + " VALUES (2, 'b')");

        DatabaseMigrator.migrateSchema(source, target, DatabaseType.SQLITE, List.of(HOSTILE_TABLE));
        DatabaseMigrator.migrateData(source, target, List.of(HOSTILE_TABLE), 1);

        assertEquals(2, count(target, quoted));
        assertEquals(1, count(source, "victim"));
    }

    /** A table name that is not a real table is rejected before any SQL is built from it. */
    @Test
    void rejectsUnknownTableNames(@TempDir Path tempDir) throws SQLException {
        SQLiteDataSource source = dataSource(tempDir.resolve("s.db"));
        SQLiteDataSource target = dataSource(tempDir.resolve("t.db"));
        run(source, "CREATE TABLE users (id INTEGER)");

        assertThrows(DatabaseMigrationException.class,
                () -> DatabaseMigrator.migrateData(source, target, List.of("users; DROP TABLE users"), 10));
        assertThrows(DatabaseMigrationException.class,
                () -> DatabaseMigrator.migrateData(source, target, List.of("u%"), 10));
        assertEquals(0, count(source, "users"));
    }

    /** A failing batch rolls the whole table back, so nothing is left half copied. */
    @Test
    void failureRollsTheTableBack(@TempDir Path tempDir) throws SQLException {
        SQLiteDataSource source = dataSource(tempDir.resolve("s.db"));
        SQLiteDataSource target = dataSource(tempDir.resolve("t.db"));
        run(source, "CREATE TABLE items (id INTEGER)",
                "INSERT INTO items VALUES (1)", "INSERT INTO items VALUES (2)",
                "INSERT INTO items VALUES (3)", "INSERT INTO items VALUES (3)");
        run(target, "CREATE TABLE items (id INTEGER PRIMARY KEY)");

        DatabaseMigrationException error = assertThrows(DatabaseMigrationException.class,
                () -> DatabaseMigrator.migrateData(source, target, List.of("items"), 2));
        assertTrue(error.getMessage().contains("items"));
        assertEquals(0, count(target, "items"));
    }

    /** With skipNonEmptyTargets a second run does not duplicate rows. */
    @Test
    void rerunSkipsTablesThatAlreadyHoldRows(@TempDir Path tempDir) throws SQLException {
        SQLiteDataSource source = dataSource(tempDir.resolve("s.db"));
        SQLiteDataSource target = dataSource(tempDir.resolve("t.db"));
        run(source, "CREATE TABLE items (id INTEGER)", "INSERT INTO items VALUES (1)", "INSERT INTO items VALUES (2)");
        run(target, "CREATE TABLE items (id INTEGER)");

        DatabaseMigrator.migrateData(source, target, List.of("items"), 10, MigrationProgressListener.NO_OP, true);
        var second = DatabaseMigrator.migrateData(source, target, List.of("items"), 10, MigrationProgressListener.NO_OP, true);

        assertEquals(2, count(target, "items"));
        assertEquals(0L, second.getRowsCopiedPerTable().get("items"));
    }
}