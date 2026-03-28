package io.legate.core.event;

import java.time.Instant;

/**
 * Event fired when a guard makes a decision.
 */
public record GuardDecisionEvent(
        String requestId,
        Instant timestamp,
        String guardName,
        String decision, // "allow", "block", "modify", "warn"
        String reason
) implements LegateEvent {
    public GuardDecisionEvent(String requestId, String guardName, String decision, String reason) {
        this(requestId, Instant.now(), guardName, decision, reason);
    }
}
