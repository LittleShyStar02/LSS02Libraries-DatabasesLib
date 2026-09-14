package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException;

public class SqliteDdlGenerator extends AbstractDdlGenerator {

    @Override
    protected String mapType(String tableName, ColumnSchema column) {
        switch (TypeMapping.toLogicalType(tableName, column)) {
            case INTEGER:
            case BIGINT:
                return "INTEGER";
            case VARCHAR:
            case TEXT:
            case DATE:
            case TIMESTAMP:
                return "TEXT";
            case BOOLEAN:
                return "INTEGER";
            case DECIMAL:
                return "NUMERIC";
            default:
                throw new UnsupportedTypeMappingException(tableName, column.getName(), column.getTypeName());
        }
    }

    @Override
    protected String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }
}
