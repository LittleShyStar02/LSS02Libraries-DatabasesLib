// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration.it;

import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The migration scenarios against MySQL 8.4. */
@Testcontainers
class MySqlMigrationIT extends AbstractMigrationIntegrationTest {

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
}
