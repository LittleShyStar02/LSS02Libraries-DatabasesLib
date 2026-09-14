package io.github.littleshystar02.lss02.databases.migration.schema;

public interface DdlGenerator {

    String generateCreateTable(TableSchema table);
}
