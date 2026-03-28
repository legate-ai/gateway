package io.legate.core.config.ratelimit;

import java.util.Map;

/**
 * Rate-limiting configuration applied across all traffic.
 *
 * <p>Two independent token buckets are enforced per virtual key (and globally):</p>
 * <ol>
 *   <li><b>Request bucket</b> — limits the number of API calls per minute.</li>
 *   <li><b>Token bucket</b> — limits cumulative token consumption per day.</li>
 * </ol>
 *
 * <p>A request is denied when <em>either</em> bucket is exhausted. Actual token
 * usage is reported post-response via {@code reportUsage()} so that the pre-request
 * estimate uses {@code max_tokens} as a conservative upper bound.</p>
 *
 * <h3>Precedence</h3>
 * <p>For a given virtual key, both the global limit AND the per-key limit are
 * enforced independently. The most restrictive limit wins.</p>
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   rate-limiting:
 *     global:
 *       requests-per-minute: 1000
 *       tokens-per-day: 100000000
 *     per-virtual-key-default:
 *       requests-per-minute: 100
 *       tokens-per-day: 1000000
 *     overrides:
 *       wdn_live_team_a:
 *         requests-per-minute: 500
 *         tokens-per-day: 10000000
 * }</pre>
 */
public record RateLimitingConfig(

    /**
     * Global rate limit applied across all requests, regardless of virtual key.
     * {@code null} means no global limit is applied.
     */
    RateLimitConfig global,

    /**
     * Default per-virtual-key rate limit applied when no key-specific override exists.
     * {@code null} means virtual keys are not rate-limited by default.
     */
    RateLimitConfig perVirtualKeyDefault,

    /**
     * Per-key overrides. The map key is the virtual key ID (full or prefix).
     * Overrides take precedence over {@link #perVirtualKeyDefault()}.
     */
    Map<String, RateLimitConfig> overrides

) {
    public RateLimitingConfig {
        if (overrides == null) {
            overrides = Map.of();
        }
    }

    /**
     * Resolves the effective rate limit for a given virtual key ID.
     *
     * <p>Lookup order: key-specific override → {@code perVirtualKeyDefault}.</p>
     *
     * @param keyId the virtual key ID to look up
     * @return the effective per-key limit, or {@link RateLimitConfig#unlimited()} if none configured
     */
    public RateLimitConfig resolvePerKeyLimit(String keyId) {
        RateLimitConfig override = overrides.get(keyId);
        if (override != null) {
            return override;
        }
        return perVirtualKeyDefault != null ? perVirtualKeyDefault : RateLimitConfig.unlimited();
    }

    /** Returns a config with no rate limiting applied anywhere. */
    public static RateLimitingConfig disabled() {
        return new RateLimitingConfig(null, null, Map.of());
    }

    // -------------------------------------------------------------------------

    /**
     * Dual-bucket rate limit parameters for one subject (global or a single virtual key).
     *
     * @param requestsPerMinute maximum requests per minute; {@code 0} = unlimited
     * @param tokensPerDay      maximum tokens consumed per day; {@code 0L} = unlimited
     */
    public record RateLimitConfig(
        int requestsPerMinute,
        long tokensPerDay
    ) {
        /** An unlimited rate limit — no restrictions applied. */
        public static RateLimitConfig unlimited() {
            return new RateLimitConfig(0, 0L);
        }

        /** Returns {@code true} when neither bucket imposes a restriction. */
        public boolean isUnlimited() {
            return requestsPerMinute == 0 && tokensPerDay == 0L;
        }

        /** Returns {@code true} when a requests-per-minute limit is configured. */
        public boolean hasRequestLimit() {
            return requestsPerMinute > 0;
        }

        /** Returns {@code true} when a tokens-per-day limit is configured. */
        public boolean hasTokenLimit() {
            return tokensPerDay > 0L;
        }
    }
}
