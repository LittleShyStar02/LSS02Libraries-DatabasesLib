package io.github.littleshystar02.lss02.databases.bootstrap;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

public final class SchemaBootstrapper {

    private static final int BASELINE_VERSION = 0;

    private SchemaBootstrapper() {
    }

    public static void bootstrap(DataSource dataSource, String setupScriptLocation, String versionedScriptsLocation) {
        try (Connection connection = dataSource.getConnection()) {
            SchemaHistoryRepository history = new SchemaHistoryRepository(connection);
            history.ensureTableExists();

            Map<Integer, String> applied = history.findAppliedChecksums();

            if (!applied.containsKey(BASELINE_VERSION) && setupScriptLocation != null) {
                String content = ScriptLoader.load(setupScriptLocation);
                runScript(connection, content);
                MigrationScript baseline = new MigrationScript(
                        BASELINE_VERSION, "baseline", "setup.sql", content, ChecksumUtil.sha256(content));
                history.recordApplied(baseline);
                applied.put(BASELINE_VERSION, baseline.getChecksum());
            }

            for (MigrationScript script : MigrationScanner.scan(versionedScriptsLocation)) {
                String existingChecksum = applied.get(script.getVersion());
                if (existingChecksum != null) {
                    if (!existingChecksum.equals(script.getChecksum())) {
                        throw new SchemaMigrationException("Migration '" + script.getScriptName()
                                + "' has already been applied but its content changed (checksum mismatch)");
                    }
                    continue;
                }
                runScript(connection, script.getContent());
                history.recordApplied(script);
            }
        } catch (SQLException e) {
            throw new SchemaMigrationException("Could not bootstrap the database schema", e);
        }
    }

    private static void runScript(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            for (String part : sql.split(";")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    statement.execute(trimmed);
                }
            }
        } catch (SQLException e) {
            throw new SchemaMigrationException("Could not execute script", e);
        }
    }
}
