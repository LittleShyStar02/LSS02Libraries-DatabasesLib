package io.github.littleshystar02.lss02.databases.migration.schema;

import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the {@link DdlGenerator} implementations. */
class DdlGeneratorTest {

    /**
     * Builds a small table used by all tests.
     *
     * @return the sample table structure
     */
    private TableSchema sampleTable() {
        return new TableSchema("users", List.of(
                ColumnSchema.builder().name("id").jdbcType(Types.INTEGER).typeName("INT").nullable(false).build(),
                ColumnSchema.builder().name("name").jdbcType(Types.VARCHAR).typeName("VARCHAR").size(100).nullable(false).build(),
                ColumnSchema.builder().name("bio").jdbcType(Types.CLOB).typeName("TEXT").nullable(true).build()));
    }

    /** MySQL output uses backticks and declares the VARCHAR length. */
    @Test
    void mysqlGeneratorQuotesIdentifiersAndRendersVarcharLength() {
        String ddl = new MySqlDdlGenerator().generateCreateTable(sampleTable());
        assertTrue(ddl.contains("CREATE TABLE `users`"));
        assertTrue(ddl.contains("`id` INT NOT NULL"));
        assertTrue(ddl.contains("`name` VARCHAR(100) NOT NULL"));
        assertTrue(ddl.contains("`bio` TEXT"));
    }

    /** MariaDB output equals the MySQL output. */
    @Test
    void mariaDbGeneratorBehavesLikeMysql() {
        assertEquals(new MySqlDdlGenerator().generateCreateTable(sampleTable()),
                new MariaDbDdlGenerator().generateCreateTable(sampleTable()));
    }

    /** PostgreSQL output quotes identifiers with double quotes. */
    @Test
    void postgresGeneratorUsesDoubleQuotes() {
        String ddl = new PostgresDdlGenerator().generateCreateTable(sampleTable());
        assertTrue(ddl.contains("CREATE TABLE \"users\""));
        assertTrue(ddl.contains("\"id\" INTEGER NOT NULL"));
    }

    /** SQLite output maps both VARCHAR and TEXT columns to TEXT. */
    @Test
    void sqliteGeneratorCollapsesVarcharAndTextToText() {
        String ddl = new SqliteDdlGenerator().generateCreateTable(sampleTable());
        assertTrue(ddl.contains("\"name\" TEXT NOT NULL"));
        assertTrue(ddl.contains("\"bio\" TEXT"));
        assertTrue(ddl.contains("\"id\" INTEGER NOT NULL"));
    }
}
