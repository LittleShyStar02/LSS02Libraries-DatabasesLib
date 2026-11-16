// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration.schema;

import lombok.Value;

import java.util.List;

/**
 * Structure of one table: its name, its columns in order and the columns of its primary key.
 */
@Value
public class TableSchema {

    /** Table name as stored in the source database. */
    String name;
    /** Columns in their physical order. */
    List<ColumnSchema> columns;
    /** Names of the primary key columns in key order; empty when the table has no primary key. */
    List<String> primaryKeyColumns;

    /**
     * Creates a table without primary key information.
     *
     * @param name the table name
     * @param columns the columns in order
     */
    public TableSchema(String name, List<ColumnSchema> columns) {
        this(name, columns, List.of());
    }

    /**
     * Creates a table.
     *
     * @param name the table name
     * @param columns the columns in order
     * @param primaryKeyColumns the primary key column names in key order
     */
    public TableSchema(String name, List<ColumnSchema> columns, List<String> primaryKeyColumns) {
        this.name = name;
        this.columns = columns;
        this.primaryKeyColumns = primaryKeyColumns == null ? List.of() : List.copyOf(primaryKeyColumns);
    }
}
