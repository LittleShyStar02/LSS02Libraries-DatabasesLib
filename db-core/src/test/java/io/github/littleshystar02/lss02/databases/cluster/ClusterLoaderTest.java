package io.github.littleshystar02.lss02.databases.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClusterLoaderTest {

    private static final String YAML = """
            database-cluster:
              primary-mysql:
                type: mysql
                host: 127.0.0.1
                port: 3306
                database: shop_prod
                username: shop_app
                password: shop_password
                options:
                  useSSL: "false"
                pool:
                  maximum-pool-size: 10
                  minimum-idle: 2
                  keepalive-time-ms: 45000
                migrations:
                  setup-script: classpath:db/mysql/setup.sql
                  versioned-scripts-path: classpath:db/mysql/migrations
                mappers:
                  base-package: com.example.mysql.mappers

              archive-sqlite:
                type: sqlite
                file-path: ./data/archive.db
            """;

    private DatabaseClusterConfiguration load(Path tempDir, String yaml) throws IOException {
        Path file = tempDir.resolve("cluster.yml");
        Files.writeString(file, yaml);
        DatabaseClusterConfiguration configuration = new DatabaseClusterConfiguration(file.toFile());
        configuration.load();
        return configuration;
    }

    @Test
    void parsesEachEntryIntoADatabaseDefinition(@TempDir Path tempDir) throws IOException {
        Map<String, DatabaseDefinition> definitions = ClusterLoader.load(load(tempDir, YAML));

        assertEquals(2, definitions.size());

        DatabaseDefinition mysql = definitions.get("primary-mysql");
        assertEquals(DatabaseType.MYSQL, mysql.getType());
        assertEquals("127.0.0.1", mysql.getHost());
        assertEquals(3306, mysql.getPort());
        assertEquals("shop_password", mysql.getPassword());
        assertEquals("false", mysql.getOptions().get("useSSL"));
        assertEquals(10, mysql.getPool().getMaximumPoolSize());
        assertEquals(45000, mysql.getPool().getKeepaliveTimeMs());
        assertEquals("classpath:db/mysql/setup.sql", mysql.getSetupScript());
        assertEquals("com.example.mysql.mappers", mysql.getMapperBasePackage());

        DatabaseDefinition sqlite = definitions.get("archive-sqlite");
        assertEquals(DatabaseType.SQLITE, sqlite.getType());
        assertEquals("./data/archive.db", sqlite.getFilePath());
        assertNull(sqlite.getHost());
    }

    @Test
    void appliesPoolDefaultsWhenNoPoolSectionIsPresent(@TempDir Path tempDir) throws IOException {
        Map<String, DatabaseDefinition> definitions = ClusterLoader.load(load(tempDir, YAML));

        assertEquals(PoolSettings.defaults(), definitions.get("archive-sqlite").getPool());
    }

    @Test
    void failsFastWhenAnEnvVariableIsMissing(@TempDir Path tempDir) throws IOException {
        String yamlWithEnvVar = YAML.replace("password: shop_password", "password: ${LSS02_DEFINITELY_UNSET_VARIABLE}");
        DatabaseClusterConfiguration configuration = load(tempDir, yamlWithEnvVar);

        assertThrows(ClusterConfigurationException.class, () -> ClusterLoader.load(configuration));
    }

    @Test
    void failsFastWhenTheRootSectionIsMissing(@TempDir Path tempDir) throws IOException {
        DatabaseClusterConfiguration configuration = load(tempDir, "not-the-right-key: {}");

        assertThrows(ClusterConfigurationException.class, () -> ClusterLoader.load(configuration));
    }
}
