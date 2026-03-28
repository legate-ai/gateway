package io.legate.store.postgres;

import io.legate.core.audit.AuditEvent;
import io.legate.core.audit.AuditLogger;
import io.legate.core.audit.AuditQuery;
import io.legate.store.postgres.mapper.AuditEventMapper;
import io.legate.store.postgres.repository.AuditEventR2dbcRepository;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PostgreSQL-backed {@link AuditLogger} using Spring Data R2DBC.
 *
 * <h3>Write strategy</h3>
 * <p>Events are buffered in a {@link ConcurrentLinkedQueue} and flushed to the
 * database in batches every second, or immediately when the buffer reaches
 * {@code FLUSH_BATCH_SIZE} events. Background flushes are fully non-blocking —
 * {@code saveAll()} is subscribed to without blocking any thread. A dedicated
 * Virtual Thread drives the periodic flush loop.</p>
 *
 * <h3>Shutdown</h3>
 * <p>On {@link #destroy()}, the flush loop is stopped and a final synchronous drain
 * runs on the Spring lifecycle thread (not a Netty/Reactor thread) to maximise
 * event durability before the process exits.</p>
 *
 * <h3>Resilience</h3>
 * <p>If an async flush fails, the batch is re-queued for the next cycle to avoid
 * data loss during transient DB outages.</p>
 */
public class PostgresAuditLogger implements AuditLogger, InitializingBean, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(PostgresAuditLogger.class);
    private static final int FLUSH_BATCH_SIZE = 100;
    private static final Duration SHUTDOWN_BLOCK_TIMEOUT = Duration.ofSeconds(10);
    public static final String LEGATE_AUDIT_FLUSH_THREAD = "legate-audit-flush";

    private final AuditEventR2dbcRepository repository;
    private final AuditEventMapper mapper;

    private final ConcurrentLinkedQueue<AuditEvent> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicLong totalSaved = new AtomicLong();

    private volatile boolean running;
    private Thread flushThread;

    public PostgresAuditLogger(AuditEventR2dbcRepository repository, AuditEventMapper mapper) {
        this.repository = Validate.notNull(repository, "repository must not be null");
        this.mapper = Validate.notNull(mapper, "mapper must not be null");
    }

    @Override
    public void afterPropertiesSet() {
        running = true;
        flushThread = Thread.ofVirtual().name(LEGATE_AUDIT_FLUSH_THREAD).start(this::flushLoop);
        logger.info("PostgresAuditLogger started");
    }

    @Override
    public void destroy() {
        running = false;
        if (flushThread != null) {
            flushThread.interrupt();
        }
        // Final synchronous drain — called on the Spring lifecycle thread, not a
        // Netty/Reactor thread, so blocking here is safe and necessary for durability.
        flushBlocking();
        logger.info("PostgresAuditLogger stopped — {} events persisted in total", totalSaved.get());
    }

    // ── AuditLogger ───────────────────────────────────────────────────────────

    @Override
    public void record(AuditEvent event) {
        if (event == null) {
            return;
        }
        buffer.offer(event);
        if (buffer.size() >= FLUSH_BATCH_SIZE) {
            // Overflow: kick off an async flush without blocking the caller.
            Thread.ofVirtual().start(this::flushAsync);
        }
    }

    @Override
    public CompletableFuture<List<AuditEvent>> query(AuditQuery query) {
        AuditQuery effective = (query != null) ? query : AuditQuery.defaults();
        return repository
                .findByFilters(effective)
                .map(mapper::toDomain)
                .collectList()
                .toFuture()
                .exceptionally(e -> {
                    logger.error("PostgresAuditLogger.query failed", e);
                    return List.of();
                });
    }

    @Override
    public long count() {
        return totalSaved.get();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void flushLoop() {
        while (running) {
            try {
                Thread.sleep(Duration.ofSeconds(1));
                flushAsync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.warn("Audit flush loop encountered an error", e);
            }
        }
    }

    /**
     * Drains a batch from the buffer and persists it reactively.
     * Fire-and-forget: failures re-queue the batch for the next cycle.
     */
    private void flushAsync() {
        List<AuditEvent> batch = drainBatch();
        if (batch.isEmpty()) {
            return;
        }
        repository
                .saveAll(batch.stream().map(mapper::toEntity).toList())
                .then()
                .doOnSuccess(ignored -> totalSaved.addAndGet(batch.size()))
                .doOnError(e -> {
                    logger.error("Failed to flush {} audit events — re-queuing", batch.size(), e);
                    batch.forEach(buffer::offer);
                })
                .subscribe();
    }

    /**
     * Synchronous drain used only during {@link #destroy()} shutdown.
     * Must only be called from a non-Reactor thread (e.g., Spring lifecycle thread).
     */
    private void flushBlocking() {
        List<AuditEvent> batch = drainBatch();
        if (batch.isEmpty()) {
            return;
        }
        try {
            repository
                    .saveAll(batch.stream().map(mapper::toEntity).toList())
                    .then()
                    .block(SHUTDOWN_BLOCK_TIMEOUT);
            totalSaved.addAndGet(batch.size());
        } catch (Exception e) {
            logger.error("Failed to flush {} audit events during shutdown", batch.size(), e);
        }
    }

    private List<AuditEvent> drainBatch() {
        List<AuditEvent> batch = new ArrayList<>(FLUSH_BATCH_SIZE);
        AuditEvent event;
        while (batch.size() < FLUSH_BATCH_SIZE && (event = buffer.poll()) != null) {
            batch.add(event);
        }
        return batch;
    }
}
