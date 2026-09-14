package io.github.littleshystar02.lss02.databases.migration.schema;

import lombok.Value;

import java.util.List;

@Value
public class TableSchema {

    String name;
    List<ColumnSchema> columns;
}
