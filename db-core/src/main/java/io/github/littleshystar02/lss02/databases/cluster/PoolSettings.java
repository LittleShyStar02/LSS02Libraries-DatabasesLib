package io.github.littleshystar02.lss02.databases.cluster;

import lombok.Builder;
import lombok.Value;

/**
 * HikariCP connection pool tuning for one database.
 *
 * <p>Durations are in milliseconds. The defaults come from {@link #defaults()}. Instances are
 * immutable and created through the generated builder.
 */
@Value
@Builder
public class PoolSettings {

    /** Maximum number of connections the pool may hold. */
    int maximumPoolSize;
    /** Minimum number of idle connections the pool tries to keep. */
    int minimumIdle;
    /** Maximum time to wait for a connection from the pool before failing. */
    long connectionTimeoutMs;
    /** Time after which an idle connection above the minimum is retired. */
    long idleTimeoutMs;
    /** Maximum lifetime of a connection before it is replaced. */
    long maxLifetimeMs;
    /** Interval of keep-alive checks on idle connections; 0 disables them. */
    long keepaliveTimeMs;

    /**
     * Provides the default pool settings: 10 connections at most, none kept idle, 30 s connection timeout,
     * 10 min idle timeout, 30 min maximum lifetime and no keep-alive.
     *
     * @return a new settings object with the defaults
     */
    public static PoolSettings defaults() {
        return PoolSettings.builder()
                .maximumPoolSize(10)
                .minimumIdle(0)
                .connectionTimeoutMs(30000)
                .idleTimeoutMs(600000)
                .maxLifetimeMs(1800000)
                .keepaliveTimeMs(0)
                .build();
    }
}
