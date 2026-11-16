// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException;

/**
 * {@link DdlGenerator} for SQLite: double-quoted identifiers and the few storage classes SQLite has
 * (INTEGER, REAL, NUMERIC, TEXT, BLOB).
 */
public class SqliteDdlGenerator extends AbstractDdlGenerator {

    @Override
    protected String mapType(String tableName, ColumnSchema column) {
        switch (TypeMapping.toLogicalType(tableName, column)) {
            case SMALLINT:
            case INTEGER:
            case BIGINT:
            case BOOLEAN:
                return "INTEGER";
            case REAL:
            case DOUBLE:
                return "REAL";
            case VARCHAR:
            case TEXT:
            case DATE:
            case TIME:
            case TIMESTAMP:
                return "TEXT";
            case DECIMAL:
                return "NUMERIC";
            case BLOB:
                return "BLOB";
            default:
                throw new UnsupportedTypeMappingException(tableName, column.getName(), column.getTypeName());
        }
    }

    @Override
    protected boolean isKeyColumnSafe(String tableName, ColumnSchema column) {
        // SQLite indexes any column type, so text and blob keys are fine here.
        return true;
    }

    @Override
    protected String quoteIdentifier(String identifier) {
        return quoteWith(identifier, "\"");
    }
}
