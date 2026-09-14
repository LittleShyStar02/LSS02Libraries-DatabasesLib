package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException;

import java.sql.Types;

public final class TypeMapping {

    private TypeMapping() {
    }

    public static LogicalType toLogicalType(String tableName, ColumnSchema column) {
        switch (column.getJdbcType()) {
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
                return LogicalType.INTEGER;
            case Types.BIGINT:
                return LogicalType.BIGINT;
            case Types.VARCHAR:
            case Types.NVARCHAR:
            case Types.CHAR:
            case Types.NCHAR:
                return LogicalType.VARCHAR;
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.CLOB:
                return LogicalType.TEXT;
            case Types.BOOLEAN:
            case Types.BIT:
                return LogicalType.BOOLEAN;
            case Types.DATE:
                return LogicalType.DATE;
            case Types.TIMESTAMP:
                return LogicalType.TIMESTAMP;
            case Types.DECIMAL:
            case Types.NUMERIC:
                return LogicalType.DECIMAL;
            default:
                throw new UnsupportedTypeMappingException(tableName, column.getName(), column.getTypeName());
        }
    }
}
