// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.it;

import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The server scenarios on MariaDB 11.4. */
@Testcontainers
class MariaDbServerIT extends AbstractServerIntegrationTest {

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

    @Override
    int containerPort() {
        return 3306;
    }
}
