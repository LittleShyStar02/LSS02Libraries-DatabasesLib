package io.github.littleshystar02.lss02.databases.migration.data;

public interface MigrationProgressListener {

    MigrationProgressListener NO_OP = new MigrationProgressListener() {
    };

    default void onTableStarted(String tableName) {
    }

    default void onBatchCopied(String tableName, int rowCount) {
    }

    default void onTableCompleted(String tableName, long totalRows) {
    }

    default void onError(String tableName, Exception error) {
    }
}
