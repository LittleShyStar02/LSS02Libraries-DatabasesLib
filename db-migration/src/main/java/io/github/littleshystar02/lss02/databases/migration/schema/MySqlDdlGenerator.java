package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException;

/** DDL generator for MySQL, with identifiers quoted in backticks. Also used by MariaDB. */
public class MySqlDdlGenerator extends AbstractDdlGenerator {

    /**
     * Maps a logical type to MySQL: {@code INT}, {@code BIGINT}, {@code VARCHAR(n)} (default 255),
     * {@code TEXT}, {@code BOOLEAN}, {@code DATE}, {@code TIMESTAMP} and {@code DECIMAL(p,s)} (default
     * 18,2).
     *
     * @param tableName the table that contains the column
     * @param column the column to translate
     * @return the MySQL type declaration
     * @throws UnsupportedTypeMappingException if the JDBC type has no logical type
     */
    @Override
    protected String mapType(String tableName, ColumnSchema column) {
        switch (TypeMapping.toLogicalType(tableName, column)) {
            case INTEGER:
                return "INT";
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
                return "DECIMAL(" + sizeOrDefault(column, 18) + "," + digitsOrDefault(column, 2) + ")";
            default:
                throw new UnsupportedTypeMappingException(tableName, column.getName(), column.getTypeName());
        }
    }

    /**
     * Quotes an identifier with backticks.
     *
     * @param identifier the raw name
     * @return the quoted name
     */
    @Override
    protected String quoteIdentifier(String identifier) {
        return "`" + identifier + "`";
    }
}
