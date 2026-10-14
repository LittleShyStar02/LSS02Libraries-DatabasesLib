package io.github.littleshystar02.lss02.databases.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class JarFixtures {

    private JarFixtures() {
    }

    public static Path createJar(Path jarFile, Map<String, String> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(jarFile); JarOutputStream jar = new JarOutputStream(out)) {
            Set<String> directories = new LinkedHashSet<>();
            for (String name : entries.keySet()) {
                int slash = name.indexOf('/');
                while (slash >= 0) {
                    directories.add(name.substring(0, slash + 1));
                    slash = name.indexOf('/', slash + 1);
                }
            }
            for (String directory : directories) {
                jar.putNextEntry(new JarEntry(directory));
                jar.closeEntry();
            }
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return jarFile;
    }

    public static URLClassLoader isolatedLoader(Path jarFile) throws IOException {
        return new URLClassLoader(new URL[]{jarFile.toUri().toURL()}, null);
    }
}
