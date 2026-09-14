package io.github.littleshystar02.lss02.databases.migration.data;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
public class MigrationResult {

    private final Map<String, Long> rowsCopiedPerTable = new LinkedHashMap<>();
    private long durationMillis;

    public void recordTable(String tableName, long rowsCopied) {
        rowsCopiedPerTable.put(tableName, rowsCopied);
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public long getTotalRowsCopied() {
        return rowsCopiedPerTable.values().stream().mapToLong(Long::longValue).sum();
    }
}
