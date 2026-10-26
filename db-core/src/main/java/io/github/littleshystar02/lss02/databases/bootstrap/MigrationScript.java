package io.github.littleshystar02.lss02.databases.bootstrap;

import lombok.Value;

/**
 * A single SQL migration script together with the metadata stored in the schema history table.
 *
 * <p>Instances are immutable value objects created by {@link MigrationScanner}, or by
 * {@link SchemaBootstrapper} for the baseline setup script.
 */
@Value
public class MigrationScript {

    /**
     * Numeric version, taken from the {@code V<version>} part of the file name. Version 0 is the baseline.
     */
    int version;
    /**
     * Human readable description, the file name part after {@code __} with underscores replaced by spaces.
     */
    String description;
    /** File name of the script, for example {@code V2__add_users_table.sql}. */
    String scriptName;
    /** Full SQL text of the script. */
    String content;
    /** SHA-256 checksum of {@link #content}, used to detect edits after the script was applied. */
    String checksum;
}
