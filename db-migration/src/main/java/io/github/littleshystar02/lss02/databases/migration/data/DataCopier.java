// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration.data;

import io.github.littleshystar02.lss02.databases.migration.DatabaseMigrationException;
import io.github.littleshystar02.lss02.databases.migration.schema.TableSchema;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Copies all rows of one table from a source connection to a target connection.
 *
 * <p>Table and column names are quoted with the dialect of each connection, so reserved words, spaces
 * and mixed case work and a hostile name cannot inject SQL. Rows are read with a single {@code SELECT}
 * using a dialect-appropriate fetch size (MySQL and MariaDB stream the rows, PostgreSQL uses a cursor),
 * and inserted with a prepared statement in batches. The whole table is written in one transaction: it
 * is committed only after the last batch, and rolled back if anything fails, so a failed copy never
 * leaves a partial table behind and can simply be retried. Columns are matched by position and values
 * are passed through {@code getObject}/{@code setObject} without conversion, except that {@code Blob}
 * and {@code Clob} values are read fully into {@code byte[]} and {@code String} first.
 */
public final class DataCopier {

    /** Not instantiable: this class only holds static helpers. */
    private DataCopier() {
    }

    /**
     * Copies one table and reports progress.
     *
     * @param source the connection to read from
     * @param target the connection to write to; the table must already exist there
     * @param table the structure of the table, which defines the columns and their order
     * @param batchSize the number of rows per insert batch (also the fetch size where applicable)
     * @param listener receives the progress events
     * @return the number of rows copied
     * @throws DatabaseMigrationException if the target cannot be prepared or the copy fails; the
     *         target transaction is rolled back first
     * @see #copy(Connection, Connection, TableSchema, int, MigrationProgressListener, boolean)
     */
    public static long copy(Connection source, Connection target, TableSchema table, int batchSize, MigrationProgressListener listener) {
        return copy(source, target, table, batchSize, listener, false);
    }

    /**
     * Copies one table and reports progress.
     *
     * <p>The target connection is switched to manual commit for the duration of the copy and its original
     * auto-commit mode is restored afterwards, even on failure. The listener is notified when the table
     * starts, after every written batch, when the table completes, or when an error occurs. Batch
     * notifications are sent before the final commit, so they report rows written, not yet durable.
     *
     * @param source the connection to read from
     * @param target the connection to write to; the table must already exist there
     * @param table the structure of the table, which defines the columns and their order
     * @param batchSize the number of rows per insert batch (also the fetch size where applicable)
     * @param listener receives the progress events
     * @param skipIfTargetNotEmpty if {@code true} and the target table already holds rows, nothing is
     *        copied and {@code 0} is returned, which makes a re-run after a partial migration safe
     * @return the number of rows copied
     * @throws DatabaseMigrationException if the target cannot be prepared or the copy fails; the
     *         target transaction is rolled back first
     */
    public static long copy(Connection source, Connection target, TableSchema table, int batchSize,
                            MigrationProgressListener listener, boolean skipIfTargetNotEmpty) {
        if (batchSize < 1) {
            throw new DatabaseMigrationException("The batch size must be at least 1, was " + batchSize);
        }
        String sourceTable = SqlIdentifiers.quote(source, table.getName());
        String targetTable = SqlIdentifiers.quote(target, table.getName());
        String selectColumns = table.getColumns().stream()
                .map(c -> SqlIdentifiers.quote(source, c.getName())).collect(Collectors.joining(", "));
        String insertColumns = table.getColumns().stream()
                .map(c -> SqlIdentifiers.quote(target, c.getName())).collect(Collectors.joining(", "));
        String selectSql = "SELECT " + selectColumns + " FROM " + sourceTable;
        String insertSql = "INSERT INTO " + targetTable + " (" + insertColumns + ") VALUES ("
                + table.getColumns().stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";

        boolean originalTargetAutoCommit;
        try {
            originalTargetAutoCommit = target.getAutoCommit();
            target.setAutoCommit(false);
        } catch (SQLException e) {
            throw new DatabaseMigrationException("Could not prepare the target connection for table '" + table.getName() + "'", e);
        }

        listener.onTableStarted(table.getName());
        boolean sourceAutoCommitChanged = false;
        boolean originalSourceAutoCommit = true;
        long totalRows = 0;
        try {
            if (skipIfTargetNotEmpty && hasRows(target, targetTable)) {
                target.rollback();
                listener.onTableCompleted(table.getName(), 0);
                return 0;
            }

            try (Statement selectStatement = source.createStatement()) {
                if (needsManualCommitForCursor(source)) {
                    originalSourceAutoCommit = source.getAutoCommit();
                    source.setAutoCommit(false);
                    sourceAutoCommitChanged = true;
                }
                selectStatement.setFetchSize(fetchSizeFor(source, batchSize));

                try (ResultSet resultSet = selectStatement.executeQuery(selectSql);
                     PreparedStatement insertStatement = target.prepareStatement(insertSql)) {
                    int columnCount = table.getColumns().size();
                    int batched = 0;
                    while (resultSet.next()) {
                        for (int i = 0; i < columnCount; i++) {
                            insertStatement.setObject(i + 1, readValue(resultSet, i + 1));
                        }
                        insertStatement.addBatch();
                        batched++;
                        totalRows++;

                        if (batched >= batchSize) {
                            insertStatement.executeBatch();
                            listener.onBatchCopied(table.getName(), batched);
                            batched = 0;
                        }
                    }
                    if (batched > 0) {
                        insertStatement.executeBatch();
                        listener.onBatchCopied(table.getName(), batched);
                    }
                }
            }
            target.commit();
            listener.onTableCompleted(table.getName(), totalRows);
            return totalRows;
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(target, e);
            listener.onError(table.getName(), e);
            throw new DatabaseMigrationException("Could not copy data for table '" + table.getName()
                    + "' (read " + totalRows + " rows before the failure; the target table was rolled back"
                    + " to its state before this copy)", e);
        } finally {
            if (sourceAutoCommitChanged) {
                try {
                    source.rollback();
                    source.setAutoCommit(originalSourceAutoCommit);
                } catch (SQLException ignored) {
                    // best-effort restore; the connection is likely being closed by the caller anyway
                }
            }
            try {
                target.setAutoCommit(originalTargetAutoCommit);
            } catch (SQLException ignored) {
                // best-effort restore; the connection is likely being closed by the caller anyway
            }
        }
    }

    /**
     * Reads one value of the current row. Large objects some drivers hand out as {@link Blob} or
     * {@link Clob} (they are tied to the source connection) are materialised as {@code byte[]} and
     * {@link String}, which every target driver accepts.
     *
     * @param resultSet the source row
     * @param index the 1-based column index
     * @return the value to bind on the target
     * @throws SQLException if the value cannot be read
     */
    private static Object readValue(ResultSet resultSet, int index) throws SQLException {
        Object value = resultSet.getObject(index);
        if (value instanceof Blob blob) {
            return blob.getBytes(1, (int) blob.length());
        }
        if (value instanceof Clob clob) {
            return clob.getSubString(1, (int) clob.length());
        }
        return value;
    }

    /**
     * Tells whether a table holds at least one row.
     *
     * @param connection the connection to query
     * @param quotedTable the already quoted table name
     * @return {@code true} if a row exists
     * @throws SQLException if the query fails
     */
    private static boolean hasRows(Connection connection, String quotedTable) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(1);
            try (ResultSet rows = statement.executeQuery("SELECT 1 FROM " + quotedTable)) {
                return rows.next();
            }
        }
    }

    /**
     * Tells whether the source engine only honours the fetch size inside a transaction (PostgreSQL).
     *
     * @param source the source connection
     * @return {@code true} for PostgreSQL
     * @throws SQLException if the metadata cannot be read
     */
    private static boolean needsManualCommitForCursor(Connection source) throws SQLException {
        return productName(source).contains("postgres");
    }

    /**
     * Chooses the fetch size for the source query: MySQL and MariaDB only stream rows with
     * {@link Integer#MIN_VALUE}, others use the batch size.
     *
     * @param source the source connection
     * @param batchSize the configured batch size
     * @return the value for {@link Statement#setFetchSize(int)}
     * @throws SQLException if the metadata cannot be read
     */
    private static int fetchSizeFor(Connection source, int batchSize) throws SQLException {
        String product = productName(source);
        if (product.contains("mysql") || product.contains("mariadb")) {
            return Integer.MIN_VALUE;
        }
        return batchSize;
    }

    /**
     * Reads the lower-case database product name of a connection.
     *
     * @param connection the connection
     * @return the product name, empty if the driver reports none
     * @throws SQLException if the metadata cannot be read
     */
    private static String productName(Connection connection) throws SQLException {
        String name = connection.getMetaData().getDatabaseProductName();
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /**
     * Rolls the target back after a failure, attaching a rollback error to the original one.
     *
     * @param target the target connection
     * @param original the failure being handled
     */
    private static void rollbackQuietly(Connection target, Exception original) {
        try {
            target.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
