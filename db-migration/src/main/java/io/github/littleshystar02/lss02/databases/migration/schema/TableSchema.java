package io.github.littleshystar02.lss02.databases.migration.schema;

import lombok.Value;

import java.util.List;

/**
 * Structure of a table: its name and its columns. Immutable value object produced by
 * {@link SchemaIntrospector}.
 */
@Value
public class TableSchema {

    /** Table name. */
    String name;
    /** Columns in the order reported by the source database. */
    List<ColumnSchema> columns;
}
