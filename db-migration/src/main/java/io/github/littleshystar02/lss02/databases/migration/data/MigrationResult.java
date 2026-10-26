package io.github.littleshystar02.lss02.databases.migration.data;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Summary of a data migration: how many rows were copied for each table and how long it took.
 *
 * <p>It is filled by {@link io.github.littleshystar02.lss02.databases.migration.DatabaseMigrator}
 * while the migration runs and handed to the caller at the end. Instances are not thread-safe.
 */
@Getter
public class MigrationResult {

    /** Number of rows copied, keyed by table name, in migration order. */
    private final Map<String, Long> rowsCopiedPerTable = new LinkedHashMap<>();
    /** Total duration of the migration in milliseconds. */
    private long durationMillis;

    /**
     * Records the outcome for one table, replacing any earlier value for the same name.
     *
     * @param tableName the table that was copied
     * @param rowsCopied the number of rows copied
     */
    public void recordTable(String tableName, long rowsCopied) {
        rowsCopiedPerTable.put(tableName, rowsCopied);
    }

    /**
     * Sets the total duration of the migration.
     *
     * @param durationMillis the duration in milliseconds
     */
    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    /**
     * Sums the rows copied over all tables.
     *
     * @return the total number of rows copied
     */
    public long getTotalRowsCopied() {
        return rowsCopiedPerTable.values().stream().mapToLong(Long::longValue).sum();
    }
}
