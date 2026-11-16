// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.migration.data;

import io.github.littleshystar02.lss02.databases.migration.DatabaseMigrationException;

import java.sql.Connection;
import java.sql.SQLException;

/** Quotes SQL identifiers (table and column names) according to the dialect of a connection. */
public final class SqlIdentifiers {

    /** Not instantiable: this class only holds static helpers. */
    private SqlIdentifiers() {
    }

    /**
     * Quotes an identifier with the quote string reported by the connection's metadata, doubling any
     * quote character inside the name so that it cannot terminate the identifier early.
     *
     * @param connection the connection whose dialect decides the quote character
     * @param identifier the raw name
     * @return the quoted identifier; unchanged only when the driver reports that it cannot quote
     * @throws DatabaseMigrationException if the name is empty, contains a NUL character, or the metadata
     *         cannot be read
     */
    public static String quote(Connection connection, String identifier) {
        if (identifier == null || identifier.isEmpty() || identifier.indexOf('\0') >= 0) {
            throw new DatabaseMigrationException("Invalid SQL identifier: " + (identifier == null ? "null" : "'" + identifier + "'"));
        }
        String quote;
        try {
            quote = connection.getMetaData().getIdentifierQuoteString();
        } catch (SQLException e) {
            throw new DatabaseMigrationException("Could not read the identifier quote string of the connection", e);
        }
        if (quote == null || quote.isBlank()) {
            // The driver cannot quote: accept only plain names so nothing can be injected.
            if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new DatabaseMigrationException("The database cannot quote identifiers and '" + identifier + "' is not a plain name");
            }
            return identifier;
        }
        quote = quote.trim();
        return quote + identifier.replace(quote, quote + quote) + quote;
    }
}
