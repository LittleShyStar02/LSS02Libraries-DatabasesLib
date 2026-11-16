// SPDX-License-Identifier: GPL-3.0-only
/**
 * Idempotent schema bootstrap on top of Flyway: runs a baseline setup script and versioned
 * {@code V<version>__<description>.sql} migration scripts exactly once per database, under a
 * database-level lock, recording each one with its checksum in the Flyway history table.
 */
package io.github.littleshystar02.lss02.databases.bootstrap;
