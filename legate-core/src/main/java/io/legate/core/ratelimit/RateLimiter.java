package io.legate.core.ratelimit;

/**
 * SPI for request and token-based rate limiting.
 *
 * <p>Two independent rate-limit axes are tracked per key:</p>
 * <ol>
 *   <li><b>Request rate</b> — maximum requests per minute (token bucket).</li>
 *   <li><b>Token quota</b> — maximum LLM tokens per day (rolling counter).</li>
 * </ol>
 *
 * <p>The typical flow is:</p>
 * <pre>{@code
 * RateLimitResult result = rateLimiter.tryAcquire(virtualKeyId, estimatedInputTokens);
 * if (result instanceof RateLimitResult.Denied d) {
 *     throw new RateLimitExceededException(d.reason(), d.retryAfter());
 * }
 * // ... call upstream ...
 * rateLimiter.reportUsage(virtualKeyId, actualTokens);
 * }</pre>
 *
 * <p>All implementations must be thread-safe.</p>
 */
public interface RateLimiter {

    /**
     * Checks whether the given key is within its rate limits, and if so, reserves capacity.
     *
     * @param key             the rate-limit key (typically the virtual key ID)
     * @param estimatedTokens estimated number of tokens for this request (for pre-check)
     * @return {@link RateLimitResult.Allowed} if the request may proceed;
     * {@link RateLimitResult.Denied} with reason if the limit is exceeded
     */
    RateLimitResult tryAcquire(String key, int estimatedTokens);

    /**
     * Reports actual token usage after a successful upstream call.
     *
     * @param key          the rate-limit key
     * @param actualTokens actual tokens consumed (prompt + completion)
     */
    void reportUsage(String key, int actualTokens);

    /**
     * Adjusts a pre-reservation made during {@link #tryAcquire} to the actual usage.
     * The net change applied to the token bucket is {@code actualTokens - reservedTokens}.
     *
     * @param key            the rate-limit key
     * @param reservedTokens tokens reserved during {@code tryAcquire} (from {@link RateLimitResult.Allowed#reservedTokens()})
     * @param actualTokens   actual tokens consumed
     */
    default void reportUsage(String key, int reservedTokens, int actualTokens) {
        // Default: ignore the reservation, just record actual usage.
        // Override in implementations that pre-reserve.
        reportUsage(key, actualTokens);
    }
}
