package io.github.littleshystar02.lss02.databases.bootstrap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes the schema history table that tracks which migration scripts were applied.
 *
 * <p>The table is named {@code lss02_schema_history} and holds one row per applied script (version,
 * description, script name, checksum and application timestamp). The repository works on a connection
 * owned by the caller, which is also responsible for closing it. Instances are not thread-safe.
 */
public final class SchemaHistoryRepository {

    /** Name of the history table. */
    private static final String TABLE_NAME = "lss02_schema_history";

    /** Connection used for every statement; owned and closed by the caller. */
    private final Connection connection;

    /**
     * Creates a repository on an open connection.
     *
     * @param connection the connection to use, it is not closed by this class
     */
    public SchemaHistoryRepository(Connection connection) {
        this.connection = connection;
    }

    /**
     * Creates the history table if it does not exist yet.
     *
     * @throws SchemaMigrationException if the statement fails
     */
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

    /**
     * Loads the checksum of every script recorded as applied.
     *
     * @return a mutable map from script version to the stored checksum
     * @throws SchemaMigrationException if the table cannot be read
     */
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

    /**
     * Records a script as applied, stamping the current instant as application time.
     *
     * @param script the script that was just executed
     * @throws SchemaMigrationException if the row cannot be inserted, for example when the version is
     *                                  already present
     */
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
