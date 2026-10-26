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

/**
 * Copies tables between two databases, possibly of different engines (for example MySQL to SQLite).
 *
 * <p>Migration is a two-step process the caller drives explicitly: {@link #migrateSchema} recreates
 * the table structures on the target, then {@code migrateData} copies the rows. Tables are read
 * through JDBC metadata and column types are translated through a neutral logical type, so no
 * engine-specific SQL is needed on the source side. Indexes, keys and constraints are not copied.
 */
public final class DatabaseMigrator {

    /** Not instantiable: this class only holds static helpers. */
    private DatabaseMigrator() {
    }

    /**
     * Creates the given tables on the target database, using the structure found on the source.
     *
     * <p>Each table is created with a plain {@code CREATE TABLE}, so the call fails if a table already
     * exists on the target.
     *
     * @param source the database that holds the original tables
     * @param target the database that receives the new tables
     * @param targetType the engine of the target, which selects the SQL dialect of the generated DDL
     * @param tableNames the names of the tables to recreate
     * @throws DatabaseMigrationException if a table is missing on the source, a column type is not
     *         supported (see {@link UnsupportedTypeMappingException}) or the DDL fails
     */
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

    /**
     * Copies the rows of the given tables without progress reporting
     * (see {@link #migrateData(DataSource, DataSource, List, int, MigrationProgressListener)}).
     *
     * @param source the database to read from
     * @param target the database to write to, where the tables must already exist
     * @param tableNames the names of the tables to copy
     * @param batchSize the number of rows inserted per batch and per commit
     * @return the rows copied per table and the total duration
     * @throws DatabaseMigrationException if a table cannot be read or written
     */
    public static MigrationResult migrateData(DataSource source, DataSource target, List<String> tableNames, int batchSize) {
        return migrateData(source, target, tableNames, batchSize, MigrationProgressListener.NO_OP);
    }

    /**
     * Copies the rows of the given tables and reports progress to a listener.
     *
     * <p>Tables are copied one after the other, in the given order. The target tables must already exist
     * with compatible columns (see {@link #migrateSchema}). Each batch is committed separately, so a
     * failure leaves the batches committed so far in place.
     *
     * @param source the database to read from
     * @param target the database to write to
     * @param tableNames the names of the tables to copy
     * @param batchSize the number of rows inserted per batch and per commit
     * @param listener receives progress events during the copy
     * @return the rows copied per table and the total duration
     * @throws DatabaseMigrationException if a table cannot be read or written
     */
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
