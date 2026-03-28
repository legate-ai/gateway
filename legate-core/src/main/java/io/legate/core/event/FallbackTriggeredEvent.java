package io.legate.core.event;

import java.time.Instant;

/**
 * Event fired when a fallback is triggered.
 */
public record FallbackTriggeredEvent(
        String requestId,
        Instant timestamp,
        String fromProvider,
        String toProvider,
        String reason,
        int attemptNumber
) implements LegateEvent {
    public FallbackTriggeredEvent(
            String requestId,
            String fromProvider,
            String toProvider,
            String reason,
            int attemptNumber
    ) {
        this(requestId, Instant.now(), fromProvider, toProvider, reason, attemptNumber);
    }
}
