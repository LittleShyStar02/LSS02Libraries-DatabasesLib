// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.it;

import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

/** The server scenarios on MySQL 8.4. */
@Testcontainers
class MySqlServerIT extends AbstractServerIntegrationTest {

    @Container
    private static final MySQLContainer<?> SERVER = new MySQLContainer<>("mysql:8.4");

    @Override
    JdbcDatabaseContainer<?> container() {
        return SERVER;
    }

    @Override
    DatabaseType type() {
        return DatabaseType.MYSQL;
    }

    @Override
    int containerPort() {
        return MySQLContainer.MYSQL_PORT;
    }

    @Override
    Map<String, String> options() {
        // The container uses a self-signed certificate and the default caching_sha2_password plugin.
        return Map.of("allowPublicKeyRetrieval", "true", "sslMode", "DISABLED");
    }
}
