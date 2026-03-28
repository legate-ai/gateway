package io.legate.core.event;

import java.time.Instant;

/**
 * Event fired when an upstream provider call completes.
 */
public record UpstreamCallCompletedEvent(
        String requestId,
        Instant timestamp,
        String provider,
        String model,
        int statusCode,
        Long latencyMs,
        boolean success
) implements LegateEvent {
    public UpstreamCallCompletedEvent(
            String requestId,
            String provider,
            String model,
            int statusCode,
            Long latencyMs,
            boolean success
    ) {
        this(requestId, Instant.now(), provider, model, statusCode, latencyMs, success);
    }
}
