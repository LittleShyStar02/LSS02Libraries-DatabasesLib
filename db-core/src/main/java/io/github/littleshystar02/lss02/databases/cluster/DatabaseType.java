package io.github.littleshystar02.lss02.databases.cluster;

import lombok.Getter;

@Getter
public enum DatabaseType {

    MYSQL("com.mysql.cj.jdbc.Driver", "jdbc:mysql://%s:%d/%s"),
    POSTGRESQL("org.postgresql.Driver", "jdbc:postgresql://%s:%d/%s"),
    MARIADB("org.mariadb.jdbc.Driver", "jdbc:mariadb://%s:%d/%s"),
    SQLITE("org.sqlite.JDBC", "jdbc:sqlite:%s");

    private final String defaultDriverClassName;
    private final String urlTemplate;

    DatabaseType(String defaultDriverClassName, String urlTemplate) {
        this.defaultDriverClassName = defaultDriverClassName;
        this.urlTemplate = urlTemplate;
    }
}
