package io.github.littleshystar02.lss02.databases.migration.schema;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ColumnSchema {

    String name;
    int jdbcType;
    String typeName;
    Integer size;
    Integer decimalDigits;
    boolean nullable;
}
