package io.github.littleshystar02.lss02.databases.migration;

import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
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

class DatabaseMigratorTest {

    private SQLiteDataSource dataSourceFor(Path dbFile) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        return dataSource;
    }

    @Test
    void migratesSchemaAndDataBetweenTwoSqliteDatabases(@TempDir Path tempDir) throws SQLException {
        SQLiteDataSource source = dataSourceFor(tempDir.resolve("source.db"));
        SQLiteDataSource target = dataSourceFor(tempDir.resolve("target.db"));

        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id INTEGER NOT NULL, name TEXT NOT NULL)");
            statement.execute("INSERT INTO users (id, name) VALUES (1, 'Ada')");
            statement.execute("INSERT INTO users (id, name) VALUES (2, 'Grace')");
        }

        DatabaseMigrator.migrateSchema(source, target, DatabaseType.SQLITE, List.of("users"));
        DatabaseMigrator.migrateData(source, target, List.of("users"), 500);

        try (Connection connection = target.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT id, name FROM users ORDER BY id")) {
            resultSet.next();
            assertEquals(1, resultSet.getInt("id"));
            assertEquals("Ada", resultSet.getString("name"));
            resultSet.next();
            assertEquals(2, resultSet.getInt("id"));
            assertEquals("Grace", resultSet.getString("name"));
        }
    }

    @Test
    void batchesSmallerThanTheTotalRowCountStillCopyEverything(@TempDir Path tempDir) throws SQLException {
        SQLiteDataSource source = dataSourceFor(tempDir.resolve("source.db"));
        SQLiteDataSource target = dataSourceFor(tempDir.resolve("target.db"));

        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE numbers (id INTEGER NOT NULL)");
            for (int i = 1; i <= 10; i++) {
                statement.execute("INSERT INTO numbers (id) VALUES (" + i + ")");
            }
        }

        DatabaseMigrator.migrateSchema(source, target, DatabaseType.SQLITE, List.of("numbers"));
        var result = DatabaseMigrator.migrateData(source, target, List.of("numbers"), 3);

        assertEquals(10, result.getTotalRowsCopied());
    }
}
