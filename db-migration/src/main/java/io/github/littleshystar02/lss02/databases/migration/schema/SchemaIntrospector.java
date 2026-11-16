// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.migration.DatabaseMigrationException;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Reads table structures from a live connection through {@link DatabaseMetaData}.
 *
 * <p>A table name must designate an existing table or view: it is resolved against the database
 * metadata (exact match first, then a unique case-insensitive match) before it is used anywhere else,
 * and the resolved name is the one stored in the resulting {@link TableSchema}.
 */
public final class SchemaIntrospector {

    /** Not instantiable: this class only holds static helpers. */
    private SchemaIntrospector() {
    }

    /**
     * Reads the columns and the primary key of one table.
     *
     * @param connection the connection to the database that holds the table
     * @param tableName the table to read; it must exist
     * @return the table structure; sizes and scales the driver reports as SQL NULL are {@code null}
     * @throws DatabaseMigrationException if the table does not exist, has no columns, or the metadata
     *         cannot be read
     */
    public static TableSchema introspect(Connection connection, String tableName) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            String resolved = resolveTableName(metaData, catalog, tableName);

            List<ColumnSchema> columns = new ArrayList<>();
            // The name argument is a LIKE pattern; rows of other tables matched by wildcards are filtered out.
            try (ResultSet resultSet = metaData.getColumns(catalog, null, resolved, null)) {
                while (resultSet.next()) {
                    String rowTable = resultSet.getString("TABLE_NAME");
                    if (rowTable != null && !rowTable.equals(resolved)) {
                        continue;
                    }
                    columns.add(ColumnSchema.builder()
                            .name(resultSet.getString("COLUMN_NAME"))
                            .jdbcType(resultSet.getInt("DATA_TYPE"))
                            .typeName(resultSet.getString("TYPE_NAME"))
                            .size(nullableInt(resultSet, "COLUMN_SIZE"))
                            .decimalDigits(nullableInt(resultSet, "DECIMAL_DIGITS"))
                            .nullable("YES".equalsIgnoreCase(resultSet.getString("IS_NULLABLE")))
                            .build());
                }
            }
            if (columns.isEmpty()) {
                throw new DatabaseMigrationException("Table '" + tableName + "' was not found or has no columns");
            }
            return new TableSchema(resolved, columns, readPrimaryKey(metaData, catalog, resolved));
        } catch (SQLException e) {
            throw new DatabaseMigrationException("Could not introspect table '" + tableName + "'", e);
        }
    }

    /**
     * Reads several tables in the given order.
     *
     * @param connection the connection to the database that holds the tables
     * @param tableNames the tables to read
     * @return their structures, in the same order
     * @throws DatabaseMigrationException if any table cannot be read
     */
    public static List<TableSchema> introspect(Connection connection, List<String> tableNames) {
        List<TableSchema> tables = new ArrayList<>();
        for (String tableName : tableNames) {
            tables.add(introspect(connection, tableName));
        }
        return tables;
    }

    /**
     * Finds the real name of a table in the database metadata.
     *
     * @param metaData the metadata of the connection
     * @param catalog the current catalog
     * @param requested the name given by the caller
     * @return the name as the database stores it
     * @throws SQLException if the metadata cannot be read
     * @throws DatabaseMigrationException if no table, or more than one case-insensitive candidate, matches
     */
    private static String resolveTableName(DatabaseMetaData metaData, String catalog, String requested) throws SQLException {
        if (requested == null || requested.isBlank()) {
            throw new DatabaseMigrationException("A table name must not be empty");
        }
        // The metadata pattern is matched case-sensitively by several engines (H2, PostgreSQL), so a
        // request that differs only in case finds nothing: the second pass lists every table and
        // compares the names itself.
        for (String pattern : new String[] {requested, null}) {
            String caseInsensitive = null;
            int candidates = 0;
            try (ResultSet tables = metaData.getTables(catalog, null, pattern, new String[] {"TABLE", "VIEW"})) {
                while (tables.next()) {
                    String name = tables.getString("TABLE_NAME");
                    if (requested.equals(name)) {
                        return name;
                    }
                    if (requested.equalsIgnoreCase(name)) {
                        caseInsensitive = name;
                        candidates++;
                    }
                }
            }
            if (candidates == 1) {
                return caseInsensitive;
            }
            if (candidates > 1) {
                break;
            }
        }
        throw new DatabaseMigrationException("Table '" + requested + "' was not found or has no columns");
    }

    /**
     * Reads the primary key column names of a table in key order.
     *
     * @param metaData the metadata of the connection
     * @param catalog the current catalog
     * @param table the resolved table name
     * @return the key columns, empty when there is no primary key
     * @throws SQLException if the metadata cannot be read
     */
    private static List<String> readPrimaryKey(DatabaseMetaData metaData, String catalog, String table) throws SQLException {
        TreeMap<Integer, String> ordered = new TreeMap<>();
        try (ResultSet keys = metaData.getPrimaryKeys(catalog, null, table)) {
            while (keys.next()) {
                ordered.put(keys.getInt("KEY_SEQ"), keys.getString("COLUMN_NAME"));
            }
        }
        return new ArrayList<>(ordered.values());
    }

    /**
     * Reads an integer column keeping SQL NULL as {@code null}.
     *
     * @param resultSet the current row
     * @param column the column label
     * @return the value, or {@code null}
     * @throws SQLException if the value cannot be read
     */
    private static Integer nullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }
}
