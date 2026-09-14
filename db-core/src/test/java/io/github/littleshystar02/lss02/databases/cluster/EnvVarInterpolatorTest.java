package io.github.littleshystar02.lss02.databases.cluster;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvVarInterpolatorTest {

    @Test
    void returnsNullUnchanged() {
        assertNull(EnvVarInterpolator.interpolate(null, key -> "irrelevant"));
    }

    @Test
    void leavesPlainStringsUnchanged() {
        assertEquals("localhost", EnvVarInterpolator.interpolate("localhost", key -> "irrelevant"));
    }

    @Test
    void substitutesAKnownVariable() {
        String result = EnvVarInterpolator.interpolate("${DB_PASSWORD}", Map.of("DB_PASSWORD", "secret")::get);
        assertEquals("secret", result);
    }

    @Test
    void substitutesAVariableEmbeddedInAString() {
        String result = EnvVarInterpolator.interpolate("jdbc:mysql://${DB_HOST}:3306/app", Map.of("DB_HOST", "db.internal")::get);
        assertEquals("jdbc:mysql://db.internal:3306/app", result);
    }

    @Test
    void throwsWhenTheVariableIsNotSet() {
        assertThrows(ClusterConfigurationException.class,
                () -> EnvVarInterpolator.interpolate("${MISSING}", key -> null));
    }
}
