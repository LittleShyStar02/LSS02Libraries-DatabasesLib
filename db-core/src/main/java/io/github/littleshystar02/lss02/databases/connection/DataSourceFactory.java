package io.github.littleshystar02.lss02.databases.connection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.littleshystar02.lss02.databases.cluster.ClusterConfigurationException;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.cluster.PoolSettings;

import java.util.Map;

public final class DataSourceFactory {

    private DataSourceFactory() {
    }

    public static HikariDataSource create(DatabaseDefinition definition) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(resolveJdbcUrl(definition));
        config.setPoolName(definition.getName());

        String driverClassName = definition.getDriverClassName();
        if (driverClassName == null && definition.getType() != null) {
            driverClassName = definition.getType().getDefaultDriverClassName();
        }
        if (driverClassName != null) {
            config.setDriverClassName(driverClassName);
        }

        if (definition.getUsername() != null) {
            config.setUsername(definition.getUsername());
        }
        if (definition.getPassword() != null) {
            config.setPassword(definition.getPassword());
        }

        PoolSettings pool = definition.getPool() != null ? definition.getPool() : PoolSettings.defaults();
        config.setMaximumPoolSize(pool.getMaximumPoolSize());
        config.setMinimumIdle(pool.getMinimumIdle());
        config.setConnectionTimeout(pool.getConnectionTimeoutMs());
        config.setIdleTimeout(pool.getIdleTimeoutMs());
        config.setMaxLifetime(pool.getMaxLifetimeMs());

        return new HikariDataSource(config);
    }

    private static String resolveJdbcUrl(DatabaseDefinition definition) {
        if (definition.getJdbcUrl() != null) {
            return definition.getJdbcUrl();
        }
        if (definition.getType() == null) {
            throw new ClusterConfigurationException(
                    "Database '" + definition.getName() + "' has neither 'jdbc-url' nor 'type' set");
        }

        String baseUrl;
        if (definition.getType() == DatabaseType.SQLITE) {
            if (definition.getFilePath() == null) {
                throw new ClusterConfigurationException(
                        "Database '" + definition.getName() + "' is of type sqlite but has no 'file-path'");
            }
            baseUrl = String.format(definition.getType().getUrlTemplate(), definition.getFilePath());
        } else {
            if (definition.getHost() == null) {
                throw new ClusterConfigurationException("Database '" + definition.getName() + "' is missing 'host'");
            }
            if (definition.getDatabase() == null) {
                throw new ClusterConfigurationException("Database '" + definition.getName() + "' is missing 'database'");
            }
            baseUrl = String.format(
                    definition.getType().getUrlTemplate(),
                    definition.getHost(),
                    definition.getPort() != null ? definition.getPort() : defaultPort(definition.getType()),
                    definition.getDatabase());
        }

        return appendOptions(baseUrl, definition.getOptions());
    }

    private static int defaultPort(DatabaseType type) {
        switch (type) {
            case MYSQL:
            case MARIADB:
                return 3306;
            case POSTGRESQL:
                return 5432;
            default:
                throw new ClusterConfigurationException("No default port known for database type " + type);
        }
    }

    private static String appendOptions(String baseUrl, Map<String, String> options) {
        if (options == null || options.isEmpty()) {
            return baseUrl;
        }
        StringBuilder url = new StringBuilder(baseUrl);
        url.append(baseUrl.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> option : options.entrySet()) {
            if (!first) {
                url.append('&');
            }
            url.append(option.getKey()).append('=').append(option.getValue());
            first = false;
        }
        return url.toString();
    }
}
