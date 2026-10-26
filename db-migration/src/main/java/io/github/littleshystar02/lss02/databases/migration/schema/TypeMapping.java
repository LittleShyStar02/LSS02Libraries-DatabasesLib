package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException;

import java.sql.Types;

/** Translates JDBC column types into engine-neutral {@link LogicalType} values. */
public final class TypeMapping {

    /** Not instantiable: this class only holds static helpers. */
    private TypeMapping() {
    }

    /**
     * Maps the JDBC type code of a column to a logical type: small and regular integers to
     * {@code INTEGER}, character types to {@code VARCHAR}, long text and CLOB to {@code TEXT}, boolean
     * and bit to {@code BOOLEAN}, and decimal and numeric to {@code DECIMAL}.
     *
     * @param tableName the table that contains the column, used in the error report
     * @param column the column to classify
     * @return the logical type
     * @throws UnsupportedTypeMappingException if the JDBC type is not supported, for example binary data
     */
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
