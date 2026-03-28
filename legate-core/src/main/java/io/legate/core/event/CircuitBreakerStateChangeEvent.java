package io.legate.core.event;

import java.time.Instant;

/**
 * Event fired when a circuit breaker changes state.
 */
public record CircuitBreakerStateChangeEvent(
        String requestId,
        Instant timestamp,
        String provider,
        String model,
        String fromState,
        String toState,
        String reason
) implements LegateEvent {
    public CircuitBreakerStateChangeEvent(
            String provider,
            String model,
            String fromState,
            String toState,
            String reason
    ) {
        this(null, Instant.now(), provider, model, fromState, toState, reason);
    }
}
