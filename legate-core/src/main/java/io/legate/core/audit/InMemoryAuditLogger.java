package io.legate.core.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * In-memory {@link AuditLogger} backed by a bounded deque.
 *
 * <p><strong>For testing only.</strong> This implementation holds all events in heap
 * memory and will cause OOM issues in long-running production use. The runtime default
 * is {@link NoOpAuditLogger}; the production implementation is {@code PostgresAuditLogger}.</p>
 *
 * <p>Thread-safe. The query method creates a snapshot for filtering, which is safe
 * under concurrent mutation.</p>
 */
@Deprecated(since = "0.4.0", forRemoval = true)
public class InMemoryAuditLogger implements AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(InMemoryAuditLogger.class);

    /** Default maximum number of events held in memory. */
    public static final int DEFAULT_MAX_SIZE = 10_000;

    private final LinkedBlockingDeque<AuditEvent> events;
    private final int maxSize;

    public InMemoryAuditLogger() {
        this(DEFAULT_MAX_SIZE);
    }

    public InMemoryAuditLogger(int maxSize) {
        this.maxSize = maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE;
        this.events = new LinkedBlockingDeque<>(this.maxSize);
    }

    @Override
    public void record(AuditEvent event) {
        if (event == null) {
            return;
        }
        try {
            // offerLast returns false when the deque is at capacity;
            // evict the oldest entry and retry once.
            if (!events.offerLast(event)) {
                events.pollFirst();
                events.offerLast(event);
            }
        } catch (Exception e) {
            log.error("Failed to record audit event", e);
        }
    }

    @Override
    public CompletableFuture<List<AuditEvent>> query(AuditQuery query) {
        AuditQuery effective = (query != null) ? query : AuditQuery.defaults();

        List<AuditEvent> snapshot = new ArrayList<>(events);

        List<AuditEvent> filtered = snapshot.stream()
            .filter(e -> effective.from() == null || !e.timestamp().isBefore(effective.from()))
            .filter(e -> effective.to()   == null || !e.timestamp().isAfter(effective.to()))
            .filter(e -> effective.virtualKeyId() == null || effective.virtualKeyId().equals(e.virtualKeyId()))
            .filter(e -> effective.type() == null || effective.type() == e.type())
            .sorted(Comparator.comparing(AuditEvent::timestamp).reversed())
            .toList();

        int total = filtered.size();
        int from  = Math.min(effective.offset(), total);
        int to    = Math.min(from + effective.limit(), total);

        return CompletableFuture.completedFuture(filtered.subList(from, to));
    }

    @Override
    public long count() {
        return events.size();
    }
}
