package io.github.littleshystar02.lss02.databases.cluster;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PoolSettings {

    int maximumPoolSize;
    int minimumIdle;
    long connectionTimeoutMs;
    long idleTimeoutMs;
    long maxLifetimeMs;

    public static PoolSettings defaults() {
        return PoolSettings.builder()
                .maximumPoolSize(10)
                .minimumIdle(0)
                .connectionTimeoutMs(30000)
                .idleTimeoutMs(600000)
                .maxLifetimeMs(1800000)
                .build();
    }
}
