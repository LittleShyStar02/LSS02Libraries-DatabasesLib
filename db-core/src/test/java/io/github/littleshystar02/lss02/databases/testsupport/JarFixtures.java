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

/** Helpers that build small jar files and isolated class loaders for classpath-related tests. */
public final class JarFixtures {

    /** Not instantiable: this class only holds static helpers. */
    private JarFixtures() {
    }

    /**
     * Writes a jar file whose entries contain the given text, adding the directory entries that lead to
     * them.
     *
     * @param jarFile the jar file to create
     * @param entries entry path inside the jar mapped to its UTF-8 text content
     * @return {@code jarFile}, for chaining
     * @throws IOException if the jar cannot be written
     */
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

    /**
     * Creates a class loader that sees only the given jar, with no parent, so tests can prove that
     * resources are not leaking from the application class path.
     *
     * @param jarFile the jar to expose
     * @return the class loader, which the caller should close
     * @throws IOException if the jar path cannot be converted to a URL
     */
    public static URLClassLoader isolatedLoader(Path jarFile) throws IOException {
        return new URLClassLoader(new URL[]{jarFile.toUri().toURL()}, null);
    }
}
