package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException;

/** DDL generator for PostgreSQL, with identifiers quoted in double quotes. */
public class PostgresDdlGenerator extends AbstractDdlGenerator {

    /**
     * Maps a logical type to PostgreSQL: {@code INTEGER}, {@code BIGINT}, {@code VARCHAR(n)} (default
     * 255), {@code TEXT}, {@code BOOLEAN}, {@code DATE}, {@code TIMESTAMP} and {@code NUMERIC(p,s)}
     * (default 18,2).
     *
     * @param tableName the table that contains the column
     * @param column the column to translate
     * @return the PostgreSQL type declaration
     * @throws UnsupportedTypeMappingException if the JDBC type has no logical type
     */
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

    /**
     * Quotes an identifier with double quotes.
     *
     * @param identifier the raw name
     * @return the quoted name
     */
    @Override
    protected String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }
}
