// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.cluster;

import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.LinkedHashMap;
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
     * Java package holding the entity classes of this database; its classes are registered as MyBatis
     * type aliases (simple class name), or {@code null} to register none.
     */
    String entityBasePackage;

    /**
     * Describes the definition for logs and exception messages. The password is masked, the JDBC URL
     * is cut before its query string (which may carry credentials) and option values are left out.
     *
     * @return a text that is safe to log
     */
    @Override
    public String toString() {
        return "DatabaseDefinition(name=" + name
                + ", type=" + type
                + ", host=" + host
                + ", port=" + port
                + ", database=" + database
                + ", username=" + username
                + ", password=" + (password == null ? "null" : "***")
                + ", filePath=" + filePath
                + ", jdbcUrl=" + redactUrl(jdbcUrl)
                + ", driverClassName=" + driverClassName
                + ", optionKeys=" + (options == null ? "null" : options.keySet())
                + ", mapperBasePackage=" + mapperBasePackage
                + ", entityBasePackage=" + entityBasePackage + ")";
    }

    /**
     * Cuts a JDBC URL before its query string and any {@code ;} property list.
     *
     * @param url the URL, may be {@code null}
     * @return the URL without parameters, or {@code null}
     */
    private static String redactUrl(String url) {
        if (url == null) {
            return null;
        }
        int cut = url.length();
        int query = url.indexOf('?');
        int semicolon = url.indexOf(';');
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        if (semicolon >= 0) {
            cut = Math.min(cut, semicolon);
        }
        return cut == url.length() ? url : url.substring(0, cut) + "?...";
    }

    /** Builder customisation: the options map is copied so the definition stays immutable. */
    public static class DatabaseDefinitionBuilder {

        /**
         * Sets the extra JDBC URL parameters; the map is copied into an unmodifiable one that keeps order.
         *
         * @param options the parameters, may be {@code null}
         * @return this builder
         */
        public DatabaseDefinitionBuilder options(Map<String, String> options) {
            this.options = options == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(options));
            return this;
        }
    }
}
