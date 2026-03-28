package io.legate.core.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event fired when a spend limit is breached.
 */
public record SpendLimitBreachedEvent(
        String requestId,
        Instant timestamp,
        String virtualKeyId,
        String limitType, // "daily", "monthly"
        BigDecimal currentSpend,
        BigDecimal limitValue
) implements LegateEvent {
    public SpendLimitBreachedEvent(
            String requestId,
            String virtualKeyId,
            String limitType,
            BigDecimal currentSpend,
            BigDecimal limitValue
    ) {
        this(requestId, Instant.now(), virtualKeyId, limitType, currentSpend, limitValue);
    }
}
