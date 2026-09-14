package io.github.littleshystar02.lss02.databases.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationScannerTest {

    @Test
    void returnsAnEmptyListWhenNoLocationIsConfigured() {
        assertTrue(MigrationScanner.scan(null).isEmpty());
    }

    @Test
    void ordersScriptsByVersionAndIgnoresUnrelatedFiles(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("V2__add_index.sql"), "create index idx on t (id);");
        Files.writeString(directory.resolve("V1__create_table.sql"), "create table t (id int);");
        Files.writeString(directory.resolve("readme.md"), "not a migration");

        List<MigrationScript> scripts = MigrationScanner.scan(directory.toString());

        assertEquals(2, scripts.size());
        assertEquals(1, scripts.get(0).getVersion());
        assertEquals("create table", scripts.get(0).getDescription());
        assertEquals(2, scripts.get(1).getVersion());
    }
}
