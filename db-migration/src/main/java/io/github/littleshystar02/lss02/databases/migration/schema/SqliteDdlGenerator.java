package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException;

/**
 * DDL generator for SQLite, with identifiers quoted in double quotes.
 *
 * <p>SQLite has few storage classes, so types are collapsed: integers and booleans become
 * {@code INTEGER}, text, dates and timestamps become {@code TEXT} and decimals become {@code NUMERIC}.
 * Sizes are not declared.
 */
public class SqliteDdlGenerator extends AbstractDdlGenerator {

    /**
     * Maps a logical type to one of the SQLite storage classes.
     *
     * @param tableName the table that contains the column
     * @param column the column to translate
     * @return {@code INTEGER}, {@code TEXT} or {@code NUMERIC}
     * @throws UnsupportedTypeMappingException if the JDBC type has no logical type
     */
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
