package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.migration.DatabaseMigrationException;

/** Chooses the {@link DdlGenerator} that matches a target database engine. */
public final class DdlGeneratorFactory {

    /** Not instantiable: this class only holds static helpers. */
    private DdlGeneratorFactory() {
    }

    /**
     * Creates the generator for an engine.
     *
     * @param type the target database engine
     * @return a new generator for that dialect
     * @throws io.github.littleshystar02.lss02.databases.migration.DatabaseMigrationException if the
     *         engine has no generator
     */
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
