package io.github.littleshystar02.lss02.databases.cluster;

/**
 * Signals an invalid or incomplete cluster configuration, for example a missing section, an unset
 * environment variable, a duplicate database name or a request for an unknown database.
 */
public class ClusterConfigurationException extends RuntimeException {

    /**
     * Creates the exception with a message.
     *
     * @param message the description of the problem
     */
    public ClusterConfigurationException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and the underlying cause.
     *
     * @param message the description of the problem
     * @param cause the original exception
     */
    public ClusterConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
