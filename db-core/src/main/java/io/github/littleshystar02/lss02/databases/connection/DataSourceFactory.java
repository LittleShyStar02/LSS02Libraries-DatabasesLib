// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.connection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import io.github.littleshystar02.lss02.databases.cluster.ClusterConfigurationException;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.cluster.PoolSettings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Creates pooled HikariCP data sources from {@link DatabaseDefinition} objects.
 *
 * <p>The JDBC URL is either taken as is from the definition or built from the engine's URL template,
 * host, port, database name and extra options.
 *
 * <p>Structured fields are validated and option keys and values are URL-encoded, so a configuration
 * value can never smuggle extra driver parameters into the generated URL. An explicit {@code jdbc-url}
 * is used verbatim and is the caller's responsibility.
 *
 * <p>SQLite databases built from {@code file-path} get file-friendly defaults: a single pooled connection
 * (see {@link PoolSettings#defaultsFor(DatabaseType)}), the write-ahead log and a 5 s busy timeout, unless
 * the {@code options} already set {@code journal_mode} or {@code busy_timeout}.
 */
public final class DataSourceFactory {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceFactory.class);

    /** Host names, IPv4 addresses and bracketed IPv6 literals. */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9_.-]+|\\[[0-9A-Fa-f:.]+\\]");
    /** Database or schema names. */
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_.$-]+");
    /** Option names. */
    private static final Pattern OPTION_KEY = Pattern.compile("[A-Za-z0-9_.-]+");
    /** Driver options that widen the attack surface of the connection (compared in lower case). */
    private static final Set<String> RISKY_MYSQL_OPTIONS =
            Set.of("allowloadlocalinfile", "allowurlinlocalinfile", "allowmultiqueries", "autodeserialize");

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
        return create(definition, null);
    }

    /**
     * Builds and starts a connection pool like {@link #create(DatabaseDefinition)}, additionally plugging
     * a metrics tracker into it (for example the Micrometer or Prometheus factories shipped by HikariCP).
     *
     * @param definition the database to connect to
     * @param metricsTrackerFactory the metrics hook, or {@code null} for none
     * @return an open pool; the caller must close it
     * @throws ClusterConfigurationException if the definition lacks the data needed to build the URL
     */
    public static HikariDataSource create(DatabaseDefinition definition, MetricsTrackerFactory metricsTrackerFactory) {
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

        PoolSettings pool = definition.getPool() != null ? definition.getPool() : PoolSettings.defaultsFor(definition.getType());
        config.setMaximumPoolSize(pool.getMaximumPoolSize());
        config.setMinimumIdle(pool.getMinimumIdle());
        config.setConnectionTimeout(pool.getConnectionTimeoutMs());
        config.setIdleTimeout(pool.getIdleTimeoutMs());
        config.setMaxLifetime(pool.getMaxLifetimeMs());
        config.setKeepaliveTime(pool.getKeepaliveTimeMs());
        config.setLeakDetectionThreshold(pool.getLeakDetectionThresholdMs());
        config.setInitializationFailTimeout(pool.getInitializationFailTimeoutMs());
        if (pool.getValidationTimeoutMs() >= 250) {
            config.setValidationTimeout(pool.getValidationTimeoutMs());
        }
        if (pool.getConnectionInitSql() != null && !pool.getConnectionInitSql().isBlank()) {
            config.setConnectionInitSql(pool.getConnectionInitSql());
        }
        config.setRegisterMbeans(pool.isRegisterMbeans());
        if (metricsTrackerFactory != null) {
            config.setMetricsTrackerFactory(metricsTrackerFactory);
        }

        LOG.debug("Starting pool '{}' (max {}, min idle {}, connection timeout {} ms, leak detection {} ms)",
                definition.getName(), pool.getMaximumPoolSize(), pool.getMinimumIdle(),
                pool.getConnectionTimeoutMs(), pool.getLeakDetectionThresholdMs());
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
    static String resolveJdbcUrl(DatabaseDefinition definition) {
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
            String filePath = definition.getFilePath();
            if (filePath.isBlank() || filePath.indexOf('?') >= 0 || filePath.indexOf(';') >= 0
                    || filePath.indexOf('\n') >= 0 || filePath.indexOf('\r') >= 0) {
                throw new ClusterConfigurationException("Database '" + definition.getName()
                        + "' has an invalid 'file-path': it must not be blank or contain '?', ';' or line breaks");
            }
            baseUrl = String.format(definition.getType().getUrlTemplate(), filePath);
            return appendOptions(baseUrl, withSqliteDefaults(definition.getOptions(), filePath));
        } else {
            if (definition.getHost() == null) {
                throw new ClusterConfigurationException("Database '" + definition.getName() + "' is missing 'host'");
            }
            if (definition.getDatabase() == null) {
                throw new ClusterConfigurationException("Database '" + definition.getName() + "' is missing 'database'");
            }
            requireMatch(definition, "host", definition.getHost(), HOST);
            requireMatch(definition, "database", definition.getDatabase(), DATABASE);
            warnIfInsecure(definition);
            baseUrl = String.format(
                    definition.getType().getUrlTemplate(),
                    definition.getHost(),
                    definition.getPort() != null ? definition.getPort() : defaultPort(definition.getType()),
                    definition.getDatabase());
        }

        return appendOptions(baseUrl, definition.getOptions());
    }

    /**
     * Adds the file-database defaults (write-ahead log, 5 s busy timeout) to the options, without
     * overriding values the configuration sets itself. In-memory databases keep their options untouched.
     *
     * @param options the configured options, may be {@code null}
     * @param filePath the SQLite file path
     * @return the options to put in the URL
     */
    private static Map<String, String> withSqliteDefaults(Map<String, String> options, String filePath) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (options != null) {
            merged.putAll(options);
        }
        if (filePath.contains(":memory:") || filePath.startsWith("file::memory:")) {
            return merged;
        }
        if (merged.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("journal_mode"))) {
            merged.put("journal_mode", "WAL");
        }
        if (merged.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("busy_timeout"))) {
            merged.put("busy_timeout", "5000");
        }
        return merged;
    }

    /**
     * Checks that a structured field holds only the characters allowed for it.
     *
     * @param definition the database, used in the message
     * @param field the YAML field name
     * @param value the value to check
     * @param allowed the allowed shape
     * @throws ClusterConfigurationException if the value does not match
     */
    private static void requireMatch(DatabaseDefinition definition, String field, String value, Pattern allowed) {
        if (!allowed.matcher(value).matches()) {
            throw new ClusterConfigurationException("Database '" + definition.getName() + "' has an invalid '"
                    + field + "': only letters, digits and the characters _ . - are allowed (no '?', '&', ';', '/' or spaces)");
        }
    }

    /**
     * Logs a warning when a remote server is reached without TLS, or when a risky MySQL driver option is set.
     * The values themselves are never logged.
     *
     * @param definition the database being connected to
     */
    private static void warnIfInsecure(DatabaseDefinition definition) {
        Map<String, String> options = definition.getOptions() == null ? Map.of() : definition.getOptions();
        if (!isLoopback(definition.getHost()) && !isTlsEnabled(options)) {
            LOG.warn("Database '" + definition.getName() + "' connects to the remote host '" + definition.getHost()
                    + "' without TLS: credentials and data travel in clear text. Set sslMode=REQUIRED"
                    + " (MySQL/MariaDB) or sslmode=verify-full (PostgreSQL) in 'options'.");
        }
        for (Map.Entry<String, String> option : options.entrySet()) {
            if (RISKY_MYSQL_OPTIONS.contains(option.getKey().toLowerCase(Locale.ROOT))
                    && "true".equalsIgnoreCase(option.getValue())) {
                LOG.warn("Database '" + definition.getName() + "' enables the risky driver option '"
                        + option.getKey() + "'");
            }
        }
    }

    /**
     * Tells whether a host name designates the local machine.
     *
     * @param host the host from the definition
     * @return {@code true} for localhost, 127.x.x.x and ::1
     */
    private static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.equals("[::1]");
    }

    /**
     * Tells whether the options turn TLS on, in any of the spellings used by the supported drivers.
     *
     * @param options the definition options
     * @return {@code true} if TLS is requested
     */
    private static boolean isTlsEnabled(Map<String, String> options) {
        for (Map.Entry<String, String> option : options.entrySet()) {
            String key = option.getKey().toLowerCase(Locale.ROOT);
            String value = option.getValue() == null ? "" : option.getValue().toLowerCase(Locale.ROOT);
            if (key.equals("sslmode")) {
                return !Set.of("disable", "disabled", "allow", "prefer", "preferred").contains(value);
            }
            if ((key.equals("usessl") || key.equals("ssl")) && value.equals("true")) {
                return true;
            }
        }
        return false;
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
     * depending on whether the URL already has a query string. Keys must be plain names and values are URL-encoded.
     *
     * @param baseUrl the URL built so far
     * @param options the extra parameters, may be {@code null} or empty
     * @return the URL with the options appended
     * @throws ClusterConfigurationException if an option key contains characters other than letters,
     *         digits and {@code _ . -}
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
            if (!OPTION_KEY.matcher(option.getKey()).matches()) {
                throw new ClusterConfigurationException("Invalid option name '" + option.getKey()
                        + "': only letters, digits and the characters _ . - are allowed");
            }
            String value = option.getValue() == null ? "" : option.getValue();
            url.append(option.getKey()).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            first = false;
        }
        return url.toString();
    }
}
