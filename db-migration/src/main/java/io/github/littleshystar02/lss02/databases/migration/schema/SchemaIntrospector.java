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
 * and the resolved catalog, schema and name are the ones stored in the resulting {@link TableSchema}.
 * Only the connection's current schema is searched when the driver reports one; a name that still matches
 * several tables (other case variants, or several schemas when the driver has no current schema) is
 * rejected as ambiguous instead of merging their columns.
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
            String schema = currentSchema(connection);
            TableRef resolved = resolveTableName(metaData, catalog, schema, tableName);

            List<ColumnSchema> columns = new ArrayList<>();
            // The name argument is a LIKE pattern; rows of other tables matched by wildcards are filtered out.
            try (ResultSet resultSet = metaData.getColumns(resolved.catalog(), resolved.schema(), resolved.name(), null)) {
                while (resultSet.next()) {
                    if (!resolved.matches(resultSet.getString("TABLE_CAT"), resultSet.getString("TABLE_SCHEM"),
                            resultSet.getString("TABLE_NAME"))) {
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
            return new TableSchema(resolved.catalog(), resolved.schema(), resolved.name(), columns,
                    readPrimaryKey(metaData, resolved));
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
     * Reads the schema the connection currently uses, so that tables of the same name in other schemas
     * are not mixed in.
     *
     * @param connection the connection
     * @return the current schema, or {@code null} when the driver has no schema concept
     */
    private static String currentSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException | RuntimeException | AbstractMethodError e) {
            return null;
        }
    }

    /**
     * Finds the real catalog, schema and name of a table in the database metadata.
     *
     * @param metaData the metadata of the connection
     * @param catalog the current catalog
     * @param schema the current schema, or {@code null} to search every schema
     * @param requested the name given by the caller
     * @return the table as the database stores it
     * @throws SQLException if the metadata cannot be read
     * @throws DatabaseMigrationException if no table matches, or the name matches several tables
     *         (other schemas or other case variants), which is reported instead of picking one
     */
    private static TableRef resolveTableName(DatabaseMetaData metaData, String catalog, String schema, String requested)
            throws SQLException {
        if (requested == null || requested.isBlank()) {
            throw new DatabaseMigrationException("A table name must not be empty");
        }
        // The metadata pattern is matched case-sensitively by several engines (H2, PostgreSQL), so a
        // request that differs only in case finds nothing: the second pass lists every table and
        // compares the names itself.
        for (String pattern : new String[] {requested, null}) {
            List<TableRef> exact = new ArrayList<>();
            List<TableRef> caseInsensitive = new ArrayList<>();
            try (ResultSet tables = metaData.getTables(catalog, schema, pattern, new String[] {"TABLE", "VIEW"})) {
                while (tables.next()) {
                    String name = tables.getString("TABLE_NAME");
                    TableRef ref = new TableRef(tables.getString("TABLE_CAT"), tables.getString("TABLE_SCHEM"), name);
                    if (requested.equals(name)) {
                        exact.add(ref);
                    } else if (requested.equalsIgnoreCase(name)) {
                        caseInsensitive.add(ref);
                    }
                }
            }
            if (exact.size() == 1) {
                return exact.get(0);
            }
            if (exact.size() > 1) {
                throw ambiguous(requested, exact);
            }
            if (caseInsensitive.size() == 1) {
                return caseInsensitive.get(0);
            }
            if (caseInsensitive.size() > 1) {
                throw ambiguous(requested, caseInsensitive);
            }
        }
        throw new DatabaseMigrationException("Table '" + requested + "' was not found or has no columns");
    }

    /**
     * Builds the exception for a name that designates more than one table.
     *
     * @param requested the name given by the caller
     * @param candidates the tables it matches
     * @return the exception to throw
     */
    private static DatabaseMigrationException ambiguous(String requested, List<TableRef> candidates) {
        return new DatabaseMigrationException("Table name '" + requested + "' is ambiguous, it matches "
                + candidates + "; use a connection whose current schema holds only one of them");
    }

    /**
     * Reads the primary key column names of a table in key order.
     *
     * @param metaData the metadata of the connection
     * @param table the resolved table
     * @return the key columns, empty when there is no primary key
     * @throws SQLException if the metadata cannot be read
     */
    private static List<String> readPrimaryKey(DatabaseMetaData metaData, TableRef table) throws SQLException {
        TreeMap<Integer, String> ordered = new TreeMap<>();
        try (ResultSet keys = metaData.getPrimaryKeys(table.catalog(), table.schema(), table.name())) {
            while (keys.next()) {
                ordered.put(keys.getInt("KEY_SEQ"), keys.getString("COLUMN_NAME"));
            }
        }
        return new ArrayList<>(ordered.values());
    }

    /**
     * A table located by catalog, schema and name.
     *
     * @param catalog the catalog, or {@code null}
     * @param schema the schema, or {@code null}
     * @param name the table name
     */
    private record TableRef(String catalog, String schema, String name) {

        /**
         * Tells whether a metadata row belongs to this table; null parts reported by the driver are accepted.
         *
         * @param rowCatalog the catalog of the row
         * @param rowSchema the schema of the row
         * @param rowName the table name of the row
         * @return {@code true} if the row is a column of this table
         */
        boolean matches(String rowCatalog, String rowSchema, String rowName) {
            return (rowName == null || rowName.equals(name))
                    && (rowSchema == null || schema == null || rowSchema.equals(schema))
                    && (rowCatalog == null || catalog == null || rowCatalog.equals(catalog));
        }

        @Override
        public String toString() {
            return (catalog == null ? "" : catalog + ".") + (schema == null ? "" : schema + ".") + name;
        }
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
