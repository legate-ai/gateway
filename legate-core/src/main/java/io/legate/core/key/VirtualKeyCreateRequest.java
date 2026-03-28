package io.legate.core.key;

import io.legate.core.context.VirtualKeyInfo;

import java.util.List;
import java.util.Map;

/**
 * Request to create a new virtual key.
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * VirtualKeyCreateRequest req = new VirtualKeyCreateRequest(
 *     "team-a",
 *     List.of("gpt-*", "claude-*"),
 *     List.of(),
 *     new VirtualKeyInfo.RateLimitInfo(100, 1_000_000),
 *     new VirtualKeyInfo.SpendLimitInfo(new BigDecimal("50.00"), new BigDecimal("500.00")),
 *     Map.of("env", "production")
 * );
 * }</pre>
 */
public record VirtualKeyCreateRequest(

        /** The team or application name this key belongs to. */
        String teamName,

        /**
         * Glob patterns for allowed model names.
         * Examples: {@code ["gpt-*", "claude-*"]}, {@code ["gpt-4o"]}.
         * If empty, no models are allowed.
         */
        List<String> allowedModels,

        /**
         * Glob patterns for explicitly denied model names.
         * Deny rules take precedence over allow rules.
         */
        List<String> deniedModels,

        /** Rate limit settings for this key. May be null for no limits. */
        VirtualKeyInfo.RateLimitInfo rateLimits,

        /** Spend limit settings for this key. May be null for no limits. */
        VirtualKeyInfo.SpendLimitInfo spendLimits,

        /** Arbitrary key-value metadata. */
        Map<String, String> metadata

) {

    public VirtualKeyCreateRequest {
        if (allowedModels == null) {
            allowedModels = List.of();
        }
        if (deniedModels == null) {
            deniedModels = List.of();
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }
}
