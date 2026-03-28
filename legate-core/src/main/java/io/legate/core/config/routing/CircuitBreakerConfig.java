package io.legate.core.config.routing;

import java.time.Duration;

/**
 * Per-endpoint circuit-breaker configuration.
 *
 * <p>State machine:</p>
 * <pre>
 *   CLOSED ──(failures ≥ failureThreshold)──► OPEN
 *   OPEN ──(waitDuration elapsed)──────────► HALF_OPEN
 *   HALF_OPEN ──(successes ≥ successThreshold)──► CLOSED
 *   HALF_OPEN ──(any failure)───────────────► OPEN
 * </pre>
 *
 * <p>Failure counting uses a sliding window of the last {@code slidingWindowSize} calls.
 * Only calls that complete (success or failure) within the window count — in-flight
 * calls do not.</p>
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   routing:
 *     circuit-breaker:
 *       failure-threshold: 5
 *       success-threshold: 2
 *       wait-duration: 60s
 *       sliding-window-size: 10
 * }</pre>
 */
public record CircuitBreakerConfig(

    /**
     * Number of failures within the sliding window required to open the circuit.
     * Default: {@code 5}.
     */
    int failureThreshold,

    /**
     * Number of consecutive successful calls in {@code HALF_OPEN} state required
     * to close the circuit. Default: {@code 2}.
     */
    int successThreshold,

    /**
     * How long the circuit remains {@code OPEN} before transitioning to
     * {@code HALF_OPEN} and allowing a probe request. Default: {@code 60 s}.
     */
    Duration waitDuration,

    /**
     * Size of the sliding window over which failures are counted.
     * A larger window smooths out transient spikes; a smaller window reacts faster.
     * Default: {@code 10} calls.
     */
    int slidingWindowSize

) {
    public CircuitBreakerConfig {
        if (failureThreshold < 1) {
            failureThreshold = 5;
        }
        if (successThreshold < 1) {
            successThreshold = 2;
        }
        if (waitDuration == null) {
            waitDuration = Duration.ofSeconds(60);
        }
        if (slidingWindowSize < 1) {
            slidingWindowSize = 10;
        }
    }

    /** Default circuit-breaker configuration. */
    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(5, 2, Duration.ofSeconds(60), 10);
    }
}
