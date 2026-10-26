package io.github.littleshystar02.lss02.databases.cluster;

import lombok.Getter;

/**
 * Database engines supported by the cluster, each with its default JDBC driver and URL template.
 *
 * <p>The URL template is a {@link String#format} pattern: server engines receive host, port and
 * database name, SQLite receives the file path.
 */
@Getter
public enum DatabaseType {

    /** MySQL server. */
    MYSQL("com.mysql.cj.jdbc.Driver", "jdbc:mysql://%s:%d/%s"),
    /** PostgreSQL server. */
    POSTGRESQL("org.postgresql.Driver", "jdbc:postgresql://%s:%d/%s"),
    /** MariaDB server. */
    MARIADB("org.mariadb.jdbc.Driver", "jdbc:mariadb://%s:%d/%s"),
    /** SQLite file database. */
    SQLITE("org.sqlite.JDBC", "jdbc:sqlite:%s");

    /** Fully qualified class name of the JDBC driver used when the definition names none. */
    private final String defaultDriverClassName;
    /** {@link String#format} pattern used to build the JDBC URL. */
    private final String urlTemplate;

    /**
     * Creates a constant.
     *
     * @param defaultDriverClassName the default JDBC driver class
     * @param urlTemplate the JDBC URL pattern
     */
    DatabaseType(String defaultDriverClassName, String urlTemplate) {
        this.defaultDriverClassName = defaultDriverClassName;
        this.urlTemplate = urlTemplate;
    }
}
