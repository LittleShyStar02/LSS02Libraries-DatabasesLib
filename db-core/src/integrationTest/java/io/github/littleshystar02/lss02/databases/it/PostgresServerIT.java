// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.it;

import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The server scenarios on PostgreSQL 16. */
@Testcontainers
class PostgresServerIT extends AbstractServerIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> SERVER = new PostgreSQLContainer<>("postgres:16-alpine");

    @Override
    JdbcDatabaseContainer<?> container() {
        return SERVER;
    }

    @Override
    DatabaseType type() {
        return DatabaseType.POSTGRESQL;
    }

    @Override
    int containerPort() {
        return PostgreSQLContainer.POSTGRESQL_PORT;
    }
}
