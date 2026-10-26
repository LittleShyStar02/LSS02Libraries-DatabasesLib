package io.github.littleshystar02.lss02.databases.migration.schema;

/**
 * Produces the SQL needed to recreate a table in one specific database dialect.
 *
 * @see DdlGeneratorFactory
 */
public interface DdlGenerator {

    /**
     * Generates a {@code CREATE TABLE} statement for a table structure.
     *
     * @param table the structure to reproduce
     * @return the DDL statement in the dialect of this generator
     * @throws io.github.littleshystar02.lss02.databases.migration.UnsupportedTypeMappingException if a
     *         column type cannot be represented in the dialect
     */
    String generateCreateTable(TableSchema table);
}
