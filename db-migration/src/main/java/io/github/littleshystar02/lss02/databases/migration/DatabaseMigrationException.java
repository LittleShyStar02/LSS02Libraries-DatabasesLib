package io.github.littleshystar02.lss02.databases.migration;

/**
 * Signals that copying a schema or data from one database to another failed, for example because a
 * table is missing, a statement fails or a column type cannot be translated.
 */
public class DatabaseMigrationException extends RuntimeException {

    /**
     * Creates the exception with a message.
     *
     * @param message the description of the failure
     */
    public DatabaseMigrationException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and the underlying cause.
     *
     * @param message the description of the failure
     * @param cause the original exception, typically an {@link java.sql.SQLException}
     */
    public DatabaseMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
