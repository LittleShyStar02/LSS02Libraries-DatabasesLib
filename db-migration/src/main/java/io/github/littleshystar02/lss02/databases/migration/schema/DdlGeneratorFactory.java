package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.migration.DatabaseMigrationException;

public final class DdlGeneratorFactory {

    private DdlGeneratorFactory() {
    }

    public static DdlGenerator forType(DatabaseType type) {
        switch (type) {
            case MYSQL:
                return new MySqlDdlGenerator();
            case MARIADB:
                return new MariaDbDdlGenerator();
            case POSTGRESQL:
                return new PostgresDdlGenerator();
            case SQLITE:
                return new SqliteDdlGenerator();
            default:
                throw new DatabaseMigrationException("No DDL generator available for database type " + type);
        }
    }
}
