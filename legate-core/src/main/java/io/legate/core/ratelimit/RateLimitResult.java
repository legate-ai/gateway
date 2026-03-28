package io.legate.core.ratelimit;

import java.time.Instant;

/**
 * Result of a rate-limit check.
 *
 * <p>Use pattern matching to handle each outcome:</p>
 * <pre>{@code
 * switch (result) {
 *     case RateLimitResult.Allowed a -> proceed(a.remainingRequests());
 *     case RateLimitResult.Denied  d -> rejectWith429(d.retryAfter());
 * }
 * }</pre>
 */
public sealed interface RateLimitResult {

    /**
     * The request is within rate limits and may proceed.
     *
     * @param remainingRequests remaining request quota in the current window
     * @param remainingTokens   remaining token quota today
     * @param resetsAt          when the request quota resets
     */
    record Allowed(
            int remainingRequests,
            long remainingTokens,
            Instant resetsAt
    ) implements RateLimitResult {
    }

    /**
     * The request exceeds a rate limit and must be rejected.
     *
     * @param reason     human-readable explanation
     * @param retryAfter suggested wait time in seconds before retrying
     * @param limit      the limit value that was exceeded
     * @param current    the current value at time of rejection
     */
    record Denied(
            String reason,
            long retryAfter,
            long limit,
            long current
    ) implements RateLimitResult {
    }
}
