// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration;

import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import io.github.littleshystar02.lss02.databases.migration.data.DataCopier;
import io.github.littleshystar02.lss02.databases.migration.data.MigrationProgressListener;
import io.github.littleshystar02.lss02.databases.migration.data.MigrationResult;
import io.github.littleshystar02.lss02.databases.migration.data.TableCommittedException;
import io.github.littleshystar02.lss02.databases.migration.schema.DdlGenerator;
import io.github.littleshystar02.lss02.databases.migration.schema.DdlGeneratorFactory;
import io.github.littleshystar02.lss02.databases.migration.schema.SchemaIntrospector;
import io.github.littleshystar02.lss02.databases.migration.schema.TableSchema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
     * exists on the target. If any table fails, the tables created by this call are dropped again, so the
     * call can be retried. If a drop fails too, the exception message lists the tables still left on the target
     * and the drop failures are attached as suppressed exceptions.
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
            List<TableSchema> created = new ArrayList<>();
            String currentTable = null;
            try {
                for (TableSchema table : SchemaIntrospector.introspect(sourceConnection, tableNames)) {
                    currentTable = table.getName();
                    String ddl = ddlGenerator.generateCreateTable(table);
                    try (Statement statement = targetConnection.createStatement()) {
                        statement.execute(ddl);
                    }
                    created.add(table);
                }
            } catch (SQLException | RuntimeException e) {
                List<String> remaining = dropCreatedTables(targetConnection, ddlGenerator, created, e);
                String compensation = remaining.isEmpty()
                        ? "the tables created so far were dropped again"
                        : "the compensation FAILED, these tables are still on the target and must be dropped"
                                + " manually before a retry: " + remaining;
                if (e instanceof SQLException) {
                    throw new DatabaseMigrationException("Could not create table '" + currentTable
                            + "' on the target; " + compensation, e);
                }
                if (!remaining.isEmpty()) {
                    throw new DatabaseMigrationException(e.getMessage() + "; " + compensation, e);
                }
                throw (RuntimeException) e;
            }
        } catch (SQLException e) {
            throw new DatabaseMigrationException("Could not migrate schema", e);
        }
    }

    /**
     * Drops the tables created by a schema migration that failed, so a retry starts from a clean target.
     * Failures while dropping are attached to the original error as suppressed exceptions.
     *
     * @param connection the target connection
     * @param ddlGenerator the dialect used to build the statements
     * @param created the tables created so far, in creation order
     * @param original the failure that aborted the migration
     * @return the names of the tables that could not be dropped and are still on the target, empty when
     *         the compensation fully succeeded
     */
    private static List<String> dropCreatedTables(Connection connection, DdlGenerator ddlGenerator,
                                                  List<TableSchema> created, Exception original) {
        List<String> remaining = new ArrayList<>();
        for (int i = created.size() - 1; i >= 0; i--) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(ddlGenerator.generateDropTable(created.get(i)));
            } catch (SQLException dropFailure) {
                remaining.add(created.get(i).getName());
                original.addSuppressed(dropFailure);
            }
        }
        return remaining;
    }

    /**
     * Copies the rows of the given tables without progress reporting
     * (see {@link #migrateData(DataSource, DataSource, List, int, MigrationProgressListener)}).
     *
     * @param source the database to read from
     * @param target the database to write to, where the tables must already exist
     * @param tableNames the names of the tables to copy
     * @param batchSize the number of rows inserted per batch
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
     * with compatible columns (see {@link #migrateSchema}). Each table is written in one transaction (see
     * {@link DataCopier}): a failure rolls back the failing table, while the tables completed before it
     * stay committed and are named in the exception message.
     *
     * @param source the database to read from
     * @param target the database to write to
     * @param tableNames the names of the tables to copy
     * @param batchSize the number of rows inserted per batch
     * @param listener receives progress events during the copy
     * @return the rows copied per table and the total duration
     * @throws DatabaseMigrationException if a table cannot be read or written
     */
    public static MigrationResult migrateData(
            DataSource source, DataSource target, List<String> tableNames, int batchSize, MigrationProgressListener listener) {
        return migrateData(source, target, tableNames, batchSize, listener, false);
    }

    /**
     * Copies the rows of the given tables, optionally leaving tables that already hold rows untouched.
     *
     * <p>With {@code skipNonEmptyTargets} a re-run after a partial migration resumes where the previous
     * one stopped: tables that were completed earlier are skipped (reported with 0 rows) instead of
     * being duplicated or failing on their primary key.
     *
     * @param source the database to read from
     * @param target the database to write to
     * @param tableNames the names of the tables to copy
     * @param batchSize the number of rows inserted per batch
     * @param listener receives progress events during the copy
     * @param skipNonEmptyTargets {@code true} to skip target tables that already contain rows
     * @return the rows copied per table and the total duration
     * @throws DatabaseMigrationException if a table cannot be read or written; the message names the
     *         failing table and the tables already completed
     */
    public static MigrationResult migrateData(
            DataSource source, DataSource target, List<String> tableNames, int batchSize,
            MigrationProgressListener listener, boolean skipNonEmptyTargets) {
        long start = System.currentTimeMillis();
        MigrationResult result = new MigrationResult();
        try (Connection sourceConnection = source.getConnection();
             Connection targetConnection = target.getConnection()) {
            for (TableSchema table : SchemaIntrospector.introspect(sourceConnection, tableNames)) {
                try {
                    long rows = DataCopier.copy(sourceConnection, targetConnection, table, batchSize, listener, skipNonEmptyTargets);
                    result.recordTable(table.getName(), rows);
                } catch (TableCommittedException e) {
                    // The table is durable even though the listener failed: count it as completed.
                    result.recordTable(e.getTableName(), e.getRowsCopied());
                    throw new DatabaseMigrationException(e.getMessage() + "; tables already completed: "
                            + result.getRowsCopiedPerTable().keySet(), e.getCause());
                } catch (DatabaseMigrationException e) {
                    throw new DatabaseMigrationException(e.getMessage() + "; tables already completed: "
                            + result.getRowsCopiedPerTable().keySet(), e.getCause() != null ? e.getCause() : e);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseMigrationException("Could not migrate data", e);
        }
        result.setDurationMillis(System.currentTimeMillis() - start);
        return result;
    }
}
