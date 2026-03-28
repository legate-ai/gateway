package io.legate.core.config.routing;

/**
 * Backoff strategy applied between retry attempts.
 *
 * <p>YAML usage:</p>
 * <pre>{@code
 * legate:
 *   routing:
 *     retry:
 *       backoff: exponential   # or EXPONENTIAL, NONE, FIXED
 * }</pre>
 */
public enum BackoffStrategy {

    /**
     * No delay between retries. Suitable for fast-fail scenarios where
     * the next endpoint in the fallback chain is a completely different
     * provider (failover, not retry of same endpoint).
     */
    NONE,

    /**
     * A constant delay of {@code initialDelay} between every retry.
     * Use when the upstream requires a predictable cooldown.
     */
    FIXED,

    /**
     * Delay doubles after each retry: {@code initialDelay * 2^attempt},
     * capped at {@code maxDelay}.
     * Use for transient overload scenarios (e.g., 429 rate limits).
     */
    EXPONENTIAL
}
