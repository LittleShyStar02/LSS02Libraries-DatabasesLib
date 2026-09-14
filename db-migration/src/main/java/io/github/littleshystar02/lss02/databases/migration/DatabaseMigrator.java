package io.github.littleshystar02.lss02.databases.migration;

import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.migration.data.DataCopier;
import io.github.littleshystar02.lss02.databases.migration.data.MigrationProgressListener;
import io.github.littleshystar02.lss02.databases.migration.data.MigrationResult;
import io.github.littleshystar02.lss02.databases.migration.schema.DdlGenerator;
import io.github.littleshystar02.lss02.databases.migration.schema.DdlGeneratorFactory;
import io.github.littleshystar02.lss02.databases.migration.schema.SchemaIntrospector;
import io.github.littleshystar02.lss02.databases.migration.schema.TableSchema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public final class DatabaseMigrator {

    private DatabaseMigrator() {
    }

    public static void migrateSchema(DataSource source, DataSource target, DatabaseType targetType, List<String> tableNames) {
        DdlGenerator ddlGenerator = DdlGeneratorFactory.forType(targetType);
        try (Connection sourceConnection = source.getConnection();
             Connection targetConnection = target.getConnection()) {
            for (TableSchema table : SchemaIntrospector.introspect(sourceConnection, tableNames)) {
                String ddl = ddlGenerator.generateCreateTable(table);
                try (Statement statement = targetConnection.createStatement()) {
                    statement.execute(ddl);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseMigrationException("Could not migrate schema", e);
        }
    }

    public static MigrationResult migrateData(DataSource source, DataSource target, List<String> tableNames, int batchSize) {
        return migrateData(source, target, tableNames, batchSize, MigrationProgressListener.NO_OP);
    }

    public static MigrationResult migrateData(
            DataSource source, DataSource target, List<String> tableNames, int batchSize, MigrationProgressListener listener) {
        long start = System.currentTimeMillis();
        MigrationResult result = new MigrationResult();
        try (Connection sourceConnection = source.getConnection();
             Connection targetConnection = target.getConnection()) {
            for (TableSchema table : SchemaIntrospector.introspect(sourceConnection, tableNames)) {
                long rows = DataCopier.copy(sourceConnection, targetConnection, table, batchSize, listener);
                result.recordTable(table.getName(), rows);
            }
        } catch (SQLException e) {
            throw new DatabaseMigrationException("Could not migrate data", e);
        }
        result.setDurationMillis(System.currentTimeMillis() - start);
        return result;
    }
}
