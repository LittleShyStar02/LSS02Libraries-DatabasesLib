package io.github.littleshystar02.lss02.databases.bootstrap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MigrationScanner {

    private static final Pattern SCRIPT_NAME_PATTERN = Pattern.compile("^V(\\d+)__(.+)\\.sql$");
    private static final String CLASSPATH_PREFIX = "classpath:";

    private MigrationScanner() {
    }

    public static List<MigrationScript> scan(String location) {
        if (location == null) {
            return List.of();
        }
        if (location.startsWith(CLASSPATH_PREFIX)) {
            return scanClasspath(location.substring(CLASSPATH_PREFIX.length()));
        }
        return scanFilesystem(Paths.get(location));
    }

    private static List<MigrationScript> scanClasspath(String resourcePath) {
        URL url = Thread.currentThread().getContextClassLoader().getResource(resourcePath);
        if (url == null) {
            throw new SchemaMigrationException("Migration path '" + resourcePath + "' was not found on the classpath");
        }
        try {
            return scanFilesystem(Paths.get(url.toURI()));
        } catch (URISyntaxException e) {
            throw new SchemaMigrationException("Migration path '" + resourcePath + "' could not be resolved", e);
        }
    }

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
