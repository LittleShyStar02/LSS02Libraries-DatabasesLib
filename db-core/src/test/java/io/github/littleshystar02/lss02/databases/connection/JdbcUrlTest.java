// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.connection;

import io.github.littleshystar02.lss02.databases.cluster.ClusterConfigurationException;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests for the JDBC URL construction and validation of {@link DataSourceFactory}. */
class JdbcUrlTest {

    /**
     * Builds a MySQL definition.
     *
     * @param host the host
     * @param database the database name
     * @param options the options
     * @return the definition
     */
    private DatabaseDefinition mysql(String host, String database, Map<String, String> options) {
        return DatabaseDefinition.builder().name("m").type(DatabaseType.MYSQL)
                .host(host).database(database).options(options).build();
    }

    /** Option values with URL metacharacters are encoded and cannot add parameters. */
    @Test
    void encodesOptionValues() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("serverTimezone", "UTC&allowLoadLocalInfile=true");
        options.put("note", "a?b=c;d e");
        String url = DataSourceFactory.resolveJdbcUrl(mysql("127.0.0.1", "shop", options));

        assertEquals("jdbc:mysql://127.0.0.1:3306/shop?serverTimezone=UTC%26allowLoadLocalInfile%3Dtrue&note=a%3Fb%3Dc%3Bd+e", url);
    }

    /** Option names must be plain identifiers. */
    @Test
    void rejectsOptionNamesWithSpecialCharacters() {
        Map<String, String> options = Map.of("x=1&allowMultiQueries", "true");
        assertThrows(ClusterConfigurationException.class,
                () -> DataSourceFactory.resolveJdbcUrl(mysql("127.0.0.1", "shop", options)));
    }

    /** Host and database names cannot carry URL syntax. */
    @Test
    void rejectsHostAndDatabaseWithUrlSyntax() {
        assertThrows(ClusterConfigurationException.class,
                () -> DataSourceFactory.resolveJdbcUrl(mysql("evil.com/db?x=", "shop", null)));
        assertThrows(ClusterConfigurationException.class,
                () -> DataSourceFactory.resolveJdbcUrl(mysql("127.0.0.1", "shop?allowMultiQueries=true", null)));
        assertThrows(ClusterConfigurationException.class,
                () -> DataSourceFactory.resolveJdbcUrl(mysql("127.0.0.1", "a;b", null)));
    }

    /** A normal host, an IPv6 literal and a database with allowed punctuation pass. */
    @Test
    void acceptsRegularNames() {
        assertEquals("jdbc:postgresql://[::1]:5432/my-db_1.x",
                DataSourceFactory.resolveJdbcUrl(DatabaseDefinition.builder().name("p").type(DatabaseType.POSTGRESQL)
                        .host("[::1]").database("my-db_1.x").build()));
    }

    /** A SQLite path with a query separator is rejected. */
    @Test
    void rejectsSqlitePathWithQuery() {
        DatabaseDefinition definition = DatabaseDefinition.builder().name("s").type(DatabaseType.SQLITE)
                .filePath("data.db?mode=ro").build();
        assertThrows(ClusterConfigurationException.class, () -> DataSourceFactory.resolveJdbcUrl(definition));
    }
}