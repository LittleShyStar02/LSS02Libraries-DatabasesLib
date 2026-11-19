// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.migration.data.MigrationProgressListener;
import io.github.littleshystar02.lss02.databases.migration.data.MigrationResult;
import io.github.littleshystar02.lss02.databases.migration.schema.SchemaIntrospector;
import io.github.littleshystar02.lss02.databases.migration.schema.TableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migrations between engines that run without Docker: H2 in MySQL and PostgreSQL compatibility mode
 * (an indicative stand-in for the real servers, which the Testcontainers tests cover) and SQLite.
 */
class CrossEngineMigrationTest {

    /**
     * Opens a pool on a fresh in-memory H2 database.
     *
     * @param mode the compatibility mode, for example {@code MySQL}
     * @return the pool
     */
    private static HikariDataSource h2(String mode) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }

    /**
     * Opens a pool on a SQLite file.
     *
     * @param file the database file
     * @return the pool
     */
    private static HikariDataSource sqlite(Path file) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + file.toString().replace("\\", "/"));
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }

    /**
     * Runs statements.
     *
     * @param dataSource where to run
     * @param statements the SQL
     * @throws SQLException if one fails
     */
    private static void run(HikariDataSource dataSource, String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** Collects the progress events. */
    private static final class Recorder implements MigrationProgressListener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void onTableStarted(String tableName) {
            events.add("start:" + tableName);
        }

        @Override
        public void onBatchCopied(String tableName, int rowCount) {
            events.add("batch:" + tableName + ":" + rowCount);
        }

        @Override
        public void onTableCompleted(String tableName, long totalRows) {
            events.add("done:" + tableName + ":" + totalRows);
        }

        @Override
        public void onError(String tableName, Exception error) {
            events.add("error:" + tableName);
        }
    }

    /** A table with every supported type, NULLs, large objects and a composite key survives MySQL-mode to PostgreSQL-mode. */
    @Test
    void copiesEveryTypeBetweenTwoH2Modes() throws Exception {
        try (HikariDataSource source = h2("MySQL"); HikariDataSource target = h2("PostgreSQL")) {
            run(source, "CREATE TABLE items (a INT NOT NULL, b VARCHAR(20) NOT NULL, label VARCHAR(50), price DECIMAL(10,2),"
                    + " active BOOLEAN, big BIGINT, ratio DOUBLE, born DATE, seen TIMESTAMP, note CLOB, data BLOB, PRIMARY KEY (a, b))");
            try (Connection connection = source.getConnection();
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO items VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setInt(1, 1);
                insert.setString(2, "x");
                insert.setString(3, "first");
                insert.setBigDecimal(4, new BigDecimal("12.50"));
                insert.setBoolean(5, true);
                insert.setLong(6, 9_000_000_000L);
                insert.setDouble(7, 0.25);
                insert.setDate(8, java.sql.Date.valueOf("2026-03-04"));
                insert.setTimestamp(9, java.sql.Timestamp.valueOf("2026-03-04 05:06:07"));
                insert.setString(10, "long text");
                insert.setBytes(11, new byte[] {0, 1, 2, (byte) 255});
                insert.executeUpdate();
                insert.setInt(1, 2);
                insert.setString(2, "y");
                for (int i = 3; i <= 11; i++) {
                    insert.setObject(i, null);
                }
                insert.executeUpdate();
            }

            List<String> tables = List.of("items");
            DatabaseMigrator.migrateSchema(source, target, DatabaseType.POSTGRESQL, tables);
            MigrationResult result = DatabaseMigrator.migrateData(source, target, tables, 1);
            assertEquals(2, result.getTotalRowsCopied());

            try (Connection connection = target.getConnection()) {
                TableSchema schema = SchemaIntrospector.introspect(connection, "items");
                assertEquals(List.of("A", "B"), schema.getPrimaryKeyColumns().stream().map(String::toUpperCase).toList());
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT * FROM items ORDER BY a")) {
                    assertTrue(rows.next());
                    assertEquals("first", rows.getString("label"));
                    assertEquals(0, new BigDecimal("12.50").compareTo(rows.getBigDecimal("price")));
                    assertTrue(rows.getBoolean("active"));
                    assertEquals(9_000_000_000L, rows.getLong("big"));
                    assertEquals(0.25, rows.getDouble("ratio"));
                    assertEquals("2026-03-04", rows.getDate("born").toString());
                    assertEquals("2026-03-04 05:06:07.0", rows.getTimestamp("seen").toString());
                    assertEquals("long text", rows.getString("note"));
                    assertArrayEquals(new byte[] {0, 1, 2, (byte) 255}, rows.getBytes("data"));
                    assertTrue(rows.next());
                    assertNull(rows.getString("label"));
                    assertNull(rows.getBigDecimal("price"));
                    assertNull(rows.getBytes("data"));
                    assertFalse(rows.next());
                }
            }
        }
    }

    /** H2 (MySQL mode) to SQLite: numbers, text, booleans and bytes arrive intact. */
    @Test
    void copiesFromH2ToSqlite(@TempDir Path dir) throws Exception {
        try (HikariDataSource source = h2("MySQL"); HikariDataSource target = sqlite(dir.resolve("t.db"))) {
            run(source, "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(30), price DECIMAL(8,2), flag BOOLEAN, data VARBINARY(16))",
                    "INSERT INTO t VALUES (1, 'Ada', 3.25, TRUE, X'0aff')",
                    "INSERT INTO t VALUES (2, NULL, NULL, NULL, NULL)");

            DatabaseMigrator.migrateSchema(source, target, DatabaseType.SQLITE, List.of("t"));
            DatabaseMigrator.migrateData(source, target, List.of("t"), 10);

            try (Connection connection = target.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT id, name, price, flag, data FROM t ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals("Ada", rows.getString("name"));
                assertEquals(3.25, rows.getDouble("price"));
                assertEquals(1, rows.getInt("flag"));
                assertArrayEquals(new byte[] {0x0a, (byte) 0xff}, rows.getBytes("data"));
                assertTrue(rows.next());
                assertNull(rows.getString("name"));
                assertNull(rows.getBytes("data"));
            }
        }
    }

    /** SQLite to H2 (PostgreSQL mode), including a BLOB column and a primary key. */
    @Test
    void copiesFromSqliteToH2(@TempDir Path dir) throws Exception {
        try (HikariDataSource source = sqlite(dir.resolve("s.db")); HikariDataSource target = h2("PostgreSQL")) {
            run(source, "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, score REAL, data BLOB)");
            try (Connection connection = source.getConnection();
                 PreparedStatement insert = connection.prepareStatement("INSERT INTO t VALUES (?, ?, ?, ?)")) {
                insert.setInt(1, 1);
                insert.setString(2, "Grace");
                insert.setDouble(3, 1.5);
                insert.setBytes(4, new byte[] {9, 8, 7});
                insert.executeUpdate();
                insert.setInt(1, 2);
                insert.setObject(2, null);
                insert.setObject(3, null);
                insert.setObject(4, null);
                insert.executeUpdate();
            }

            DatabaseMigrator.migrateSchema(source, target, DatabaseType.POSTGRESQL, List.of("t"));
            DatabaseMigrator.migrateData(source, target, List.of("t"), 1);

            try (Connection connection = target.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT \"id\", \"name\", \"score\", \"data\" FROM \"t\" ORDER BY \"id\"")) {
                assertTrue(rows.next());
                assertEquals("Grace", rows.getString("name"));
                assertEquals(1.5, rows.getDouble("score"));
                assertArrayEquals(new byte[] {9, 8, 7}, rows.getBytes("data"));
                assertTrue(rows.next());
                assertNull(rows.getBytes("data"));
            }
        }
    }

    /** The listener sees start, one event per batch, completion, and an error event on failure. */
    @Test
    void reportsProgressAndErrors() throws Exception {
        try (HikariDataSource source = h2("MySQL"); HikariDataSource target = h2("MySQL")) {
            run(source, "CREATE TABLE n (id INT)", "INSERT INTO n VALUES (1)", "INSERT INTO n VALUES (2)", "INSERT INTO n VALUES (3)");
            DatabaseMigrator.migrateSchema(source, target, DatabaseType.MYSQL, List.of("n"));

            Recorder recorder = new Recorder();
            DatabaseMigrator.migrateData(source, target, List.of("n"), 2, recorder);
            assertEquals(List.of("start:N", "batch:N:2", "batch:N:1", "done:N:3"), recorder.events);

            Recorder failing = new Recorder();
            run(source, "CREATE TABLE only_on_source (id INT)", "INSERT INTO only_on_source VALUES (1)");
            DatabaseMigrationException error = assertThrows(DatabaseMigrationException.class,
                    () -> DatabaseMigrator.migrateData(source, target, List.of("n", "only_on_source"), 2, failing, false));
            assertTrue(error.getMessage().contains("ONLY_ON_SOURCE"), error.getMessage());
            assertTrue(error.getMessage().contains("tables already completed"), error.getMessage());
            assertTrue(failing.events.contains("error:ONLY_ON_SOURCE"), failing.events.toString());
        }
    }

    /** A column type that has no mapping fails the schema step with the table and column named. */
    @Test
    void unsupportedTypesAreReportedWithTheirColumn() throws Exception {
        try (HikariDataSource source = h2("MySQL"); HikariDataSource target = h2("PostgreSQL")) {
            run(source, "CREATE TABLE arrays (id INT, tags INTEGER ARRAY)");
            UnsupportedTypeMappingException error = assertThrows(UnsupportedTypeMappingException.class,
                    () -> DatabaseMigrator.migrateSchema(source, target, DatabaseType.POSTGRESQL, List.of("arrays")));
            assertEquals("TAGS", error.getColumnName().toUpperCase());
        }
    }

    /** Names are matched case-insensitively when unambiguous, and unknown or empty names are rejected. */
    @Test
    void resolvesTableNames() throws Exception {
        try (HikariDataSource source = h2("MySQL")) {
            run(source, "CREATE TABLE players (id INT)", "CREATE VIEW player_ids AS SELECT id FROM players");
            try (Connection connection = source.getConnection()) {
                assertEquals("PLAYERS", SchemaIntrospector.introspect(connection, "players").getName());
                assertEquals("PLAYER_IDS", SchemaIntrospector.introspect(connection, "PLAYER_IDS").getName());
                assertThrows(DatabaseMigrationException.class, () -> SchemaIntrospector.introspect(connection, "nope"));
                assertThrows(DatabaseMigrationException.class, () -> SchemaIntrospector.introspect(connection, " "));
                assertThrows(DatabaseMigrationException.class, () -> SchemaIntrospector.introspect(connection, (String) null));
                assertEquals(2, SchemaIntrospector.introspect(connection, List.of("players", "player_ids")).size());
            }
        }
    }

    /** Two tables whose names differ only in case cannot be told apart by a case-insensitive request. */
    @Test
    void ambiguousCaseInsensitiveNamesAreRejected() throws Exception {
        try (HikariDataSource source = h2("MySQL")) {
            run(source, "CREATE TABLE \"Users\" (id INTEGER)", "CREATE TABLE \"USERS\" (id INTEGER)");
            try (Connection connection = source.getConnection()) {
                assertEquals("Users", SchemaIntrospector.introspect(connection, "Users").getName());
                assertThrows(DatabaseMigrationException.class, () -> SchemaIntrospector.introspect(connection, "users"));
            }
        }
    }

    /** A batch size below one is refused before anything is read. */
    @Test
    void invalidBatchSizeIsRefused() throws Exception {
        try (HikariDataSource source = h2("MySQL"); HikariDataSource target = h2("MySQL")) {
            run(source, "CREATE TABLE n (id INT)");
            assertThrows(DatabaseMigrationException.class,
                    () -> DatabaseMigrator.migrateData(source, target, List.of("n"), 0));
        }
    }

    /** DB-MIG-004: tables of the same name in two schemas are not merged; location is kept. */
    @Test
    void sameNamedTablesInOtherSchemasAreNotMerged() throws Exception {
        try (HikariDataSource db = h2("PostgreSQL"); Connection connection = db.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA S1");
            statement.execute("CREATE SCHEMA S2");
            statement.execute("CREATE TABLE S1.ITEMS (A INT PRIMARY KEY)");
            statement.execute("CREATE TABLE S2.ITEMS (B INT, C INT)");

            statement.execute("SET SCHEMA S2");
            TableSchema second = SchemaIntrospector.introspect(connection, "items");
            assertEquals("S2", second.getSchema());
            assertEquals("ITEMS", second.getName());
            assertEquals(2, second.getColumns().size());
            assertEquals("B", second.getColumns().get(0).getName());

            statement.execute("SET SCHEMA S1");
            TableSchema first = SchemaIntrospector.introspect(connection, "ITEMS");
            assertEquals("S1", first.getSchema());
            assertEquals(1, first.getColumns().size());
            assertEquals(List.of("A"), first.getPrimaryKeyColumns());
        }
    }
}
