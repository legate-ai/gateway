package io.legate.core.event;

import java.time.Instant;

/**
 * Event fired when a rate limit is breached.
 */
public record RateLimitBreachedEvent(
        String requestId,
        Instant timestamp,
        String virtualKeyId,
        String limitType, // "requests_per_minute", "tokens_per_day"
        long currentValue,
        long limitValue
) implements LegateEvent {
    public RateLimitBreachedEvent(
            String requestId,
            String virtualKeyId,
            String limitType,
            long currentValue,
            long limitValue
    ) {
        this(requestId, Instant.now(), virtualKeyId, limitType, currentValue, limitValue);
    }
}
