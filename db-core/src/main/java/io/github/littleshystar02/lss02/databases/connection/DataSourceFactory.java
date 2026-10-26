package io.github.littleshystar02.lss02.databases.connection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.littleshystar02.lss02.databases.cluster.ClusterConfigurationException;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.cluster.PoolSettings;

import java.util.Map;

/**
 * Creates pooled HikariCP data sources from {@link DatabaseDefinition} objects.
 *
 * <p>The JDBC URL is either taken as is from the definition or built from the engine's URL template,
 * host, port, database name and extra options.
 */
public final class DataSourceFactory {

    /** Not instantiable: this class only holds static helpers. */
    private DataSourceFactory() {
    }

    /**
     * Builds and starts a connection pool for a database. The pool is named after the definition and
     * tuned with its {@link PoolSettings}, or the defaults when none are given.
     *
     * @param definition the database to connect to
     * @return an open pool; the caller must close it
     * @throws ClusterConfigurationException if the definition lacks the data needed to build the URL
     */
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
        config.setKeepaliveTime(pool.getKeepaliveTimeMs());

        return new HikariDataSource(config);
    }

    /**
     * Determines the JDBC URL: the explicit one if present, otherwise one built from the engine template.
     *
     * @param definition the database definition
     * @return the JDBC URL, including any options as a query string
     * @throws ClusterConfigurationException if neither {@code jdbc-url} nor {@code type} is set, or if
     *         {@code file-path} (SQLite), {@code host} or {@code database} (other engines) is missing
     */
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

    /**
     * Gives the standard port of a server engine: 3306 for MySQL and MariaDB, 5432 for PostgreSQL.
     *
     * @param type the engine
     * @return the default port
     * @throws ClusterConfigurationException if the engine has no network port (SQLite)
     */
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

    /**
     * Appends the options to a URL as {@code key=value} query parameters, using {@code ?} or {@code &}
     * depending on whether the URL already has a query string. Values are not URL-encoded.
     *
     * @param baseUrl the URL built so far
     * @param options the extra parameters, may be {@code null} or empty
     * @return the URL with the options appended
     */
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
