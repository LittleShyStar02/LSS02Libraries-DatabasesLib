package io.github.littleshystar02.lss02.databases.bootstrap;

/**
 * Signals that a schema bootstrap failed: a script could not be found, read or executed, or an already
 * applied script was modified.
 */
public class SchemaMigrationException extends RuntimeException {

    /**
     * Creates the exception with a message.
     *
     * @param message the description of the failure
     */
    public SchemaMigrationException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and the underlying cause.
     *
     * @param message the description of the failure
     * @param cause the original exception, typically an {@link java.sql.SQLException}
     */
    public SchemaMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
