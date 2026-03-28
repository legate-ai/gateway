package io.legate.core.key;

import io.legate.core.context.VirtualKeyInfo;

import java.util.List;
import java.util.Optional;

/**
 * SPI for creating, resolving, and revoking virtual API keys.
 *
 * <p>Virtual keys are the primary authentication mechanism for Legate.
 * Clients include them in the {@code Authorization: Bearer <key>} header.
 * Legate validates them via {@link #resolve(String)} before processing requests.</p>
 *
 * <p>Default implementation: {@link FileBasedVirtualKeyStore} (stores hashed keys in YAML).
 * Phase 4 adds a PostgreSQL-backed implementation for distributed deployments.</p>
 *
 * <p>All methods must be thread-safe.</p>
 */
public interface VirtualKeyStore {

    /**
     * Resolves a raw key string to the associated {@link VirtualKeyInfo}.
     *
     * <p>Implementations must compare the key using a timing-safe algorithm (e.g., bcrypt)
     * to prevent timing attacks.</p>
     *
     * @param rawKey the plaintext key from the client {@code Authorization} header
     * @return the key info if the key is valid and not revoked; empty otherwise
     */
    Optional<VirtualKeyInfo> resolve(String rawKey);

    /**
     * Creates a new virtual key.
     *
     * @param request the key creation parameters
     * @return the result including the plaintext key (shown once only)
     */
    VirtualKeyCreateResult create(VirtualKeyCreateRequest request);

    /**
     * Revokes a key by its ID.
     *
     * <p>After revocation, subsequent calls to {@link #resolve(String)} for keys with
     * this ID will return empty.</p>
     *
     * @param keyId the ID of the key to revoke
     * @return {@code true} if the key was found and revoked; {@code false} if not found
     */
    boolean revoke(String keyId);

    /**
     * Returns a summary of all keys (including revoked keys), safe for API responses.
     *
     * @return unmodifiable list of key summaries; never null
     */
    List<VirtualKeySummary> list();
}
