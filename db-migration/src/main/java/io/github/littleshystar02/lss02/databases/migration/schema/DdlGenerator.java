// SPDX-License-Identifier: GPL-3.0-only
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

    /**
     * Generates a statement that drops a table if it exists, used to undo a partially applied schema
     * migration.
     *
     * @param table the structure of the table to drop
     * @return the DDL statement in the dialect of this generator
     */
    String generateDropTable(TableSchema table);
}
