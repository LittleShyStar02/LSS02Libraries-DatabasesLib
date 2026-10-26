package io.github.littleshystar02.lss02.databases.migration.data;

import io.github.littleshystar02.lss02.databases.migration.DatabaseMigrationException;
import io.github.littleshystar02.lss02.databases.migration.schema.TableSchema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

/**
 * Copies all rows of one table from a source connection to a target connection.
 *
 * <p>Rows are read with a single {@code SELECT}, inserted with a prepared statement in batches, and
 * each batch is committed on its own, so memory use is bounded by the batch size on the target side.
 * Columns are matched by position and values are passed through {@code getObject}/{@code setObject}
 * without conversion.
 */
public final class DataCopier {

    /** Not instantiable: this class only holds static helpers. */
    private DataCopier() {
    }

    /**
     * Copies one table and reports progress.
     *
     * <p>The target connection is switched to manual commit for the duration of the copy and its original
     * auto-commit mode is restored afterwards, even on failure. The listener is notified when the table
     * starts, after every committed batch, when the table completes, or when an error occurs.
     *
     * @param source the connection to read from
     * @param target the connection to write to; the table must already exist there
     * @param table the structure of the table, which defines the columns and their order
     * @param batchSize the number of rows per insert batch and commit
     * @param listener receives the progress events
     * @return the number of rows copied
     * @throws DatabaseMigrationException if the target cannot be prepared or the copy fails
     */
    public static long copy(Connection source, Connection target, TableSchema table, int batchSize, MigrationProgressListener listener) {
        String columns = table.getColumns().stream().map(c -> c.getName()).collect(Collectors.joining(", "));
        String selectSql = "SELECT " + columns + " FROM " + table.getName();
        String insertSql = "INSERT INTO " + table.getName() + " (" + columns + ") VALUES ("
                + table.getColumns().stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";

        boolean originalAutoCommit;
        try {
            originalAutoCommit = target.getAutoCommit();
            target.setAutoCommit(false);
        } catch (SQLException e) {
            throw new DatabaseMigrationException("Could not prepare the target connection for table '" + table.getName() + "'", e);
        }

        listener.onTableStarted(table.getName());
        long totalRows = 0;
        try (Statement selectStatement = source.createStatement();
             ResultSet resultSet = selectStatement.executeQuery(selectSql);
             PreparedStatement insertStatement = target.prepareStatement(insertSql)) {

            int batched = 0;
            while (resultSet.next()) {
                for (int i = 0; i < table.getColumns().size(); i++) {
                    insertStatement.setObject(i + 1, resultSet.getObject(i + 1));
                }
                insertStatement.addBatch();
                batched++;
                totalRows++;

                if (batched >= batchSize) {
                    insertStatement.executeBatch();
                    target.commit();
                    listener.onBatchCopied(table.getName(), batched);
                    batched = 0;
                }
            }
            if (batched > 0) {
                insertStatement.executeBatch();
                target.commit();
                listener.onBatchCopied(table.getName(), batched);
            }

            listener.onTableCompleted(table.getName(), totalRows);
            return totalRows;
        } catch (SQLException e) {
            listener.onError(table.getName(), e);
            throw new DatabaseMigrationException("Could not copy data for table '" + table.getName() + "'", e);
        } finally {
            try {
                target.setAutoCommit(originalAutoCommit);
            } catch (SQLException ignored) {
                // best-effort restore; the connection is likely being closed by the caller anyway
            }
        }
    }
}
