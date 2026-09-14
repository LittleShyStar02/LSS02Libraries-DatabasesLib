package io.github.littleshystar02.lss02.databases.cluster;

import org.simpleyaml.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ClusterLoader {

    private static final String ROOT_SECTION = "database-cluster";

    private ClusterLoader() {
    }

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
                .build();
    }

    private static String interpolate(String value) {
        return EnvVarInterpolator.interpolate(value);
    }
}
