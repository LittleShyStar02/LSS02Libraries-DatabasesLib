package io.github.littleshystar02.lss02.databases.migration.schema;

import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests for {@link TypeMapping}. */
class TypeMappingTest {

    /**
     * Builds a column with the given type.
     *
     * @param jdbcType the {@link java.sql.Types} code
     * @param typeName the source type name
     * @return the column
     */
    private ColumnSchema column(int jdbcType, String typeName) {
        return ColumnSchema.builder().name("col").jdbcType(jdbcType).typeName(typeName).nullable(true).build();
    }

    /** Supported JDBC types map to the expected logical types. */
    @Test
    void mapsSupportedScalarTypes() {
        assertEquals(LogicalType.INTEGER, TypeMapping.toLogicalType("t", column(Types.INTEGER, "INT")));
        assertEquals(LogicalType.BIGINT, TypeMapping.toLogicalType("t", column(Types.BIGINT, "BIGINT")));
        assertEquals(LogicalType.VARCHAR, TypeMapping.toLogicalType("t", column(Types.VARCHAR, "VARCHAR")));
        assertEquals(LogicalType.TEXT, TypeMapping.toLogicalType("t", column(Types.CLOB, "CLOB")));
        assertEquals(LogicalType.BOOLEAN, TypeMapping.toLogicalType("t", column(Types.BOOLEAN, "BOOLEAN")));
        assertEquals(LogicalType.DATE, TypeMapping.toLogicalType("t", column(Types.DATE, "DATE")));
        assertEquals(LogicalType.TIMESTAMP, TypeMapping.toLogicalType("t", column(Types.TIMESTAMP, "TIMESTAMP")));
        assertEquals(LogicalType.DECIMAL, TypeMapping.toLogicalType("t", column(Types.DECIMAL, "DECIMAL")));
    }

    /** An unsupported JDBC type raises {@link UnsupportedTypeMappingException}. */
    @Test
    void throwsForAnUnsupportedType() {
        assertThrows(io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException.class,
                () -> TypeMapping.toLogicalType("t", column(Types.ARRAY, "ARRAY")));
    }
}
