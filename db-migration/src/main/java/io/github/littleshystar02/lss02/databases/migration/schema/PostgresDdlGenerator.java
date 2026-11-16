// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException;

/**
 * {@link DdlGenerator} for PostgreSQL: double-quoted identifiers, {@code NUMERIC} for decimals and
 * {@code BYTEA} for binary data.
 */
public class PostgresDdlGenerator extends AbstractDdlGenerator {

    /** Largest length PostgreSQL accepts for {@code VARCHAR(n)}. */
    private static final int MAX_VARCHAR = 10_485_760;

    @Override
    protected String mapType(String tableName, ColumnSchema column) {
        switch (TypeMapping.toLogicalType(tableName, column)) {
            case SMALLINT:
                return "SMALLINT";
            case INTEGER:
                return "INTEGER";
            case BIGINT:
                return "BIGINT";
            case REAL:
                return "REAL";
            case DOUBLE:
                return "DOUBLE PRECISION";
            case VARCHAR:
                int size = sizeOrDefault(column, 255);
                return size > MAX_VARCHAR ? "TEXT" : "VARCHAR(" + size + ")";
            case TEXT:
                return "TEXT";
            case BOOLEAN:
                return "BOOLEAN";
            case DATE:
                return "DATE";
            case TIME:
                return "TIME";
            case TIMESTAMP:
                return "TIMESTAMP";
            case DECIMAL:
                int precision = Math.min(sizeOrDefault(column, 18), 1000);
                int scale = Math.min(digitsOrDefault(column, 2), precision);
                return "NUMERIC(" + precision + "," + scale + ")";
            case BLOB:
                return "BYTEA";
            default:
                throw new UnsupportedTypeMappingException(tableName, column.getName(), column.getTypeName());
        }
    }

    @Override
    protected String quoteIdentifier(String identifier) {
        return quoteWith(identifier, "\"");
    }
}
