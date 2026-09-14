package io.github.littleshystar02.lss02.databases.cluster;

public class ClusterConfigurationException extends RuntimeException {

    public ClusterConfigurationException(String message) {
        super(message);
    }

    public ClusterConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
