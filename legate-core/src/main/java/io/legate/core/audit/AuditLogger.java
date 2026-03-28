package io.legate.core.audit;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * SPI for recording and querying audit events.
 *
 * <p>The production implementation is {@link PostgresAuditLogger} (activated via
 * {@code legate.store.type=postgres}). When no persistent store is configured,
 * {@link NoOpAuditLogger} is used and audit events are discarded with a warning.</p>
 *
 * <p>Implementations must be thread-safe.</p>
 */
public interface AuditLogger {

    /**
     * Records an audit event.
     *
     * <p>This method must never throw — if persistence fails, the error should be
     * logged and swallowed so the request pipeline is unaffected.</p>
     *
     * @param event the event to record
     */
    void record(AuditEvent event);

    /**
     * Queries audit events matching the given criteria.
     *
     * <p>Returns a {@link CompletableFuture} so callers can compose results
     * non-blockingly (e.g., via {@code Mono.fromFuture()}) without requiring
     * Project Reactor in this module.</p>
     *
     * @param query filter and pagination parameters
     * @return future of matching events, ordered by timestamp descending
     */
    CompletableFuture<List<AuditEvent>> query(AuditQuery query);

    /**
     * Returns the total number of events persisted so far.
     */
    long count();
}
