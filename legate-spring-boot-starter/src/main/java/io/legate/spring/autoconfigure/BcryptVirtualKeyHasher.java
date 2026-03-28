package io.legate.spring.autoconfigure;

import io.legate.core.key.Sha256VirtualKeyHasher;
import io.legate.core.key.VirtualKeyHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt-backed {@link VirtualKeyHasher} using Spring Security Crypto.
 *
 * <p>Uses {@link BCryptPasswordEncoder} with a work factor of 12. This is Legate's
 * default production hasher, registered automatically by
 * {@link LegateAutoConfiguration}.</p>
 *
 * <h3>Backward compatibility</h3>
 * <p>If an existing key hash starts with the legacy {@code sha256:} prefix
 * (written by {@link Sha256VirtualKeyHasher}), it is verified using SHA-256
 * so that deployments can migrate incrementally. New keys are always stored as
 * BCrypt hashes.</p>
 */
public class BcryptVirtualKeyHasher implements VirtualKeyHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final Sha256VirtualKeyHasher legacyHasher = new Sha256VirtualKeyHasher();

    @Override
    public String hash(String plaintext) {
        return encoder.encode(plaintext);
    }

    @Override
    public boolean verify(String plaintext, String storedHash) {
        if (storedHash == null) {
            return false;
        }
        // BCrypt hashes start with $2a$, $2b$, or $2y$
        if (storedHash.startsWith("$2")) {
            return encoder.matches(plaintext, storedHash);
        }
        // Legacy SHA-256 hash — verify for backward compatibility during migration
        if (storedHash.startsWith(Sha256VirtualKeyHasher.PREFIX)) {
            return legacyHasher.verify(plaintext, storedHash);
        }
        return false;
    }
}
