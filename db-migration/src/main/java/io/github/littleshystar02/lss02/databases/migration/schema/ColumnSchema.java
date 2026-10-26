package io.github.littleshystar02.lss02.databases.migration.schema;

import lombok.Builder;
import lombok.Value;

/**
 * Structure of one table column as read from the source database metadata.
 *
 * <p>Instances are immutable and created through the generated builder by {@link SchemaIntrospector}.
 */
@Value
@Builder
public class ColumnSchema {

    /** Column name. */
    String name;
    /** Type code from {@link java.sql.Types}; this is what the type mapping works on. */
    int jdbcType;
    /** Type name as the source database spells it, kept for error messages. */
    String typeName;
    /** Column size (length or precision) as reported by the driver. */
    Integer size;
    /** Number of digits after the decimal point, for numeric types. */
    Integer decimalDigits;
    /** Whether the column accepts {@code NULL}. */
    boolean nullable;
}
