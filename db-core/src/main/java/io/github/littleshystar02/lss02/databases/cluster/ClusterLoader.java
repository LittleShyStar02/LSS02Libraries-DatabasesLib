package io.github.littleshystar02.lss02.databases.cluster;

import org.simpleyaml.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates the {@code database-cluster} section of a YAML configuration into
 * {@link DatabaseDefinition} objects.
 *
 * <p>Every child of the section is one named database. All text values go through
 * {@link EnvVarInterpolator}, so secrets can be written as {@code ${VARIABLE}} instead of being stored
 * in the file.
 */
public final class ClusterLoader {

    /** Name of the top-level YAML section that lists the databases. */
    private static final String ROOT_SECTION = "database-cluster";

    /** Not instantiable: this class only holds static helpers. */
    private ClusterLoader() {
    }

    /**
     * Reads every database entry of the configuration.
     *
     * @param configuration a loaded cluster configuration
     * @return the definitions keyed by database name, in the order they appear in the file
     * @throws ClusterConfigurationException if the {@code database-cluster} section is missing, an entry
     *         is not a section, or a referenced environment variable is not set
     * @throws IllegalArgumentException if an entry has a {@code type} that is not a known
     *         {@link DatabaseType}
     */
    public static Map<String, DatabaseDefinition> load(DatabaseClusterConfiguration configuration) {
        ConfigurationSection root = configuration.getConfigurationSection(ROOT_SECTION);
        if (root == null) {
            throw new ClusterConfigurationException("Missing top-level '" + ROOT_SECTION + "' section");
        }

        Map<String, DatabaseDefinition> definitions = new LinkedHashMap<>();
        for (String name : root.getKeys(false)) {
            ConfigurationSection entry = root.getConfigurationSection(name);
            if (entry == null) {
                throw new ClusterConfigurationException("Entry '" + name + "' in '" + ROOT_SECTION + "' is not a section");
            }
            definitions.put(name, parseDefinition(name, entry));
        }
        return definitions;
    }

    /**
     * Builds one definition from its YAML section: connection settings, options, pool, migrations and
     * mapper packages. Absent optional settings stay {@code null}.
     *
     * @param name the entry name, which becomes the database name
     * @param entry the YAML section of the entry
     * @return the parsed definition
     */
    private static DatabaseDefinition parseDefinition(String name, ConfigurationSection entry) {
        String typeValue = interpolate(entry.getString("type"));
        DatabaseType type = typeValue == null ? null : DatabaseType.valueOf(typeValue.toUpperCase());

        ConfigurationSection migrations = entry.getConfigurationSection("migrations");
        ConfigurationSection mappers = entry.getConfigurationSection("mappers");

        return DatabaseDefinition.builder()
                .name(name)
                .type(type)
                .host(interpolate(entry.getString("host")))
                .port(entry.contains("port") ? entry.getInt("port") : null)
                .database(interpolate(entry.getString("database")))
                .username(interpolate(entry.getString("username")))
                .password(interpolate(entry.getString("password")))
                .filePath(interpolate(entry.getString("file-path")))
                .jdbcUrl(interpolate(entry.getString("jdbc-url")))
                .driverClassName(interpolate(entry.getString("driver-class-name")))
                .options(parseOptions(entry.getConfigurationSection("options")))
                .pool(parsePool(entry.getConfigurationSection("pool")))
                .setupScript(migrations == null ? null : interpolate(migrations.getString("setup-script")))
                .versionedScriptsPath(migrations == null ? null : interpolate(migrations.getString("versioned-scripts-path")))
                .mapperBasePackage(mappers == null ? null : mappers.getString("base-package"))
                .entityBasePackage(mappers == null ? null : mappers.getString("entity-package"))
                .build();
    }

    /**
     * Reads the free-form JDBC {@code options} section, converting values to interpolated strings.
     *
     * @param options the {@code options} section, may be {@code null}
     * @return the options in file order, empty when the section is absent
     */
    private static Map<String, String> parseOptions(ConfigurationSection options) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (options != null) {
            for (String key : options.getKeys(false)) {
                Object value = options.get(key);
                parsed.put(key, interpolate(value == null ? null : String.valueOf(value)));
            }
        }
        return parsed;
    }

    /**
     * Reads the {@code pool} section, taking {@link PoolSettings#defaults()} for any missing value.
     *
     * @param pool the {@code pool} section, may be {@code null}
     * @return the pool settings, the defaults when the section is absent
     */
    private static PoolSettings parsePool(ConfigurationSection pool) {
        PoolSettings defaults = PoolSettings.defaults();
        if (pool == null) {
            return defaults;
        }
        return PoolSettings.builder()
                .maximumPoolSize(pool.getInt("maximum-pool-size", defaults.getMaximumPoolSize()))
                .minimumIdle(pool.getInt("minimum-idle", defaults.getMinimumIdle()))
                .connectionTimeoutMs(pool.getLong("connection-timeout-ms", defaults.getConnectionTimeoutMs()))
                .idleTimeoutMs(pool.getLong("idle-timeout-ms", defaults.getIdleTimeoutMs()))
                .maxLifetimeMs(pool.getLong("max-lifetime-ms", defaults.getMaxLifetimeMs()))
                .keepaliveTimeMs(pool.getLong("keepalive-time-ms", defaults.getKeepaliveTimeMs()))
                .build();
    }

    /**
     * Shortcut for {@link EnvVarInterpolator#interpolate(String)}.
     *
     * @param value the raw text, may be {@code null}
     * @return the text with environment variables resolved
     */
    private static String interpolate(String value) {
        return EnvVarInterpolator.interpolate(value);
    }
}
