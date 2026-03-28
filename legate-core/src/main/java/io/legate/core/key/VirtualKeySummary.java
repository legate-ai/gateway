package io.legate.core.key;

import java.time.Instant;
import java.util.List;

/**
 * Summary of a virtual key — safe to return in API responses (no sensitive values).
 */
public record VirtualKeySummary(

        /** Unique key identifier. */
        String keyId,

        /** Team or application name. */
        String teamName,

        /**
         * First 8 characters of the key followed by {@code "..."}.
         * Example: {@code "wdn_live..."}.
         * Safe to display in dashboards.
         */
        String keyPrefix,

        /** Timestamp when the key was created. */
        Instant createdAt,

        /** Whether this key has been revoked and can no longer be used. */
        boolean revoked,

        /** Allowed model patterns. */
        List<String> allowedModels,

        /** Denied model patterns. */
        List<String> deniedModels

) {
}
