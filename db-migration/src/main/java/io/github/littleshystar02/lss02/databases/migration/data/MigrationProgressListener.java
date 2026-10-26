package io.github.littleshystar02.lss02.databases.migration.data;

/**
 * Receives progress events while table data is being copied, for example to drive a progress bar or a
 * log. Every method has an empty default, so implementations override only what they need.
 */
public interface MigrationProgressListener {

    /** Listener that ignores every event, used when the caller does not need progress. */
    MigrationProgressListener NO_OP = new MigrationProgressListener() {
    };

    /**
     * Called before the first row of a table is copied.
     *
     * @param tableName the table about to be copied
     */
    default void onTableStarted(String tableName) {
    }

    /**
     * Called after a batch has been inserted and committed on the target.
     *
     * @param tableName the table being copied
     * @param rowCount the number of rows in the batch that was just committed
     */
    default void onBatchCopied(String tableName, int rowCount) {
    }

    /**
     * Called after the last batch of a table has been committed.
     *
     * @param tableName the table that was copied
     * @param totalRows the total number of rows copied for the table
     */
    default void onTableCompleted(String tableName, long totalRows) {
    }

    /**
     * Called when copying a table fails, just before the exception is thrown to the caller.
     *
     * @param tableName the table that was being copied
     * @param error the failure
     */
    default void onError(String tableName, Exception error) {
    }
}
