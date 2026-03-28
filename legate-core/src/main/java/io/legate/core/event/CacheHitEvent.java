package io.legate.core.event;

import java.time.Instant;

/**
 * Event fired when a cache hit occurs.
 */
public record CacheHitEvent(
        String requestId,
        Instant timestamp,
        String cacheKey,
        long savedLatencyMs
) implements LegateEvent {
    public CacheHitEvent(String requestId, String cacheKey, long savedLatencyMs) {
        this(requestId, Instant.now(), cacheKey, savedLatencyMs);
    }
}
