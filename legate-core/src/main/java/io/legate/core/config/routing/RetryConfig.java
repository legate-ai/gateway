package io.legate.core.config.routing;

import java.time.Duration;

/**
 * Retry policy applied when an upstream call fails with a transient error
 * (5xx response, 429 rate-limit, or network timeout).
 *
 * <p>Each retry advances to the next endpoint in the fallback chain. If no additional
 * endpoints remain the exception is propagated to the client.</p>
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   routing:
 *     retry:
 *       max-attempts: 3
 *       backoff: exponential
 *       initial-delay: 200ms
 *       max-delay: 5s
 *       backoff-multiplier: 2.0
 * }</pre>
 */
public record RetryConfig(

    /**
     * Total number of attempts (initial call + retries).
     * A value of {@code 1} disables retries entirely.
     * A value of {@code 3} means 1 initial call and up to 2 retries.
     * Default: {@code 2}.
     */
    int maxAttempts,

    /**
     * Backoff strategy between retry attempts.
     * Default: {@link BackoffStrategy#NONE}.
     */
    BackoffStrategy backoff,

    /**
     * Delay before the first retry (and the base for subsequent retries in
     * {@link BackoffStrategy#EXPONENTIAL} mode).
     * Default: {@code 500 ms}.
     */
    Duration initialDelay,

    /**
     * Maximum delay cap for {@link BackoffStrategy#EXPONENTIAL} backoff.
     * The computed delay is clamped to this value.
     * Default: {@code 5 s}.
     */
    Duration maxDelay,

    /**
     * Multiplier applied to the delay on each subsequent retry when using
     * {@link BackoffStrategy#EXPONENTIAL}.
     * Delay for attempt {@code n} = {@code initialDelay * backoffMultiplier^(n-1)},
     * capped at {@code maxDelay}.
     * Default: {@code 2.0} (doubles each time).
     */
    double backoffMultiplier

) {
    public RetryConfig {
        if (maxAttempts < 1) {
            maxAttempts = 1;
        }
        if (backoff == null) {
            backoff = BackoffStrategy.NONE;
        }
        if (initialDelay == null) {
            initialDelay = Duration.ofMillis(500);
        }
        if (maxDelay == null) {
            maxDelay = Duration.ofSeconds(5);
        }
        if (backoffMultiplier <= 0) {
            backoffMultiplier = 2.0;
        }
    }

    /** Default retry config — two total attempts (1 retry), no backoff delay. */
    public static RetryConfig defaults() {
        return new RetryConfig(2, BackoffStrategy.NONE, Duration.ofMillis(500), Duration.ofSeconds(5), 2.0);
    }

    /** Returns {@code true} when retries are enabled ({@code maxAttempts > 1}). */
    public boolean isEnabled() {
        return maxAttempts > 1;
    }

    /**
     * Computes the delay before a given retry attempt.
     *
     * @param attemptNumber 1-based retry attempt number (1 = first retry)
     * @return the computed delay, clamped to {@code maxDelay}
     */
    public Duration computeDelay(int attemptNumber) {
        return switch (backoff) {
            case NONE        -> Duration.ZERO;
            case FIXED       -> initialDelay;
            case EXPONENTIAL -> {
                double ms = initialDelay.toMillis() * Math.pow(backoffMultiplier, attemptNumber - 1);
                yield Duration.ofMillis(Math.min((long) ms, maxDelay.toMillis()));
            }
        };
    }
}
