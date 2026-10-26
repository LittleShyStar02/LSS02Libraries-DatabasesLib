package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.migration.DatabaseMigrationException;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Reads table structures from a live database through JDBC metadata. */
public final class SchemaIntrospector {

    /** Not instantiable: this class only holds static helpers. */
    private SchemaIntrospector() {
    }

    /**
     * Reads the columns of one table of the connection's current catalog.
     *
     * @param connection an open connection to the source database
     * @param tableName the table to describe
     * @return the table structure, with its columns in the order the driver reports them
     * @throws DatabaseMigrationException if the table does not exist, has no columns, or the metadata
     *         cannot be read
     */
    public static TableSchema introspect(Connection connection, String tableName) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            List<ColumnSchema> columns = new ArrayList<>();
            try (ResultSet resultSet = metaData.getColumns(connection.getCatalog(), null, tableName, null)) {
                while (resultSet.next()) {
                    columns.add(ColumnSchema.builder()
                            .name(resultSet.getString("COLUMN_NAME"))
                            .jdbcType(resultSet.getInt("DATA_TYPE"))
                            .typeName(resultSet.getString("TYPE_NAME"))
                            .size(resultSet.getInt("COLUMN_SIZE"))
                            .decimalDigits(resultSet.getInt("DECIMAL_DIGITS"))
                            .nullable("YES".equalsIgnoreCase(resultSet.getString("IS_NULLABLE")))
                            .build());
                }
            }
            if (columns.isEmpty()) {
                throw new DatabaseMigrationException("Table '" + tableName + "' was not found or has no columns");
            }
            return new TableSchema(tableName, columns);
        } catch (SQLException e) {
            throw new DatabaseMigrationException("Could not introspect table '" + tableName + "'", e);
        }
    }

    /**
     * Reads the structure of several tables.
     *
     * @param connection an open connection to the source database
     * @param tableNames the tables to describe
     * @return the table structures in the order of {@code tableNames}
     * @throws DatabaseMigrationException if any table cannot be described
     */
    public static List<TableSchema> introspect(Connection connection, List<String> tableNames) {
        List<TableSchema> tables = new ArrayList<>();
        for (String tableName : tableNames) {
            tables.add(introspect(connection, tableName));
        }
        return tables;
    }
}
