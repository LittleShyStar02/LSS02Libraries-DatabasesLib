package io.github.littleshystar02.lss02.databases.bootstrap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the versioned SQL migration scripts stored in a directory, either on disk or on the classpath.
 *
 * <p>A script is picked up only when its file name matches {@code V<version>__<description>.sql}, for
 * example {@code V2__add_users_table.sql}. Underscores in the description are replaced by spaces.
 * Other files in the directory are ignored. The classpath may point inside a jar, in which case the
 * jar is opened as a temporary file system while it is scanned.
 */
public final class MigrationScanner {

    /** Matches a script file name; group 1 is the numeric version and group 2 the description. */
    private static final Pattern SCRIPT_NAME_PATTERN = Pattern.compile("^V(\\d+)__(.+)\\.sql$");
    /** Location prefix that selects the classpath instead of the file system. */
    private static final String CLASSPATH_PREFIX = "classpath:";
    /** URI scheme used by classpath resources that live inside a jar. */
    private static final String JAR_PROTOCOL = "jar";

    /** Not instantiable: this class only holds static helpers. */
    private MigrationScanner() {
    }

    /**
     * Scans a location using the default class loader (see {@link #scan(String, ClassLoader)}).
     *
     * @param location a {@code classpath:} location or a file system directory, may be {@code null}
     * @return the scripts sorted by ascending version, empty when {@code location} is {@code null}
     */
    public static List<MigrationScript> scan(String location) {
        return scan(location, null);
    }

    /**
     * Scans a location for versioned migration scripts.
     *
     * @param location a location starting with {@code classpath:} (resolved through {@code classLoader})
     *                 or a plain file system directory; {@code null} means "no scripts"
     * @param classLoader the class loader used for {@code classpath:} locations, {@code null} for the
     *                    thread context class loader
     * @return the scripts sorted by ascending version, empty when {@code location} is {@code null}
     * @throws SchemaMigrationException if the location does not exist or is not a directory
     */
    public static List<MigrationScript> scan(String location, ClassLoader classLoader) {
        if (location == null) {
            return List.of();
        }
        if (location.startsWith(CLASSPATH_PREFIX)) {
            return scanClasspath(location.substring(CLASSPATH_PREFIX.length()), ScriptLoader.resolve(classLoader));
        }
        return scanFilesystem(Paths.get(location));
    }

    /**
     * Resolves a classpath directory and scans it, as a jar entry or as a plain directory.
     *
     * @param resourcePath the classpath path without the {@code classpath:} prefix
     * @param classLoader the class loader that resolves the path
     * @return the scripts found, sorted by version
     * @throws SchemaMigrationException if the path is missing or cannot be converted to a URI
     */
    private static List<MigrationScript> scanClasspath(String resourcePath, ClassLoader classLoader) {
        URL url = classLoader.getResource(resourcePath);
        if (url == null) {
            throw new SchemaMigrationException("Migration path '" + resourcePath + "' was not found on the classpath");
        }
        try {
            URI uri = url.toURI();
            if (JAR_PROTOCOL.equalsIgnoreCase(uri.getScheme())) {
                return scanJar(uri, resourcePath);
            }
            return scanFilesystem(Paths.get(uri));
        } catch (URISyntaxException e) {
            throw new SchemaMigrationException("Migration path '" + resourcePath + "' could not be resolved", e);
        }
    }

    /**
     * Scans a directory stored inside a jar. The jar file system is closed afterwards only if this call
     * opened it, so a file system that was already open elsewhere in the JVM is left untouched.
     *
     * @param uri the {@code jar:} URI of the directory
     * @param resourcePath the classpath path, used for error messages
     * @return the scripts found, sorted by version
     * @throws SchemaMigrationException if the jar cannot be opened
     */
    private static List<MigrationScript> scanJar(URI uri, String resourcePath) {
        FileSystem fileSystem = null;
        boolean created = false;
        try {
            try {
                fileSystem = FileSystems.newFileSystem(uri, Map.of());
                created = true;
            } catch (FileSystemAlreadyExistsException e) {
                fileSystem = FileSystems.getFileSystem(uri);
            }
            return scanFilesystem(fileSystem.provider().getPath(uri));
        } catch (IOException e) {
            throw new SchemaMigrationException("Migration path '" + resourcePath + "' could not be opened", e);
        } finally {
            if (created) {
                try {
                    fileSystem.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Reads every {@code V<version>__<description>.sql} file of a directory.
     *
     * @param directory the directory to read
     * @return the scripts, each with its SHA-256 checksum, sorted by ascending version
     * @throws SchemaMigrationException if the path is not a directory
     * @throws UncheckedIOException if a file cannot be listed or read
     */
    private static List<MigrationScript> scanFilesystem(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new SchemaMigrationException("Migration path '" + directory + "' is not a directory");
        }

        List<MigrationScript> scripts = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.sql")) {
            for (Path file : stream) {
                Matcher matcher = SCRIPT_NAME_PATTERN.matcher(file.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                String content = Files.readString(file, StandardCharsets.UTF_8);
                scripts.add(new MigrationScript(
                        Integer.parseInt(matcher.group(1)),
                        matcher.group(2).replace('_', ' '),
                        file.getFileName().toString(),
                        content,
                        ChecksumUtil.sha256(content)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        scripts.sort(Comparator.comparingInt(MigrationScript::getVersion));
        return scripts;
    }
}
