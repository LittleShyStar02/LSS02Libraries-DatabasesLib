package io.github.littleshystar02.lss02.databases.bootstrap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes content checksums for migration scripts.
 *
 * <p>The checksum of every applied script is stored in the schema history table and compared on later
 * startups, which is how an edit to an already applied script is detected.
 */
public final class ChecksumUtil {

    /** Not instantiable: this class only holds static helpers. */
    private ChecksumUtil() {
    }

    /**
     * Computes the SHA-256 digest of a text.
     *
     * @param content the text to hash, encoded as UTF-8 before hashing
     * @return the digest as 64 lowercase hexadecimal characters
     * @throws IllegalStateException if the JVM does not provide the SHA-256 algorithm
     */
    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
