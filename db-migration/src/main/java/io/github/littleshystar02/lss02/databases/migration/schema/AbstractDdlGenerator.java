// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration.schema;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared implementation of {@link DdlGenerator}: it lays out the {@code CREATE TABLE} statement and
 * leaves the engine-specific parts to subclasses.
 *
 * <p>Subclasses decide how an identifier is quoted ({@link #quoteIdentifier}) and how a logical
 * column type is written in their dialect ({@link #mapType}). Column names, types, nullability and the
 * primary key are generated; indexes, foreign keys, defaults and auto-increment are not. The primary
 * key is omitted when a key column has a type the dialect cannot index safely.
 */
public abstract class AbstractDdlGenerator implements DdlGenerator {

    /**
     * Builds a {@code CREATE TABLE} statement with one line per column, marking non-nullable columns
     * {@code NOT NULL} and adding a {@code PRIMARY KEY} clause when the source table has one that is
     * safe in this dialect.
     *
     * @param table the table structure to reproduce
     * @return the DDL statement, without trailing semicolon
     * @throws io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException if a
     *         column type cannot be translated
     */
    @Override
    public String generateCreateTable(TableSchema table) {
        List<String> definitions = new ArrayList<>();
        for (ColumnSchema column : table.getColumns()) {
            String nullability = column.isNullable() ? "" : " NOT NULL";
            definitions.add(quoteIdentifier(column.getName()) + " " + mapType(table.getName(), column) + nullability);
        }
        String primaryKey = primaryKeyClause(table);
        if (primaryKey != null) {
            definitions.add(primaryKey);
        }
        return "CREATE TABLE " + quoteIdentifier(table.getName()) + " (\n  "
                + String.join(",\n  ", definitions) + "\n)";
    }

    @Override
    public String generateDropTable(TableSchema table) {
        return "DROP TABLE IF EXISTS " + quoteIdentifier(table.getName());
    }

    /**
     * Builds the primary key clause, or {@code null} when the table has no key or a key column is not
     * safe to index.
     *
     * @param table the table structure
     * @return the clause, or {@code null}
     */
    private String primaryKeyClause(TableSchema table) {
        List<String> keyColumns = table.getPrimaryKeyColumns();
        if (keyColumns.isEmpty()) {
            return null;
        }
        List<String> quoted = new ArrayList<>();
        for (String keyName : keyColumns) {
            ColumnSchema column = table.getColumns().stream()
                    .filter(c -> c.getName().equals(keyName)).findFirst().orElse(null);
            if (column == null || !isKeyColumnSafe(table.getName(), column)) {
                return null;
            }
            quoted.add(quoteIdentifier(keyName));
        }
        return "PRIMARY KEY (" + String.join(", ", quoted) + ")";
    }

    /**
     * Tells whether a column can be part of a primary key in this dialect. Large text and binary
     * columns cannot, so the key is dropped instead of producing a statement the server rejects.
     *
     * @param tableName the table that contains the column
     * @param column the key column
     * @return {@code true} if the column may be indexed
     */
    protected boolean isKeyColumnSafe(String tableName, ColumnSchema column) {
        LogicalType type = TypeMapping.toLogicalType(tableName, column);
        return type != LogicalType.TEXT && type != LogicalType.BLOB;
    }

    /**
     * Quotes a table or column name for this dialect, doubling any embedded quote character.
     *
     * @param identifier the raw name
     * @return the quoted name
     */
    protected abstract String quoteIdentifier(String identifier);

    /**
     * Quotes a name with a quote string, doubling embedded occurrences of it.
     *
     * @param identifier the raw name
     * @param quote the quote string, for example a double quote
     * @return the quoted name
     */
    protected static String quoteWith(String identifier, String quote) {
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    /**
     * Writes the SQL type of a column in this dialect.
     *
     * @param tableName the table that contains the column
     * @param column the column to translate
     * @return the type expression, for example {@code VARCHAR(100)}
     * @throws io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException if the
     *         type cannot be represented
     */
    protected abstract String mapType(String tableName, ColumnSchema column);

    /**
     * Gives the declared size of a column, or a default when the driver reported none.
     *
     * @param column the column
     * @param defaultValue the fallback, used for a missing or non-positive size
     * @return the size
     */
    protected static int sizeOrDefault(ColumnSchema column, int defaultValue) {
        return column.getSize() != null && column.getSize() > 0 ? column.getSize() : defaultValue;
    }

    /**
     * Gives the declared scale of a decimal column, or a default when the driver reported none. Zero is
     * a valid scale and is kept.
     *
     * @param column the column
     * @param defaultValue the fallback, used when the scale is unknown or negative
     * @return the scale
     */
    protected static int digitsOrDefault(ColumnSchema column, int defaultValue) {
        return column.getDecimalDigits() != null && column.getDecimalDigits() >= 0 ? column.getDecimalDigits() : defaultValue;
    }
}
