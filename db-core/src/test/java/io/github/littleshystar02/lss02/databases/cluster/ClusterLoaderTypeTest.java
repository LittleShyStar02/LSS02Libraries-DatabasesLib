// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the {@code type} parsing of {@link ClusterLoader}. */
class ClusterLoaderTypeTest {

    /**
     * Loads a one-entry cluster whose type is the given text.
     *
     * @param tempDir directory for the YAML file
     * @param type the raw type value
     * @return the parsed definition of the entry
     * @throws IOException if the file cannot be written
     */
    private DatabaseDefinition loadWithType(Path tempDir, String type) throws IOException {
        Path file = tempDir.resolve("cluster.yml");
        Files.writeString(file, "database-cluster:\n  main:\n    type: " + type
                + "\n    host: localhost\n    database: d\n");
        DatabaseClusterConfiguration configuration = new DatabaseClusterConfiguration(file.toFile());
        configuration.load();
        return ClusterLoader.load(configuration).get("main");
    }

    /** Types resolve independently of the default locale (Turkish dotted and dotless i). */
    @Test
    void typeParsingIgnoresTheDefaultLocale(@TempDir Path tempDir) throws IOException {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals(DatabaseType.POSTGRESQL, loadWithType(tempDir, "postgresql").getType());
            assertEquals(DatabaseType.MYSQL, loadWithType(tempDir, "MySQL").getType());
            assertEquals(DatabaseType.SQLITE, loadWithType(tempDir, "sqlite").getType());
        } finally {
            Locale.setDefault(previous);
        }
    }

    /** An unknown type fails with the entry name and the allowed values. */
    @Test
    void unknownTypeNamesTheEntryAndTheAllowedValues(@TempDir Path tempDir) {
        ClusterConfigurationException error =
                assertThrows(ClusterConfigurationException.class, () -> loadWithType(tempDir, "oracle"));
        assertTrue(error.getMessage().contains("main"));
        assertTrue(error.getMessage().contains("oracle"));
        assertTrue(error.getMessage().contains("postgresql"));
    }
}