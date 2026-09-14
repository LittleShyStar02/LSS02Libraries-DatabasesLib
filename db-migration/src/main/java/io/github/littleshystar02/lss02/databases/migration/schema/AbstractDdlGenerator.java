package io.github.littleshystar02.lss02.databases.migration.schema;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractDdlGenerator implements DdlGenerator {

    @Override
    public String generateCreateTable(TableSchema table) {
        List<String> columnDefinitions = new ArrayList<>();
        for (ColumnSchema column : table.getColumns()) {
            String nullability = column.isNullable() ? "" : " NOT NULL";
            columnDefinitions.add(quoteIdentifier(column.getName()) + " " + mapType(table.getName(), column) + nullability);
        }
        return "CREATE TABLE " + quoteIdentifier(table.getName()) + " (\n  "
                + String.join(",\n  ", columnDefinitions) + "\n)";
    }

    protected String quoteIdentifier(String identifier) {
        return identifier;
    }

    protected abstract String mapType(String tableName, ColumnSchema column);

    protected static int sizeOrDefault(ColumnSchema column, int defaultValue) {
        return column.getSize() != null && column.getSize() > 0 ? column.getSize() : defaultValue;
    }

    protected static int digitsOrDefault(ColumnSchema column, int defaultValue) {
        return column.getDecimalDigits() != null ? column.getDecimalDigits() : defaultValue;
    }
}
