package io.github.littleshystar02.lss02.databases.migration;

import lombok.Getter;

/**
 * Signals that a column has a JDBC type that cannot be translated to the target database.
 *
 * <p>It carries the table, column and source type name, so the failing column can be reported to the
 * user or excluded by the caller.
 */
@Getter
public class UnsupportedTypeMappingException extends DatabaseMigrationException {

    /** Name of the table that contains the column. */
    private final String tableName;
    /** Name of the column with the unsupported type. */
    private final String columnName;
    /** Type name of the column as reported by the source database. */
    private final String sourceTypeName;

    /**
     * Creates the exception for one column.
     *
     * @param tableName the table that contains the column
     * @param columnName the column with the unsupported type
     * @param sourceTypeName the type name as reported by the source database
     */
    public UnsupportedTypeMappingException(String tableName, String columnName, String sourceTypeName) {
        super("Column '" + tableName + "." + columnName + "' has type '" + sourceTypeName
                + "' which is not supported for cross-database migration in this version");
        this.tableName = tableName;
        this.columnName = columnName;
        this.sourceTypeName = sourceTypeName;
    }
}
