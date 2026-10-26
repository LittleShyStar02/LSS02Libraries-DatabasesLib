package io.github.littleshystar02.lss02.databases.migration.schema;

/**
 * Engine-neutral column types used as the middle step of a cross-database migration: a JDBC type is
 * first mapped to one of these (see {@link TypeMapping}) and each {@link DdlGenerator} then renders it
 * in its own dialect.
 */
public enum LogicalType {
    /** Whole numbers up to 32 bits (also tiny and small integers). */
    INTEGER,
    /** 64 bit whole numbers. */
    BIGINT,
    /** Variable length text with a declared maximum size. */
    VARCHAR,
    /** Long text without a declared size. */
    TEXT,
    /** True or false values. */
    BOOLEAN,
    /** Calendar date without time. */
    DATE,
    /** Date and time. */
    TIMESTAMP,
    /** Exact numbers with precision and scale. */
    DECIMAL
}
