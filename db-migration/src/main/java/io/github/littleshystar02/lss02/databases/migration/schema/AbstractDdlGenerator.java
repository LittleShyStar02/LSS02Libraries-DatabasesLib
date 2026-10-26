package io.github.littleshystar02.lss02.databases.migration.schema;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared implementation of {@link DdlGenerator}: it lays out the {@code CREATE TABLE} statement and
 * leaves the engine-specific parts to subclasses.
 *
 * <p>Subclasses decide how an identifier is quoted ({@link #quoteIdentifier}) and how a logical
 * column type is written in their dialect ({@link #mapType}). Only column names, types and nullability
 * are generated: no keys, indexes or defaults.
 */
public abstract class AbstractDdlGenerator implements DdlGenerator {

    /**
     * Builds a {@code CREATE TABLE} statement with one line per column, marking non-nullable columns
     * {@code NOT NULL}.
     *
     * @param table the table structure to reproduce
     * @return the DDL statement, without trailing semicolon
     * @throws io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException if a
     *         column type cannot be translated
     */
    @Override
    public String generateCreateTable(TableSchema table) {
        List<String> columnDefinitions = new ArrayList<>();
        for (ColumnSchema column : table.getColumns()) {
            String nullability = column.isNullable() ? "" : " NOT NULL";
            columnDefinitions.add(quoteIdentifier(column.getName()) + " " + mapType(table.getName(), column) + nullability);
        }
        return "CREATE TABLE " + quoteIdentifier(table.getName()) + " (\n  "
                + String.join(",\n  ", columnDefinitions) + "\n)";
    }

    /**
     * Quotes a table or column name for the dialect. The default leaves it unquoted.
     *
     * @param identifier the raw name
     * @return the name as it must appear in the SQL
     */
    protected String quoteIdentifier(String identifier) {
        return identifier;
    }

    /**
     * Translates the type of a column into the dialect's type name, including size or precision.
     *
     * @param tableName the table that contains the column, used in error reports
     * @param column the column to translate
     * @return the type declaration, for example {@code VARCHAR(255)}
     * @throws io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException if the
     *         type cannot be represented
     */
    protected abstract String mapType(String tableName, ColumnSchema column);

    /**
     * Gives the column size when it is known and positive, otherwise a fallback.
     *
     * @param column the column
     * @param defaultValue the value to use when the size is missing or not positive
     * @return the size to declare
     */
    protected static int sizeOrDefault(ColumnSchema column, int defaultValue) {
        return column.getSize() != null && column.getSize() > 0 ? column.getSize() : defaultValue;
    }

    /**
     * Gives the number of decimal digits of the column when known, otherwise a fallback.
     *
     * @param column the column
     * @param defaultValue the value to use when the digits are missing
     * @return the number of decimal digits to declare
     */
    protected static int digitsOrDefault(ColumnSchema column, int defaultValue) {
        return column.getDecimalDigits() != null ? column.getDecimalDigits() : defaultValue;
    }
}
