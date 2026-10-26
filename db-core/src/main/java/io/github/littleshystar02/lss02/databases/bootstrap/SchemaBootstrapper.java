package io.github.littleshystar02.lss02.databases.bootstrap;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * Brings a database schema up to date by running SQL scripts exactly once.
 *
 * <p>The process is idempotent and similar in spirit to Flyway. A history table records every applied
 * script together with its checksum. On each run the bootstrapper first applies the optional baseline
 * setup script (version 0) if it was never applied, then every versioned script that is not yet in the
 * history, in ascending version order. Scripts already applied are skipped, but their checksum must
 * still match, otherwise the run fails.
 */
public final class SchemaBootstrapper {

    /** Version under which the baseline setup script is recorded in the history table. */
    private static final int BASELINE_VERSION = 0;

    /** Not instantiable: this class only holds static helpers. */
    private SchemaBootstrapper() {
    }

    /**
     * Bootstraps the schema using the default class loader (see
     * {@link #bootstrap(DataSource, String, String, ClassLoader)}).
     *
     * @param dataSource the database to bring up to date
     * @param setupScriptLocation location of the baseline script, or {@code null} to skip it
     * @param versionedScriptsLocation directory of the versioned scripts, or {@code null} to skip them
     * @throws SchemaMigrationException if a script fails or an applied script was modified
     */
    public static void bootstrap(DataSource dataSource, String setupScriptLocation, String versionedScriptsLocation) {
        bootstrap(dataSource, setupScriptLocation, versionedScriptsLocation, null);
    }

    /**
     * Applies the baseline script and all pending versioned scripts to a database.
     *
     * <p>Statements run on one connection and each script is split on {@code ;}, so scripts must not
     * contain semicolons inside string literals or procedure bodies.
     *
     * @param dataSource the database to bring up to date
     * @param setupScriptLocation {@code classpath:} location or file of the baseline script, run only if
     *                            version 0 is not in the history yet; {@code null} to skip it
     * @param versionedScriptsLocation {@code classpath:} location or directory of the
     *                                 {@code V<version>__<description>.sql} scripts; {@code null} to skip
     * @param classLoader the class loader for {@code classpath:} locations, {@code null} for the thread
     *                    context class loader
     * @throws SchemaMigrationException if a script cannot be run, a previously applied script has a
     *                                  different checksum, or the database is not reachable
     */
    public static void bootstrap(DataSource dataSource, String setupScriptLocation, String versionedScriptsLocation,
                                 ClassLoader classLoader) {
        try (Connection connection = dataSource.getConnection()) {
            SchemaHistoryRepository history = new SchemaHistoryRepository(connection);
            history.ensureTableExists();

            Map<Integer, String> applied = history.findAppliedChecksums();

            if (!applied.containsKey(BASELINE_VERSION) && setupScriptLocation != null) {
                String content = ScriptLoader.load(setupScriptLocation, classLoader);
                runScript(connection, content);
                MigrationScript baseline = new MigrationScript(
                        BASELINE_VERSION, "baseline", "setup.sql", content, ChecksumUtil.sha256(content));
                history.recordApplied(baseline);
                applied.put(BASELINE_VERSION, baseline.getChecksum());
            }

            for (MigrationScript script : MigrationScanner.scan(versionedScriptsLocation, classLoader)) {
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

    /**
     * Executes a script statement by statement.
     *
     * @param connection the connection to run on
     * @param sql the script text; statements are separated by {@code ;} and blank parts are skipped
     * @throws SchemaMigrationException if any statement fails
     */
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
