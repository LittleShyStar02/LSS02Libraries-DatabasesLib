package io.github.littleshystar02.lss02.databases.migration;

import lombok.Getter;

@Getter
public class UnsupportedTypeMappingException extends DatabaseMigrationException {

    private final String tableName;
    private final String columnName;
    private final String sourceTypeName;

    public UnsupportedTypeMappingException(String tableName, String columnName, String sourceTypeName) {
        super("Column '" + tableName + "." + columnName + "' has type '" + sourceTypeName
                + "' which is not supported for cross-database migration in this version");
        this.tableName = tableName;
        this.columnName = columnName;
        this.sourceTypeName = sourceTypeName;
    }
}
