package io.legate.core.audit;

import java.time.Instant;

/**
 * Query parameters for {@link AuditLogger#query(AuditQuery)}.
 *
 * <p>All fields are optional filters. {@code null} means "no filter on this dimension".</p>
 *
 * @param from         earliest timestamp (inclusive); {@code null} = no lower bound
 * @param to           latest timestamp (inclusive); {@code null} = no upper bound
 * @param virtualKeyId filter by virtual key ID; {@code null} = all keys
 * @param type         filter by event type; {@code null} = all types
 * @param limit        max number of results (default: 50, max: 1000)
 * @param offset       number of results to skip for pagination (default: 0)
 */
public record AuditQuery(
    Instant from,
    Instant to,
    String virtualKeyId,
    AuditEventType type,
    int limit,
    int offset
) {
    public AuditQuery {
        if (limit <= 0) {
            limit = 50;
        }
        if (limit > 1000) {
            limit = 1000;
        }
        if (offset < 0) {
            offset = 0;
        }
    }

    /** Returns a query that retrieves the most recent {@code limit} events. */
    public static AuditQuery recent(int limit) {
        return new AuditQuery(null, null, null, null, limit, 0);
    }

    /** Returns a default query: 50 most recent events, no filters. */
    public static AuditQuery defaults() {
        return new AuditQuery(null, null, null, null, 50, 0);
    }
}
