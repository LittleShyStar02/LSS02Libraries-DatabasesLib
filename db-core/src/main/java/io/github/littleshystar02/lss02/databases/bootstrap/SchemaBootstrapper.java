// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.bootstrap;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Brings a database schema up to date by running SQL scripts exactly once, using Flyway.
 *
 * <p>The optional baseline setup script is run as migration version {@code 0}, then every
 * {@code V<version>__<description>.sql} script of the versioned directory runs in ascending version
 * order. Flyway takes a database-level lock for the whole run (so two servers bootstrapping the same
 * database never interleave), runs each script in its own transaction where the engine supports
 * transactional DDL, parses the scripts with its own SQL parser (semicolons inside strings, comments
 * and procedure bodies are handled), rejects duplicate versions and verifies the checksum of every
 * applied script.
 *
 * <p>Databases bootstrapped by earlier releases keep a {@code lss02_schema_history} table. When one is
 * found and no Flyway history exists yet, it is adopted as a baseline at the highest applied version:
 * nothing is run again and the old table is left untouched.
 */
public final class SchemaBootstrapper {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaBootstrapper.class);

    /** Version under which the baseline setup script is run. */
    private static final String BASELINE_VERSION = "0";
    /** File name given to the baseline setup script inside the temporary directory handed to Flyway. */
    private static final String BASELINE_FILE_NAME = "V0__baseline.sql";
    /** History table written by releases that did not use Flyway. */
    private static final String LEGACY_HISTORY_TABLE = "lss02_schema_history";
    /** History table written by Flyway (its default name). */
    private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";
    /** Location prefix that selects the classpath instead of the file system. */
    private static final String CLASSPATH_PREFIX = "classpath:";
    /** In-JVM locks of SQLite databases, one per JDBC URL. */
    private static final ConcurrentMap<String, Object> SQLITE_LOCKS = new ConcurrentHashMap<>();
    /** Location prefix Flyway uses for plain directories. */
    private static final String FILESYSTEM_PREFIX = "filesystem:";

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
     * @param dataSource the database to bring up to date
     * @param setupScriptLocation {@code classpath:} location or file of the baseline script, run only if
     *                            the database has no history yet; {@code null} to skip it
     * @param versionedScriptsLocation {@code classpath:} location or directory of the
     *                                 {@code V<version>__<description>.sql} scripts; {@code null} to skip
     * @param classLoader the class loader for {@code classpath:} locations, {@code null} for the thread
     *                    context class loader
     * @throws SchemaMigrationException if a script cannot be run (the message names the script and the
     *                                  failing statement), a previously applied script has a different
     *                                  checksum, two scripts share a version, or the database is not
     *                                  reachable
     */
    public static void bootstrap(DataSource dataSource, String setupScriptLocation, String versionedScriptsLocation,
                                 ClassLoader classLoader) {
        synchronized (lockFor(dataSource)) {
            doBootstrap(dataSource, setupScriptLocation, versionedScriptsLocation, classLoader);
        }
    }

    /**
     * Gives the lock that serialises bootstraps inside this JVM. SQLite has no advisory locks, so two
     * pools on the same file would race on the schema history; for it the lock is shared per JDBC URL.
     * Other engines lock through Flyway itself, so each call gets a private, uncontended lock.
     *
     * @param dataSource the database about to be bootstrapped
     * @return the object to synchronise on
     */
    private static Object lockFor(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            if (url != null && url.startsWith("jdbc:sqlite:")) {
                return SQLITE_LOCKS.computeIfAbsent(url, key -> new Object());
            }
        } catch (SQLException e) {
            throw new SchemaMigrationException("Could not bootstrap the database schema", e);
        }
        return new Object();
    }

    private static void doBootstrap(DataSource dataSource, String setupScriptLocation, String versionedScriptsLocation,
                                    ClassLoader classLoader) {
        ClassLoader loader = ScriptLoader.resolve(classLoader);
        Path baselineDirectory = null;
        try {
            List<String> locations = new ArrayList<>();
            if (setupScriptLocation != null) {
                baselineDirectory = writeBaseline(ScriptLoader.load(setupScriptLocation, loader));
                locations.add(FILESYSTEM_PREFIX + baselineDirectory.toAbsolutePath());
            }
            if (versionedScriptsLocation != null) {
                locations.add(toFlywayLocation(versionedScriptsLocation));
            }

            FluentConfiguration configuration = Flyway.configure(loader)
                    .dataSource(dataSource)
                    .locations(locations.toArray(new String[0]))
                    .cleanDisabled(true);

            Integer legacyVersion = findLegacyBaseline(dataSource);
            if (legacyVersion != null) {
                configuration.baselineOnMigrate(true).baselineVersion(String.valueOf(legacyVersion));
            } else {
                configuration.baselineVersion(BASELINE_VERSION);
            }

            long begin = System.nanoTime();
            MigrateResult result = configuration.load().migrate();
            logResult(result, System.nanoTime() - begin);
        } catch (FlywayException e) {
            LOG.error("Schema migration failed: {}", e.getMessage());
            throw new SchemaMigrationException("Could not bootstrap the database schema: " + e.getMessage(), e);
        } catch (SQLException e) {
            throw new SchemaMigrationException("Could not bootstrap the database schema", e);
        } finally {
            deleteQuietly(baselineDirectory);
        }
    }

    /**
     * Logs what a migration run did: one line per applied script and a summary.
     *
     * @param result the Flyway outcome
     * @param nanos the time the run took
     */
    private static void logResult(MigrateResult result, long nanos) {
        if (result.migrations != null) {
            result.migrations.forEach(m -> LOG.info("Applied migration {} ({}) in {} ms",
                    m.version, m.description, m.executionTime));
        }
        LOG.info("Schema is at version {}: {} migration(s) applied in {} ms",
                result.targetSchemaVersion, result.migrationsExecuted, nanos / 1_000_000);
    }

    /**
     * Translates a library location into a Flyway location: {@code classpath:} stays as is, a plain
     * directory gets the {@code filesystem:} prefix.
     *
     * @param location the configured location
     * @return the Flyway location
     */
    private static String toFlywayLocation(String location) {
        if (location.startsWith(CLASSPATH_PREFIX) || location.startsWith(FILESYSTEM_PREFIX)) {
            return location;
        }
        return FILESYSTEM_PREFIX + Paths.get(location).toAbsolutePath();
    }

    /**
     * Writes the baseline script to a temporary directory so Flyway can pick it up as version 0.
     *
     * @param content the script text
     * @return the temporary directory
     * @throws SchemaMigrationException if the file cannot be written
     */
    private static Path writeBaseline(String content) {
        try {
            Path directory = Files.createTempDirectory("lss02-baseline");
            Files.writeString(directory.resolve(BASELINE_FILE_NAME), content, StandardCharsets.UTF_8);
            return directory;
        } catch (IOException e) {
            throw new SchemaMigrationException("Could not stage the setup script", e);
        }
    }

    /**
     * Looks for a history table left by releases without Flyway.
     *
     * @param dataSource the database to inspect
     * @return the highest applied version when a legacy history exists and no Flyway history does,
     *         {@code null} otherwise
     * @throws SQLException if the database cannot be inspected
     */
    private static Integer findLegacyBaseline(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (tableExists(connection, FLYWAY_HISTORY_TABLE) || !tableExists(connection, LEGACY_HISTORY_TABLE)) {
                return null;
            }
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT MAX(version) FROM " + LEGACY_HISTORY_TABLE)) {
                if (resultSet.next()) {
                    int max = resultSet.getInt(1);
                    return resultSet.wasNull() ? 0 : max;
                }
                return 0;
            }
        }
    }

    /**
     * Checks whether a table exists, ignoring the case of its name.
     *
     * @param connection the connection to ask
     * @param table the table name, in lower case
     * @return {@code true} if the table exists in the current catalog and schema
     * @throws SQLException if the metadata cannot be read
     */
    private static boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        for (String candidate : new String[]{table, table.toUpperCase(Locale.ROOT)}) {
            try (ResultSet tables = metaData.getTables(
                    connection.getCatalog(), connection.getSchema(), candidate, new String[]{"TABLE"})) {
                if (tables.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Deletes the temporary baseline directory, ignoring failures.
     *
     * @param directory the directory to remove, may be {@code null}
     */
    private static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort: the directory lives in the temp folder
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
