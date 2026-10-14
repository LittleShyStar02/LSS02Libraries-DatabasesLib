package io.github.littleshystar02.lss02.databases.connection;

import com.zaxxer.hikari.HikariDataSource;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.cluster.PoolSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataSourceFactoryTest {

    private HikariDataSource create(Path tempDir, PoolSettings pool) {
        DatabaseDefinition definition = DatabaseDefinition.builder()
                .name("pool-test")
                .type(DatabaseType.SQLITE)
                .filePath(tempDir.resolve("pool.db").toString().replace("\\", "/"))
                .pool(pool)
                .build();
        return DataSourceFactory.create(definition);
    }

    @Test
    void appliesTheConfiguredKeepaliveTime(@TempDir Path tempDir) {
        PoolSettings pool = PoolSettings.builder()
                .maximumPoolSize(2)
                .minimumIdle(0)
                .connectionTimeoutMs(30000)
                .idleTimeoutMs(600000)
                .maxLifetimeMs(1800000)
                .keepaliveTimeMs(60000)
                .build();

        try (HikariDataSource dataSource = create(tempDir, pool)) {
            assertEquals(60000, dataSource.getKeepaliveTime());
        }
    }

    @Test
    void leavesKeepaliveDisabledByDefault(@TempDir Path tempDir) {
        try (HikariDataSource dataSource = create(tempDir, PoolSettings.defaults())) {
            assertEquals(0, dataSource.getKeepaliveTime());
        }
    }
}
