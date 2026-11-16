// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration.schema;

import io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests primary keys, escaping, numeric precision and the extended type matrix of the DDL generators. */
class DdlGeneratorKeysAndTypesTest {

    /**
     * Builds a column.
     *
     * @param name the column name
     * @param jdbcType the JDBC type code
     * @param size the declared size, or {@code null}
     * @param digits the declared scale, or {@code null}
     * @return the column
     */
    private ColumnSchema col(String name, int jdbcType, Integer size, Integer digits) {
        return ColumnSchema.builder().name(name).jdbcType(jdbcType).typeName("T").size(size).decimalDigits(digits).nullable(false).build();
    }

    /** The primary key of the source table is reproduced, with escaped identifiers. */
    @Test
    void generatesPrimaryKeyAndEscapesQuotes() {
        TableSchema table = new TableSchema("we\"ird", List.of(col("id", Types.INTEGER, null, null), col("g\"p", Types.BIGINT, null, null)),
                List.of("id", "g\"p"));
        String ddl = new PostgresDdlGenerator().generateCreateTable(table);
        assertTrue(ddl.contains("CREATE TABLE \"we\"\"ird\""));
        assertTrue(ddl.contains("PRIMARY KEY (\"id\", \"g\"\"p\")"));
    }

    /** MySQL drops a key that cannot be indexed (long VARCHAR or TEXT). */
    @Test
    void mysqlOmitsUnsafePrimaryKeys() {
        TableSchema longKey = new TableSchema("t", List.of(col("k", Types.VARCHAR, 2000, null)), List.of("k"));
        TableSchema shortKey = new TableSchema("t", List.of(col("k", Types.VARCHAR, 100, null)), List.of("k"));
        assertFalse(new MySqlDdlGenerator().generateCreateTable(longKey).contains("PRIMARY KEY"));
        assertTrue(new MySqlDdlGenerator().generateCreateTable(shortKey).contains("PRIMARY KEY"));
    }

    /** Decimal precision and scale are kept, a scale of zero included, and clamped to the dialect limits. */
    @Test
    void keepsDecimalPrecisionAndScale() {
        TableSchema table = new TableSchema("t", List.of(
                col("a", Types.DECIMAL, 10, 0), col("b", Types.DECIMAL, 12, 4), col("c", Types.NUMERIC, 100, 50)));
        String mysql = new MySqlDdlGenerator().generateCreateTable(table);
        assertTrue(mysql.contains("`a` DECIMAL(10,0)"));
        assertTrue(mysql.contains("`b` DECIMAL(12,4)"));
        assertTrue(mysql.contains("`c` DECIMAL(65,30)"));
        assertTrue(new PostgresDdlGenerator().generateCreateTable(table).contains("\"c\" NUMERIC(100,50)"));
    }

    /** Floating point, small integers, binary and time columns map in every dialect. */
    @Test
    void mapsTheExtendedTypes() {
        TableSchema table = new TableSchema("t", List.of(
                col("s", Types.SMALLINT, null, null), col("f", Types.REAL, null, null), col("d", Types.DOUBLE, null, null),
                col("b", Types.BLOB, null, null), col("tm", Types.TIME, null, null)));
        String pg = new PostgresDdlGenerator().generateCreateTable(table);
        assertTrue(pg.contains("SMALLINT") && pg.contains("REAL") && pg.contains("DOUBLE PRECISION")
                && pg.contains("BYTEA") && pg.contains("TIME"));
        String my = new MySqlDdlGenerator().generateCreateTable(table);
        assertTrue(my.contains("FLOAT") && my.contains("DOUBLE") && my.contains("LONGBLOB"));
        String lite = new SqliteDdlGenerator().generateCreateTable(table);
        assertTrue(lite.contains("\"f\" REAL") && lite.contains("\"b\" BLOB"));
    }

    /** Types without a mapping still raise the documented exception. */
    @Test
    void unsupportedTypesStillThrow() {
        TableSchema table = new TableSchema("t", List.of(col("x", Types.ARRAY, null, null)));
        assertThrows(UnsupportedTypeMappingException.class, () -> new PostgresDdlGenerator().generateCreateTable(table));
    }
}