package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.migration.DatabaseMigrationException;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class SchemaIntrospector {

    private SchemaIntrospector() {
    }

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

    public static List<TableSchema> introspect(Connection connection, List<String> tableNames) {
        List<TableSchema> tables = new ArrayList<>();
        for (String tableName : tableNames) {
            tables.add(introspect(connection, tableName));
        }
        return tables;
    }
}
