// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException;

/**
 * {@link DdlGenerator} for MySQL: identifiers quoted with the MySQL quote character, {@code DECIMAL}
 * for exact numbers and {@code LONGBLOB} for binary data. {@link MariaDbDdlGenerator} reuses it.
 */
public class MySqlDdlGenerator extends AbstractDdlGenerator {

    /** Longest {@code VARCHAR} that fits a utf8mb4 row; longer columns become {@code LONGTEXT}. */
    private static final int MAX_VARCHAR = 16_383;
    /** Longest {@code VARCHAR} that can be indexed within the InnoDB key limit (3072 bytes, utf8mb4). */
    private static final int MAX_KEY_VARCHAR = 768;
    /** The MySQL identifier quote character, taken from its code point. */
    private static final String QUOTE = new String(Character.toChars(96));

    @Override
    protected String mapType(String tableName, ColumnSchema column) {
        switch (TypeMapping.toLogicalType(tableName, column)) {
            case SMALLINT:
                return "SMALLINT";
            case INTEGER:
                return "INT";
            case BIGINT:
                return "BIGINT";
            case REAL:
                return "FLOAT";
            case DOUBLE:
                return "DOUBLE";
            case VARCHAR:
                int size = sizeOrDefault(column, 255);
                return size > MAX_VARCHAR ? "LONGTEXT" : "VARCHAR(" + size + ")";
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
                int precision = Math.min(sizeOrDefault(column, 18), 65);
                int scale = Math.min(digitsOrDefault(column, 2), Math.min(30, precision));
                return "DECIMAL(" + precision + "," + scale + ")";
            case BLOB:
                return "LONGBLOB";
            default:
                throw new UnsupportedTypeMappingException(tableName, column.getName(), column.getTypeName());
        }
    }

    @Override
    protected boolean isKeyColumnSafe(String tableName, ColumnSchema column) {
        if (!super.isKeyColumnSafe(tableName, column)) {
            return false;
        }
        return TypeMapping.toLogicalType(tableName, column) != LogicalType.VARCHAR
                || sizeOrDefault(column, 255) <= MAX_KEY_VARCHAR;
    }

    @Override
    protected String quoteIdentifier(String identifier) {
        return quoteWith(identifier, QUOTE);
    }
}
