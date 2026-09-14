package io.github.littleshystar02.lss02.databases.migration.schema;

import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DdlGeneratorTest {

    private TableSchema sampleTable() {
        return new TableSchema("users", List.of(
                ColumnSchema.builder().name("id").jdbcType(Types.INTEGER).typeName("INT").nullable(false).build(),
                ColumnSchema.builder().name("name").jdbcType(Types.VARCHAR).typeName("VARCHAR").size(100).nullable(false).build(),
                ColumnSchema.builder().name("bio").jdbcType(Types.CLOB).typeName("TEXT").nullable(true).build()));
    }

    @Test
    void mysqlGeneratorQuotesIdentifiersAndRendersVarcharLength() {
        String ddl = new MySqlDdlGenerator().generateCreateTable(sampleTable());
        assertTrue(ddl.contains("CREATE TABLE `users`"));
        assertTrue(ddl.contains("`id` INT NOT NULL"));
        assertTrue(ddl.contains("`name` VARCHAR(100) NOT NULL"));
        assertTrue(ddl.contains("`bio` TEXT"));
    }

    @Test
    void mariaDbGeneratorBehavesLikeMysql() {
        assertEquals(new MySqlDdlGenerator().generateCreateTable(sampleTable()),
                new MariaDbDdlGenerator().generateCreateTable(sampleTable()));
    }

    @Test
    void postgresGeneratorUsesDoubleQuotes() {
        String ddl = new PostgresDdlGenerator().generateCreateTable(sampleTable());
        assertTrue(ddl.contains("CREATE TABLE \"users\""));
        assertTrue(ddl.contains("\"id\" INTEGER NOT NULL"));
    }

    @Test
    void sqliteGeneratorCollapsesVarcharAndTextToText() {
        String ddl = new SqliteDdlGenerator().generateCreateTable(sampleTable());
        assertTrue(ddl.contains("\"name\" TEXT NOT NULL"));
        assertTrue(ddl.contains("\"bio\" TEXT"));
        assertTrue(ddl.contains("\"id\" INTEGER NOT NULL"));
    }
}
