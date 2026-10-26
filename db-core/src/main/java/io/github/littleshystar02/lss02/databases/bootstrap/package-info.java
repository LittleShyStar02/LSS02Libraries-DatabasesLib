/**
 * Idempotent schema bootstrap: runs a baseline setup script and versioned
 * {@code V<version>__<description>.sql} migration scripts exactly once per database, recording each
 * one with its checksum in a history table.
 */
package io.github.littleshystar02.lss02.databases.bootstrap;
