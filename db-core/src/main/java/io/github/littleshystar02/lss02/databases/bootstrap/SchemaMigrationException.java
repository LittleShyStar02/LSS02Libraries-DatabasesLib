package io.github.littleshystar02.lss02.databases.bootstrap;

public class SchemaMigrationException extends RuntimeException {

    public SchemaMigrationException(String message) {
        super(message);
    }

    public SchemaMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
