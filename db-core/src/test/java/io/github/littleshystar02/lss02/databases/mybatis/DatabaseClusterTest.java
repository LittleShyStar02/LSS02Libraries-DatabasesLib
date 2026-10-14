package io.github.littleshystar02.lss02.databases.mybatis;

import io.github.littleshystar02.lss02.databases.cluster.ClusterConfigurationException;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseClusterConfiguration;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.mybatis.fixtures.User;
import io.github.littleshystar02.lss02.databases.mybatis.fixtures.UserMapper;
import io.github.littleshystar02.lss02.databases.testsupport.JarFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    void buildsFromDefinitionsAndReadsScriptsFromTheGivenClassLoader(@TempDir Path tempDir) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("plugin-db/setup.sql", "create table base (id integer primary key);");
        entries.put("plugin-db/migrations/V1__add_extra.sql", "create table extra (id integer primary key);");
        Path jar = JarFixtures.createJar(tempDir.resolve("plugin.jar"), entries);

        DatabaseDefinition definition = DatabaseDefinition.builder()
                .name("main")
                .type(DatabaseType.SQLITE)
                .filePath(tempDir.resolve("main.db").toString().replace("\\", "/"))
                .setupScript("classpath:plugin-db/setup.sql")
                .versionedScriptsPath("classpath:plugin-db/migrations")
                .build();

        ClassLoader contextBefore = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = JarFixtures.isolatedLoader(jar);
             DatabaseCluster cluster = new DatabaseCluster(List.of(definition), loader)) {
            assertEquals(contextBefore, Thread.currentThread().getContextClassLoader());
            assertEquals(1, countTables(cluster, "base"));
            assertEquals(1, countTables(cluster, "extra"));
        }
    }

    @Test
    void rejectsDuplicateDefinitionNames(@TempDir Path tempDir) {
        DatabaseDefinition first = DatabaseDefinition.builder()
                .name("same").type(DatabaseType.SQLITE).filePath(tempDir.resolve("a.db").toString()).build();
        DatabaseDefinition second = DatabaseDefinition.builder()
                .name("same").type(DatabaseType.SQLITE).filePath(tempDir.resolve("b.db").toString()).build();

        assertThrows(ClusterConfigurationException.class, () -> new DatabaseCluster(List.of(first, second)));
    }

    private static int countTables(DatabaseCluster cluster, String table) throws SQLException {
        try (Connection connection = cluster.getDataSource("main").getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select count(*) from sqlite_master where type = 'table' and name = '" + table + "'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    @Test
    void throwsForAnUnknownDatabaseName(@TempDir Path tempDir) throws IOException {
        try (DatabaseCluster cluster = new DatabaseCluster(configure(tempDir))) {
            assertThrows(ClusterConfigurationException.class, () -> cluster.getDataSource("does-not-exist"));
        }
    }
}
