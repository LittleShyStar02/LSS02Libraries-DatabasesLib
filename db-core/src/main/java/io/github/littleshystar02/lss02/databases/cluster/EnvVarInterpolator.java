package io.github.littleshystar02.lss02.databases.cluster;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code ${NAME}} placeholders in configuration text with the value of the matching
 * environment variable.
 *
 * <p>It keeps secrets such as database passwords out of the YAML file. A placeholder whose variable is
 * not defined is an error rather than an empty string, so a wrong deployment fails at startup.
 */
public final class EnvVarInterpolator {

    /**
     * Matches a {@code ${NAME}} placeholder; group 1 is the variable name (letters, digits and underscore).
     */
    private static final Pattern PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    /** Not instantiable: this class only holds static helpers. */
    private EnvVarInterpolator() {
    }

    /**
     * Resolves placeholders against the process environment.
     *
     * @param value the text that may contain placeholders, may be {@code null}
     * @return the text with every placeholder replaced, {@code null} if {@code value} is {@code null}
     * @throws ClusterConfigurationException if a referenced variable is not set
     */
    public static String interpolate(String value) {
        return interpolate(value, System::getenv);
    }

    /**
     * Resolves placeholders with a custom lookup, which makes the behavior testable without touching the
     * real environment.
     *
     * @param value the text that may contain placeholders, may be {@code null}
     * @param resolver returns the value of a variable name, or {@code null} when it is not defined
     * @return the text with every placeholder replaced, {@code null} if {@code value} is {@code null}
     * @throws ClusterConfigurationException if the resolver returns {@code null} for a referenced variable
     */
    public static String interpolate(String value, Function<String, String> resolver) {
        if (value == null) {
            return null;
        }
        Matcher matcher = PATTERN.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String variableName = matcher.group(1);
            String resolved = resolver.apply(variableName);
            if (resolved == null) {
                throw new ClusterConfigurationException(
                        "Environment variable '" + variableName + "' referenced in the cluster configuration is not set");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
