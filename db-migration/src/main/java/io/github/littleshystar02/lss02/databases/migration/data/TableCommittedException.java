// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration.data;

import io.github.littleshystar02.lss02.databases.migration.DatabaseMigrationException;

/**
 * Signals that a table was copied and committed on the target, but the progress listener failed while
 * being told about the completion. The table is durable: nothing was rolled back and the table must not
 * be copied again. The cause is the exception thrown by the listener.
 */
public class TableCommittedException extends DatabaseMigrationException {

    /** The table that was committed. */
    private final String tableName;
    /** The rows committed for the table. */
    private final long rowsCopied;

    /**
     * Creates the exception.
     *
     * @param tableName the table that was committed
     * @param rowsCopied the rows committed for the table
     * @param cause the failure of the listener
     */
    public TableCommittedException(String tableName, long rowsCopied, Throwable cause) {
        super("Table '" + tableName + "' was copied and committed (" + rowsCopied
                + " rows) but the progress listener failed in onTableCompleted; the table is NOT rolled back", cause);
        this.tableName = tableName;
        this.rowsCopied = rowsCopied;
    }

    /**
     * Returns the committed table.
     *
     * @return the table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Returns the rows committed for the table.
     *
     * @return the row count
     */
    public long getRowsCopied() {
        return rowsCopied;
    }
}
