package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException;

public class PostgresDdlGenerator extends AbstractDdlGenerator {

    @Override
    protected String mapType(String tableName, ColumnSchema column) {
        switch (TypeMapping.toLogicalType(tableName, column)) {
            case INTEGER:
                return "INTEGER";
            case BIGINT:
                return "BIGINT";
            case VARCHAR:
                return "VARCHAR(" + sizeOrDefault(column, 255) + ")";
            case TEXT:
                return "TEXT";
            case BOOLEAN:
                return "BOOLEAN";
            case DATE:
                return "DATE";
            case TIMESTAMP:
                return "TIMESTAMP";
            case DECIMAL:
                return "NUMERIC(" + sizeOrDefault(column, 18) + "," + digitsOrDefault(column, 2) + ")";
            default:
                throw new UnsupportedTypeMappingException(tableName, column.getName(), column.getTypeName());
        }
    }

    @Override
    protected String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }
}
