package io.legate.core.key;

/**
 * SPI for hashing and verifying virtual key plaintexts.
 *
 * <p>The default implementation ({@link Sha256VirtualKeyHasher}) uses SHA-256 for
 * backward compatibility. Override with {@code BcryptVirtualKeyHasher} (from
 * {@code legate-spring-boot-starter}) for production deployments, which uses
 * bcrypt with a work factor of 12.</p>
 *
 * <h3>Implementation contract</h3>
 * <ul>
 *   <li>{@link #hash} must produce a self-identifying string (e.g., a prefix like
 *       {@code sha256:} or a BCrypt header like {@code $2a$}) so that legacy hashes
 *       stored on disk can still be verified after an upgrade.</li>
 *   <li>{@link #verify} must be constant-time to prevent timing attacks.</li>
 * </ul>
 */
public interface VirtualKeyHasher {

    /**
     * Hashes a plaintext key for storage.
     *
     * @param plaintext the raw virtual key; must not be null
     * @return an opaque hash string suitable for persistent storage
     */
    String hash(String plaintext);

    /**
     * Verifies a plaintext key against a stored hash.
     *
     * @param plaintext  the raw virtual key to verify; must not be null
     * @param storedHash the hash previously produced by {@link #hash(String)}
     * @return {@code true} if the plaintext matches the stored hash
     */
    boolean verify(String plaintext, String storedHash);
}
