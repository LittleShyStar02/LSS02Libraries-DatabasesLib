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

    /** DB-MIG-001: a failing onTableCompleted does not claim a rollback; the table stays committed. */
    @Test
    void completionListenerFailureKeepsTheCommittedTable(@TempDir Path tempDir) throws SQLException {
        SQLiteDataSource source = dataSource(tempDir.resolve("s.db"));
        SQLiteDataSource target = dataSource(tempDir.resolve("t.db"));
        run(source, "CREATE TABLE items (id INTEGER)", "INSERT INTO items VALUES (1)", "INSERT INTO items VALUES (2)");
        run(target, "CREATE TABLE items (id INTEGER)");
        List<String> errors = new java.util.ArrayList<>();
        MigrationProgressListener failing = new MigrationProgressListener() {
            @Override
            public void onTableCompleted(String tableName, long totalRows) {
                throw new IllegalStateException("listener broke");
            }

            @Override
            public void onError(String tableName, Exception error) {
                errors.add(tableName);
            }
        };

        DatabaseMigrationException error = assertThrows(DatabaseMigrationException.class,
                () -> DatabaseMigrator.migrateData(source, target, List.of("items"), 10, failing));

        assertEquals(2, count(target, "items"), "the committed table must not be rolled back");
        assertTrue(error.getMessage().contains("committed"));
        assertTrue(error.getMessage().contains("tables already completed: [items]"));
        assertTrue(errors.isEmpty(), "onError is for copy failures only");
    }

    /** DB-MIG-002: a failing onTableStarted restores auto-commit and rolls back; onError failures are suppressed. */
    @Test
    void startListenerFailureRestoresAutoCommit(@TempDir Path tempDir) throws SQLException {
        SQLiteDataSource source = dataSource(tempDir.resolve("s.db"));
        SQLiteDataSource target = dataSource(tempDir.resolve("t.db"));
        run(source, "CREATE TABLE items (id INTEGER)", "INSERT INTO items VALUES (1)");
        run(target, "CREATE TABLE items (id INTEGER)");
        MigrationProgressListener failing = new MigrationProgressListener() {
            @Override
            public void onTableStarted(String tableName) {
                throw new IllegalStateException("start broke");
            }

            @Override
            public void onError(String tableName, Exception error) {
                throw new IllegalStateException("error callback broke");
            }
        };
        try (Connection sourceConnection = source.getConnection(); Connection targetConnection = target.getConnection()) {
            var table = io.github.littleshystar02.lss02.databases.migration.schema.SchemaIntrospector.introspect(sourceConnection, "items");

            DatabaseMigrationException error = assertThrows(DatabaseMigrationException.class,
                    () -> DataCopier.copy(sourceConnection, targetConnection, table, 10, failing));

            assertTrue(targetConnection.getAutoCommit(), "auto-commit must be restored");
            assertEquals(1, error.getCause().getSuppressed().length);
        }
        assertEquals(0, count(target, "items"));
    }

    /** DB-MIG-003: a target whose transaction belongs to the caller is rejected and left untouched. */
    @Test
    void callerManagedTransactionIsRejected(@TempDir Path tempDir) throws SQLException {
        SQLiteDataSource source = dataSource(tempDir.resolve("s.db"));
        SQLiteDataSource target = dataSource(tempDir.resolve("t.db"));
        run(source, "CREATE TABLE items (id INTEGER)", "INSERT INTO items VALUES (1)");
        run(target, "CREATE TABLE items (id INTEGER)", "CREATE TABLE pending (id INTEGER)");
        try (Connection sourceConnection = source.getConnection(); Connection targetConnection = target.getConnection()) {
            var table = io.github.littleshystar02.lss02.databases.migration.schema.SchemaIntrospector.introspect(sourceConnection, "items");
            targetConnection.setAutoCommit(false);
            try (Statement statement = targetConnection.createStatement()) {
                statement.execute("INSERT INTO pending VALUES (42)");
            }

            DatabaseMigrationException error = assertThrows(DatabaseMigrationException.class,
                    () -> DataCopier.copy(sourceConnection, targetConnection, table, 10, MigrationProgressListener.NO_OP));

            assertTrue(error.getMessage().contains("managed by the caller"));
            org.junit.jupiter.api.Assertions.assertFalse(targetConnection.getAutoCommit(), "the caller's mode must be left as it was");
            targetConnection.commit();
        }
        assertEquals(1, count(target, "pending"), "the caller's pending write must survive");
        assertEquals(0, count(target, "items"));
    }

    /** DB-MIG-005: a failed compensation reports the tables still on the target together with the cause. */
    @Test
    void schemaCompensationFailureIsReported(@TempDir Path tempDir) throws SQLException {
        SQLiteDataSource source = dataSource(tempDir.resolve("s.db"));
        SQLiteDataSource target = dataSource(tempDir.resolve("t.db"));
        run(source, "CREATE TABLE a (id INTEGER)", "CREATE TABLE b (id INTEGER)");
        // Table b already exists on the target, so its CREATE fails after a was created.
        run(target, "CREATE TABLE b (id INTEGER)");
        DatabaseMigrationException ok = assertThrows(DatabaseMigrationException.class,
                () -> DatabaseMigrator.migrateSchema(source, target, DatabaseType.SQLITE, List.of("a", "b")));
        assertTrue(ok.getMessage().contains("dropped again"));
        assertEquals(0, tableCount(target, "a"));

        // Every DROP fails: the compensation cannot clean up and says which tables are left.
        SQLiteDataSource source2 = dataSource(tempDir.resolve("s2.db"));
        SQLiteDataSource target2 = dataSource(tempDir.resolve("t2.db"));
        run(source2, "CREATE TABLE a (id INTEGER)", "CREATE TABLE b (id INTEGER)");
        run(target2, "CREATE TABLE b (id INTEGER)");
        javax.sql.DataSource noDrop = dropFailing(target2);

        DatabaseMigrationException failed = assertThrows(DatabaseMigrationException.class,
                () -> DatabaseMigrator.migrateSchema(source2, noDrop, DatabaseType.SQLITE, List.of("a", "b")));

        assertTrue(failed.getMessage().contains("compensation FAILED"), failed.getMessage());
        assertTrue(failed.getMessage().contains("[a]"), failed.getMessage());
        assertEquals(1, tableCount(target2, "a"));
        assertEquals(1, failed.getCause().getSuppressed().length);
    }

    /**
     * Wraps a data source so that every {@code DROP} statement fails.
     *
     * @param delegate the real data source
     * @return the wrapper
     */
    private static javax.sql.DataSource dropFailing(SQLiteDataSource delegate) {
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(DataCopierTest.class.getClassLoader(),
                new Class<?>[] {javax.sql.DataSource.class}, (proxy, method, args) -> {
                    Object result = invoke(method, delegate, args);
                    if (!method.getName().equals("getConnection")) {
                        return result;
                    }
                    Connection connection = (Connection) result;
                    return java.lang.reflect.Proxy.newProxyInstance(DataCopierTest.class.getClassLoader(),
                            new Class<?>[] {Connection.class}, (p, m, a) -> {
                                Object inner = invoke(m, connection, a);
                                if (!m.getName().equals("createStatement")) {
                                    return inner;
                                }
                                Statement statement = (Statement) inner;
                                return java.lang.reflect.Proxy.newProxyInstance(DataCopierTest.class.getClassLoader(),
                                        new Class<?>[] {Statement.class}, (p2, m2, a2) -> {
                                            if (m2.getName().equals("execute") && a2 != null && a2[0] instanceof String sql
                                                    && sql.trim().toUpperCase(java.util.Locale.ROOT).startsWith("DROP")) {
                                                throw new SQLException("drop refused");
                                            }
                                            return invoke(m2, statement, a2);
                                        });
                            });
                });
    }

    /**
     * Invokes a method reflectively, unwrapping the target exception.
     *
     * @param method the method
     * @param target the object
     * @param args the arguments
     * @return the result
     * @throws Throwable what the method throws
     */
    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * Counts the tables of a given name on a SQLite database.
     *
     * @param dataSource the database
     * @param name the table name
     * @return 0 or 1
     * @throws SQLException if the query fails
     */
    private int tableCount(SQLiteDataSource dataSource, String name) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = '" + name + "'")) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
