package io.legate.core.event;

import java.time.Instant;

/**
 * Event fired when a request is first received.
 */
public record RequestReceivedEvent(
        String requestId,
        Instant timestamp,
        String model,
        String virtualKeyId,
        boolean streaming
) implements LegateEvent {
    public RequestReceivedEvent(String requestId, String model, String virtualKeyId, boolean streaming) {
        this(requestId, Instant.now(), model, virtualKeyId, streaming);
    }
}
