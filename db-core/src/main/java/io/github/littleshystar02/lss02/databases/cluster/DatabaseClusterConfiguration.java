package io.github.littleshystar02.lss02.databases.cluster;

import io.github.littleshystar02.lss02.config.AbstractConfiguration;

import java.io.File;
import java.net.URI;

public class DatabaseClusterConfiguration extends AbstractConfiguration {

    public DatabaseClusterConfiguration(File file) {
        super(file);
    }

    public DatabaseClusterConfiguration(String path) {
        super(path);
    }

    public DatabaseClusterConfiguration(URI uri) {
        super(uri);
    }
}
