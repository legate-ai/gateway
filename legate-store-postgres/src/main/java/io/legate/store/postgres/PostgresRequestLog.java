package io.legate.store.postgres;

import io.legate.core.event.CompletionEvent;
import io.legate.core.event.EventBus;
import io.legate.store.postgres.entity.RequestLogEntity;
import io.legate.store.postgres.repository.RequestLogR2dbcRepository;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Subscribes to {@link CompletionEvent} on the {@link EventBus} and persists
 * request records to the {@code request_log} PostgreSQL table.
 *
 * <p>Records are buffered and flushed in batches every second (or at
 * {@code FLUSH_BATCH_SIZE} events) by a dedicated Virtual Thread, ensuring
 * the request-handling path is never blocked.</p>
 */
public class PostgresRequestLog implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PostgresRequestLog.class);
    private static final int FLUSH_BATCH_SIZE = 100;
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(10);

    private final RequestLogR2dbcRepository repository;
    private final EventBus eventBus;

    private final ConcurrentLinkedQueue<CompletionEvent> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicLong totalLogged = new AtomicLong();

    private volatile boolean running;
    private Thread flushThread;

    public PostgresRequestLog(RequestLogR2dbcRepository repository, EventBus eventBus) {
        this.repository = Validate.notNull(repository, "repository must not be null");
        this.eventBus = Validate.notNull(eventBus, "eventBus must not be null");
    }

    @Override
    public void afterPropertiesSet() {
        running = true;
        flushThread = Thread.ofVirtual()
                .name("legate-reqlog-flush")
                .start(this::flushLoop);
        eventBus.subscribe(CompletionEvent.class, this::onCompletion);
        log.info("PostgresRequestLog started");
    }

    @Override
    public void destroy() {
        running = false;
        if (flushThread != null) {
            flushThread.interrupt();
        }
        flush();
        log.info("PostgresRequestLog stopped — {} requests logged in total", totalLogged.get());
    }

    private void onCompletion(CompletionEvent event) {
        buffer.offer(event);
        if (buffer.size() >= FLUSH_BATCH_SIZE) {
            Thread.ofVirtual().start(this::flush);
        }
    }

    private void flushLoop() {
        while (running) {
            try {
                Thread.sleep(Duration.ofSeconds(1));
                flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Request log flush loop encountered an error", e);
            }
        }
    }

    private void flush() {
        List<CompletionEvent> batch = drainBatch();
        if (batch.isEmpty()) {
            return;
        }
        try {
            List<RequestLogEntity> entities = batch.stream()
                    .map(this::toEntity)
                    .toList();
            repository.saveAll(entities)
                    .collectList()
                    .block(BLOCK_TIMEOUT);
            totalLogged.addAndGet(batch.size());
        } catch (Exception e) {
            log.warn("Failed to flush {} request log entries — dropping batch: {}", batch.size(), e.getMessage());
        }
    }

    private List<CompletionEvent> drainBatch() {
        List<CompletionEvent> batch = new ArrayList<>(FLUSH_BATCH_SIZE);
        CompletionEvent event;
        while (batch.size() < FLUSH_BATCH_SIZE && (event = buffer.poll()) != null) {
            batch.add(event);
        }
        return batch;
    }

    private RequestLogEntity toEntity(CompletionEvent completionEvent) {
        return new RequestLogEntity(
                completionEvent.requestId(),
                completionEvent.timestamp(),
                StringUtils.defaultString(completionEvent.virtualKeyId()),
                StringUtils.defaultString(completionEvent.teamName()),
                StringUtils.defaultString(completionEvent.requestedModel()),
                StringUtils.defaultString(completionEvent.actualModel()),
                StringUtils.defaultString(completionEvent.provider()),
                completionEvent.inputTokens(),
                completionEvent.outputTokens(),
                completionEvent.estimatedCostUsd() != null ? completionEvent.estimatedCostUsd() : BigDecimal.ZERO,
                completionEvent.totalLatencyMs(),
                completionEvent.upstreamLatencyMs() != null ? completionEvent.upstreamLatencyMs() : 0L,
                completionEvent.cacheHit(),
                completionEvent.fallbackAttempts(),
                completionEvent.success(),
                StringUtils.defaultString(completionEvent.errorCode())
        );
    }
}
