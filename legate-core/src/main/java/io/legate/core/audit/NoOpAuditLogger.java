package io.legate.core.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link AuditLogger} implementation that silently discards all events.
 *
 * <p>This is the default when no persistent store is configured. It logs a single
 * {@code WARN} on first use to make the operator aware that audit events are being
 * dropped, then stays quiet to avoid log spam.</p>
 *
 * <p>To enable persistent audit logging, configure a store backend:
 * <pre>{@code
 * legate:
 *   store:
 *     type: postgres
 * }</pre>
 */
public class NoOpAuditLogger implements AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(NoOpAuditLogger.class);

    private volatile boolean warned = false;

    @Override
    public void record(AuditEvent event) {
        if (!warned) {
            warned = true;
            log.warn("No persistent audit store configured — audit events are being discarded. "
                + "Set 'legate.store.type=postgres' to enable audit logging.");
        }
    }

    @Override
    public CompletableFuture<List<AuditEvent>> query(AuditQuery query) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public long count() {
        return 0L;
    }
}
