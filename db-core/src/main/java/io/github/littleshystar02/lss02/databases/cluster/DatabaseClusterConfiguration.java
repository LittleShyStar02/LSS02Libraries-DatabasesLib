package io.github.littleshystar02.lss02.databases.cluster;

import io.github.littleshystar02.lss02.config.AbstractConfiguration;

import java.io.File;
import java.net.URI;

/**
 * YAML file that describes the databases of a cluster under the {@code database-cluster} key.
 *
 * <p>It adds no behavior to {@link AbstractConfiguration}: it is the typed entry point accepted by
 * {@link io.github.littleshystar02.lss02.databases.mybatis.DatabaseCluster} and
 * {@link ClusterLoader}. Call {@code load()} on the instance before using it.
 */
public class DatabaseClusterConfiguration extends AbstractConfiguration {

    /**
     * Binds the configuration to a file.
     *
     * @param file the YAML file
     */
    public DatabaseClusterConfiguration(File file) {
        super(file);
    }

    /**
     * Binds the configuration to a file path.
     *
     * @param path the path of the YAML file
     */
    public DatabaseClusterConfiguration(String path) {
        super(path);
    }

    /**
     * Binds the configuration to the file designated by a URI.
     *
     * @param uri the {@code file:} URI of the YAML file
     */
    public DatabaseClusterConfiguration(URI uri) {
        super(uri);
    }
}
