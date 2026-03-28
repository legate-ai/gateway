package io.legate.core.event;

import java.time.Instant;

/**
 * Event fired when a cache miss occurs.
 */
public record CacheMissEvent(
        String requestId,
        Instant timestamp,
        String cacheKey
) implements LegateEvent {
    public CacheMissEvent(String requestId, String cacheKey) {
        this(requestId, Instant.now(), cacheKey);
    }
}
