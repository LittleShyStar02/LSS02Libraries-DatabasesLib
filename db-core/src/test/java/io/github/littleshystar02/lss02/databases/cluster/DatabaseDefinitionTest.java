// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.cluster;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the log safety and immutability of {@link DatabaseDefinition}. */
class DatabaseDefinitionTest {

    /** The text form never contains the password, nor the query string of the JDBC URL. */
    @Test
    void toStringHidesPasswordAndUrlParameters() {
        DatabaseDefinition definition = DatabaseDefinition.builder()
                .name("main")
                .type(DatabaseType.MYSQL)
                .username("app")
                .password("s3cret-pw")
                .jdbcUrl("jdbc:mysql://db:3306/shop?user=app&password=urlsecret")
                .options(Map.of("token", "optsecret"))
                .build();

        String text = definition.toString();
        assertFalse(text.contains("s3cret-pw"));
        assertFalse(text.contains("urlsecret"));
        assertFalse(text.contains("optsecret"));
        assertTrue(text.contains("password=***"));
        assertTrue(text.contains("name=main"));
    }

    /** Changing the map given to the builder, or the map of the definition, is not possible. */
    @Test
    void optionsAreCopiedAndUnmodifiable() {
        Map<String, String> options = new HashMap<>();
        options.put("a", "1");
        DatabaseDefinition definition = DatabaseDefinition.builder().name("x").options(options).build();

        options.put("b", "2");
        assertFalse(definition.getOptions().containsKey("b"));
        assertThrows(UnsupportedOperationException.class, () -> definition.getOptions().put("c", "3"));
    }
}