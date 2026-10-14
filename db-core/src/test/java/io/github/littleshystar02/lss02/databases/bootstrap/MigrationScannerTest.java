package io.github.littleshystar02.lss02.databases.bootstrap;

import io.github.littleshystar02.lss02.databases.testsupport.JarFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationScannerTest {

    @Test
    void returnsAnEmptyListWhenNoLocationIsConfigured() {
        assertTrue(MigrationScanner.scan(null).isEmpty());
    }

    @Test
    void scansMigrationsPackedInsideAJarAndCanDoSoRepeatedly(@TempDir Path directory) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("db/migrations/V2__add_index.sql", "create index idx on t (id);");
        entries.put("db/migrations/V1__create_table.sql", "create table t (id int);");
        entries.put("db/migrations/notes.txt", "ignored");
        Path jar = JarFixtures.createJar(directory.resolve("plugin.jar"), entries);

        try (URLClassLoader loader = JarFixtures.isolatedLoader(jar)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                List<MigrationScript> scripts = MigrationScanner.scan("classpath:db/migrations", loader);

                assertEquals(2, scripts.size());
                assertEquals(1, scripts.get(0).getVersion());
                assertEquals("create table t (id int);", scripts.get(0).getContent());
                assertEquals(2, scripts.get(1).getVersion());
            }
        }
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
