package io.github.littleshystar02.lss02.databases.bootstrap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SchemaHistoryRepository {

    private static final String TABLE_NAME = "lss02_schema_history";

    private final Connection connection;

    public SchemaHistoryRepository(Connection connection) {
        this.connection = connection;
    }

    public void ensureTableExists() {
        String ddl = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
                "version INTEGER PRIMARY KEY, " +
                "description VARCHAR(255) NOT NULL, " +
                "script_name VARCHAR(255) NOT NULL, " +
                "checksum VARCHAR(64) NOT NULL, " +
                "applied_at VARCHAR(64) NOT NULL)";
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        } catch (SQLException e) {
            throw new SchemaMigrationException("Could not create the schema history table", e);
        }
    }

    public Map<Integer, String> findAppliedChecksums() {
        Map<Integer, String> applied = new LinkedHashMap<>();
        String sql = "SELECT version, checksum FROM " + TABLE_NAME;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                applied.put(resultSet.getInt("version"), resultSet.getString("checksum"));
            }
        } catch (SQLException e) {
            throw new SchemaMigrationException("Could not read the schema history table", e);
        }
        return applied;
    }

    public void recordApplied(MigrationScript script) {
        String sql = "INSERT INTO " + TABLE_NAME
                + " (version, description, script_name, checksum, applied_at) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, script.getVersion());
            statement.setString(2, script.getDescription());
            statement.setString(3, script.getScriptName());
            statement.setString(4, script.getChecksum());
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new SchemaMigrationException("Could not record migration '" + script.getScriptName() + "' as applied", e);
        }
    }
}
