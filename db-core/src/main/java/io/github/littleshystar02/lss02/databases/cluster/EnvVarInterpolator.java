package io.github.littleshystar02.lss02.databases.cluster;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EnvVarInterpolator {

    private static final Pattern PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    private EnvVarInterpolator() {
    }

    public static String interpolate(String value) {
        return interpolate(value, System::getenv);
    }

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
