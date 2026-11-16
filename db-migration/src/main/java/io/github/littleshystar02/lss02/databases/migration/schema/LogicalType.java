// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration.schema;

/** Dialect-neutral column types the migration can translate between engines. */
public enum LogicalType {
    /** 32-bit integer. */
    INTEGER,
    /** 16-bit integer (also used for TINYINT). */
    SMALLINT,
    /** 64-bit integer. */
    BIGINT,
    /** Single precision floating point. */
    REAL,
    /** Double precision floating point. */
    DOUBLE,
    /** Bounded text. */
    VARCHAR,
    /** Unbounded text. */
    TEXT,
    /** True/false value. */
    BOOLEAN,
    /** Calendar date. */
    DATE,
    /** Time of day. */
    TIME,
    /** Date and time. */
    TIMESTAMP,
    /** Exact number with precision and scale. */
    DECIMAL,
    /** Binary data. */
    BLOB
}
