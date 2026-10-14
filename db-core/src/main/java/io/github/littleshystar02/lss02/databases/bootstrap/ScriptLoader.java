package io.github.littleshystar02.lss02.databases.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class ScriptLoader {

    private static final String CLASSPATH_PREFIX = "classpath:";

    private ScriptLoader() {
    }

    public static String load(String location) {
        return load(location, null);
    }

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

    static ClassLoader resolve(ClassLoader classLoader) {
        if (classLoader != null) {
            return classLoader;
        }
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : ScriptLoader.class.getClassLoader();
    }
}
