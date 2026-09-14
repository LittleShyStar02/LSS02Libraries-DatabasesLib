package io.github.littleshystar02.lss02.databases.cluster;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class DatabaseDefinition {

    String name;
    DatabaseType type;

    String host;
    Integer port;
    String database;
    String username;
    String password;

    String filePath;
    String jdbcUrl;
    String driverClassName;

    Map<String, String> options;

    PoolSettings pool;

    String setupScript;
    String versionedScriptsPath;

    String mapperBasePackage;
    String entityBasePackage;
}
