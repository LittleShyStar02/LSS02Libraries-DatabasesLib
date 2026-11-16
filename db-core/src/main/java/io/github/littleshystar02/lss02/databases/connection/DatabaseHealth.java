// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.connection;

/**
 * Snapshot of the health of one database pool, as returned by
 * {@link io.github.littleshystar02.lss02.databases.mybatis.DatabaseCluster#health()}.
 *
 * @param name the database name
 * @param up whether a connection was obtained and found valid
 * @param latencyMs time the liveness check took, in milliseconds
 * @param activeConnections connections currently borrowed, or {@code -1} when the pool does not report it
 * @param idleConnections connections currently idle, or {@code -1} when unknown
 * @param totalConnections connections currently open, or {@code -1} when unknown
 * @param threadsAwaitingConnection threads blocked waiting for a connection, or {@code -1} when unknown
 * @param error the failure message when the database is down, otherwise {@code null}
 */
public record DatabaseHealth(
        String name,
        boolean up,
        long latencyMs,
        int activeConnections,
        int idleConnections,
        int totalConnections,
        int threadsAwaitingConnection,
        String error) {
}
