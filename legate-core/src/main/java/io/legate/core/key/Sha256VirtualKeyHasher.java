package io.legate.core.key;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256–based {@link VirtualKeyHasher}.
 *
 * <p>Provides fast, deterministic hashing via the {@code sha256:} prefix format.
 * Suitable for development and testing environments. For production deployments use
 * {@code BcryptVirtualKeyHasher} from {@code legate-spring-boot-starter}, which
 * applies bcrypt with a work factor of 12 and is resistant to brute-force attacks.</p>
 *
 * <p>Hash format: {@code sha256:<hex-encoded-256-bit-digest>}</p>
 */
public class Sha256VirtualKeyHasher implements VirtualKeyHasher {

    public static final String PREFIX = "sha256:";

    @Override
    public String hash(String plaintext) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    }

    @Override
    public boolean verify(String plaintext, String storedHash) {
        if (storedHash == null || !storedHash.startsWith(PREFIX)) {
            return false;
        }
        String computed = hash(plaintext);
        // Constant-time comparison to prevent timing attacks
        return MessageDigest.isEqual(
            storedHash.getBytes(StandardCharsets.UTF_8),
            computed.getBytes(StandardCharsets.UTF_8)
        );
    }
}
