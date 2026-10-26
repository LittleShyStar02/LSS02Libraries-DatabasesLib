package io.github.littleshystar02.lss02.databases.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Loads the text of a single SQL script from the classpath or from the file system. */
public final class ScriptLoader {

    /** Location prefix that selects the classpath instead of the file system. */
    private static final String CLASSPATH_PREFIX = "classpath:";

    /** Not instantiable: this class only holds static helpers. */
    private ScriptLoader() {
    }

    /**
     * Loads a script using the default class loader (see {@link #load(String, ClassLoader)}).
     *
     * @param location a {@code classpath:} location or a file path
     * @return the script text
     */
    public static String load(String location) {
        return load(location, null);
    }

    /**
     * Loads a script as UTF-8 text.
     *
     * @param location a location starting with {@code classpath:} (resolved through {@code classLoader})
     *                 or a plain file path
     * @param classLoader the class loader for {@code classpath:} locations, {@code null} for the thread
     *                    context class loader
     * @return the script text
     * @throws SchemaMigrationException if a classpath resource does not exist
     * @throws UncheckedIOException if the file cannot be read
     */
    public static String load(String location, ClassLoader classLoader) {
        try {
            if (location.startsWith(CLASSPATH_PREFIX)) {
                String resourcePath = location.substring(CLASSPATH_PREFIX.length());
                try (InputStream stream = resolve(classLoader).getResourceAsStream(resourcePath)) {
                    if (stream == null) {
                        throw new SchemaMigrationException("Script '" + resourcePath + "' was not found on the classpath");
                    }
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return Files.readString(Paths.get(location), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Picks the class loader to use: the given one, otherwise the thread context class loader, otherwise
     * the loader of this library.
     *
     * @param classLoader the preferred class loader, may be {@code null}
     * @return a non-null class loader
     */
    static ClassLoader resolve(ClassLoader classLoader) {
        if (classLoader != null) {
            return classLoader;
        }
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : ScriptLoader.class.getClassLoader();
    }
}
