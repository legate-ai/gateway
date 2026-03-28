package io.legate.core.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An immutable audit event recorded in the {@link AuditLogger}.
 *
 * @param eventId      unique ID for this audit event
 * @param timestamp    when the event occurred
 * @param requestId    Legate request ID ({@code req_xxx}); may be {@code null} for non-request events
 * @param virtualKeyId virtual key that triggered this event; may be {@code null}
 * @param type         the event type
 * @param description  human-readable summary
 * @param details      arbitrary key-value pairs with additional context
 */
public record AuditEvent(
    String eventId,
    Instant timestamp,
    String requestId,
    String virtualKeyId,
    AuditEventType type,
    String description,
    Map<String, Object> details
) {
    public AuditEvent {
        if (eventId == null) {
            eventId = UUID.randomUUID().toString();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (details == null) {
            details = Map.of();
        }
    }

    /** Convenience factory for audit events with a request ID. */
    public static AuditEvent of(
        String requestId,
        String virtualKeyId,
        AuditEventType type,
        String description
    ) {
        return new AuditEvent(null, null, requestId, virtualKeyId, type, description, Map.of());
    }

    /** Convenience factory with details map. */
    public static AuditEvent of(
        String requestId,
        String virtualKeyId,
        AuditEventType type,
        String description,
        Map<String, Object> details
    ) {
        return new AuditEvent(null, null, requestId, virtualKeyId, type, description, details);
    }
}
