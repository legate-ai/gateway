package io.legate.core.event;

import java.time.Instant;

/**
 * Event fired when an upstream provider call starts.
 */
public record UpstreamCallStartedEvent(
        String requestId,
        Instant timestamp,
        String provider,
        String model,
        String endpoint
) implements LegateEvent {
    public UpstreamCallStartedEvent(String requestId, String provider, String model, String endpoint) {
        this(requestId, Instant.now(), provider, model, endpoint);
    }
}
