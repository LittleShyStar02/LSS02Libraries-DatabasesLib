package io.github.littleshystar02.lss02.databases.mybatis;

import io.github.littleshystar02.lss02.databases.cluster.ClusterConfigurationException;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseClusterConfiguration;
import io.github.littleshystar02.lss02.databases.mybatis.fixtures.User;
import io.github.littleshystar02.lss02.databases.mybatis.fixtures.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseClusterTest {

    private DatabaseClusterConfiguration configure(Path tempDir) throws IOException {
        Path dbFile = tempDir.resolve("archive.db");
        String yaml = """
                database-cluster:
                  archive:
                    type: sqlite
                    file-path: %s
                    migrations:
                      setup-script: classpath:db/sqlite/setup.sql
                    mappers:
                      base-package: io.github.littleshystar02.lss02.databases.mybatis.fixtures
                """.formatted(dbFile.toString().replace("\\", "/"));

        Path configFile = tempDir.resolve("cluster.yml");
        Files.writeString(configFile, yaml);
        DatabaseClusterConfiguration configuration = new DatabaseClusterConfiguration(configFile.toFile());
        configuration.load();
        return configuration;
    }

    @Test
    void bootstrapsSchemaAndExecutesAMapperEndToEnd(@TempDir Path tempDir) throws IOException {
        try (DatabaseCluster cluster = new DatabaseCluster(configure(tempDir))) {
            cluster.useMapper("archive", UserMapper.class, mapper -> {
                User user = new User();
                user.setId(1);
                user.setName("Ada");
                mapper.insert(user);
            });

            List<User> users = cluster.withMapper("archive", UserMapper.class, UserMapper::findAll);

            assertEquals(1, users.size());
            assertEquals("Ada", users.get(0).getName());
        }
    }

    @Test
    void bootstrapIsIdempotentAcrossRestarts(@TempDir Path tempDir) throws IOException {
        DatabaseClusterConfiguration configuration = configure(tempDir);

        try (DatabaseCluster first = new DatabaseCluster(configuration)) {
            first.useMapper("archive", UserMapper.class, mapper -> {
                User user = new User();
                user.setId(1);
                user.setName("Ada");
                mapper.insert(user);
            });
        }

        try (DatabaseCluster second = new DatabaseCluster(configuration)) {
            List<User> users = second.withMapper("archive", UserMapper.class, UserMapper::findAll);
            assertEquals(1, users.size());
        }
    }

    @Test
    void throwsForAnUnknownDatabaseName(@TempDir Path tempDir) throws IOException {
        try (DatabaseCluster cluster = new DatabaseCluster(configure(tempDir))) {
            assertThrows(ClusterConfigurationException.class, () -> cluster.getDataSource("does-not-exist"));
        }
    }
}
