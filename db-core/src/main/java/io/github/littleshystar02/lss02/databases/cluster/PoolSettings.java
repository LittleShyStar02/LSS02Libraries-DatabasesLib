// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.cluster;

import lombok.Builder;
import lombok.Value;

/**
 * HikariCP connection pool tuning for one database.
 *
 * <p>Durations are in milliseconds. The defaults come from {@link #defaults()} for server engines and
 * from {@link #defaultsFor(DatabaseType)} when the engine is known (SQLite gets a file-friendly profile).
 * Instances are immutable and created through the generated builder.
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
    /** Time after which an idle connection above the minimum is retired; 0 never retires them. */
    long idleTimeoutMs;
    /** Maximum lifetime of a connection before it is replaced; 0 means unlimited. */
    long maxLifetimeMs;
    /** Interval of keep-alive checks on idle connections; 0 disables them. */
    long keepaliveTimeMs;
    /**
     * Time a connection may stay out of the pool before a stack trace of its borrower is logged, which
     * pinpoints sessions that were never closed; 0 disables leak detection.
     */
    long leakDetectionThresholdMs;
    /**
     * How long the pool start may keep trying to obtain the first connection: 0 or 1 fail on the first
     * failed attempt, a larger value retries until that many milliseconds have passed, a negative value
     * starts the pool without an initial connection (degraded start, errors appear on first use).
     */
    long initializationFailTimeoutMs;
    /** Maximum time a liveness check of a pooled connection may take; values below 250 keep the Hikari default. */
    long validationTimeoutMs;
    /** SQL run on every new connection, or {@code null} for none. */
    String connectionInitSql;
    /** Whether the pool registers its JMX MBeans. */
    boolean registerMbeans;

    /**
     * Provides the default pool settings for a server engine: 10 connections at most, 2 kept idle,
     * 30 s connection timeout, 10 min idle timeout, 30 min maximum lifetime, keep-alive every 5 min,
     * fail-fast start, 5 s validation timeout, no leak detection and no JMX.
     *
     * @return a new settings object with the defaults
     */
    public static PoolSettings defaults() {
        return PoolSettings.builder()
                .maximumPoolSize(10)
                .minimumIdle(2)
                .connectionTimeoutMs(30000)
                .idleTimeoutMs(600000)
                .maxLifetimeMs(1800000)
                .keepaliveTimeMs(300000)
                .leakDetectionThresholdMs(0)
                .initializationFailTimeoutMs(1)
                .validationTimeoutMs(5000)
                .build();
    }

    /**
     * Provides the default pool settings for an engine. SQLite allows a single writer, so a file database
     * gets one permanent connection that never expires and no keep-alive; every other engine (and
     * {@code null}) gets {@link #defaults()}.
     *
     * @param type the engine of the database, may be {@code null}
     * @return a new settings object with the defaults for that engine
     */
    public static PoolSettings defaultsFor(DatabaseType type) {
        if (type != DatabaseType.SQLITE) {
            return defaults();
        }
        return PoolSettings.builder()
                .maximumPoolSize(1)
                .minimumIdle(1)
                .connectionTimeoutMs(30000)
                .idleTimeoutMs(0)
                .maxLifetimeMs(0)
                .keepaliveTimeMs(0)
                .leakDetectionThresholdMs(0)
                .initializationFailTimeoutMs(1)
                .validationTimeoutMs(5000)
                .build();
    }
}
