package io.legate.core.key;

/**
 * Result of creating a new virtual key.
 *
 * <p>The {@link #plaintextKey()} is returned exactly <em>once</em> at creation time
 * and is never stored or retrievable again. The caller must save it securely.</p>
 */
public record VirtualKeyCreateResult(

        /** The key's unique identifier (e.g., {@code wdn_key_xxxxxxxx}). */
        String keyId,

        /**
         * The plaintext key value, shown once on creation.
         * Format: {@code wdn_live_<32-random-chars>}.
         */
        String plaintextKey

) {
}
