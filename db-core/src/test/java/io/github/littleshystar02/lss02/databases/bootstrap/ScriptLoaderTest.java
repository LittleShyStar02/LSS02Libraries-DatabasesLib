package io.github.littleshystar02.lss02.databases.bootstrap;

import io.github.littleshystar02.lss02.databases.testsupport.JarFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScriptLoaderTest {

    @Test
    void readsAClasspathScriptFromTheGivenClassLoader(@TempDir Path directory) throws IOException {
        Path jar = JarFixtures.createJar(directory.resolve("plugin.jar"), Map.of("db/setup.sql", "select 1;"));

        try (URLClassLoader loader = JarFixtures.isolatedLoader(jar)) {
            assertEquals("select 1;", ScriptLoader.load("classpath:db/setup.sql", loader));
        }
    }

    @Test
    void doesNotSeeJarResourcesThroughAnUnrelatedContextClassLoader(@TempDir Path directory) throws IOException {
        Path jar = JarFixtures.createJar(directory.resolve("plugin.jar"), Map.of("db/only-in-jar.sql", "select 1;"));

        try (URLClassLoader loader = JarFixtures.isolatedLoader(jar)) {
            assertThrows(SchemaMigrationException.class, () -> ScriptLoader.load("classpath:db/only-in-jar.sql"));
            assertEquals("select 1;", ScriptLoader.load("classpath:db/only-in-jar.sql", loader));
        }
    }
}
