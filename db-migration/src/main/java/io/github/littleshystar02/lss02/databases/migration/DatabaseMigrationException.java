package io.github.littleshystar02.lss02.databases.migration;

public class DatabaseMigrationException extends RuntimeException {

    public DatabaseMigrationException(String message) {
        super(message);
    }

    public DatabaseMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
