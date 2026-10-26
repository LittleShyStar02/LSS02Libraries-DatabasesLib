package io.github.littleshystar02.lss02.databases.cluster;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Immutable description of one database of the cluster, as read from its YAML entry.
 *
 * <p>Which fields matter depends on {@link #type}: server databases use {@link #host},
 * {@link #port}, {@link #database}, SQLite uses {@link #filePath}, and {@link #jdbcUrl} overrides
 * the generated URL for any type. Unset optional settings are {@code null}. Instances are created
 * through the generated builder.
 */
@Value
@Builder
public class DatabaseDefinition {

    /** Unique name of the database inside the cluster, also used as the connection pool name. */
    String name;
    /** Database engine; may be {@code null} when {@link #jdbcUrl} is given explicitly. */
    DatabaseType type;

    /** Server host name or address (server databases only). */
    String host;
    /** Server port; {@code null} selects the default port of the engine. */
    Integer port;
    /** Name of the database or schema on the server (server databases only). */
    String database;
    /** User name for the connection, or {@code null} if none is needed. */
    String username;
    /** Password for the connection, or {@code null} if none is needed. */
    String password;

    /** Path of the database file (SQLite only). */
    String filePath;
    /** Complete JDBC URL; when set it takes precedence over the URL built from host, port and database. */
    String jdbcUrl;
    /** JDBC driver class; {@code null} selects the default driver of {@link #type}. */
    String driverClassName;

    /** Extra JDBC URL parameters appended as a query string. */
    Map<String, String> options;

    /** Connection pool tuning; {@code null} means {@link PoolSettings#defaults()}. */
    PoolSettings pool;

    /** Location of the baseline SQL script run once on a new database, or {@code null}. */
    String setupScript;
    /** Location of the directory of {@code V<version>__<description>.sql} scripts, or {@code null}. */
    String versionedScriptsPath;

    /** Java package scanned for MyBatis mapper interfaces, or {@code null} to register none. */
    String mapperBasePackage;
    /**
     * Java package holding the entity classes of this database; informational, read from the configuration.
     */
    String entityBasePackage;
}
