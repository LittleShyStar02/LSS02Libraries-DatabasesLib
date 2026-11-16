// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration.it;

import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The migration scenarios against MariaDB 11.4. */
@Testcontainers
class MariaDbMigrationIT extends AbstractMigrationIntegrationTest {

    @Container
    private static final MariaDBContainer<?> SERVER = new MariaDBContainer<>("mariadb:11.4");

    @Override
    JdbcDatabaseContainer<?> container() {
        return SERVER;
    }

    @Override
    DatabaseType type() {
        return DatabaseType.MARIADB;
    }
}
