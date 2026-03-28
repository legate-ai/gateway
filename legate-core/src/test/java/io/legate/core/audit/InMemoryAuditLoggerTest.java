package io.legate.core.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAuditLoggerTest {

    // ── Basic recording ────────────────────────────────────────────────────────

    @Test
    void record_storesEvent() {
        var log = new InMemoryAuditLogger();
        var event = AuditEvent.of("req_123", "key1", AuditEventType.REQUEST_BLOCKED, "Blocked by PII");

        log.record(event);

        assertThat(log.count()).isEqualTo(1);
    }

    @Test
    void query_returnsRecordedEvents() {
        var log = new InMemoryAuditLogger();
        log.record(AuditEvent.of("req_1", "key1", AuditEventType.REQUEST_BLOCKED, "PII detected"));
        log.record(AuditEvent.of("req_2", "key2", AuditEventType.RATE_LIMIT_EXCEEDED, "Too fast"));

        List<AuditEvent> results = log.query(AuditQuery.defaults()).join();

        assertThat(results).hasSize(2);
    }

    @Test
    void record_withNull_isIgnored() {
        var log = new InMemoryAuditLogger();
        log.record(null); // Should not throw

        assertThat(log.count()).isEqualTo(0);
    }

    // ── FIFO eviction ──────────────────────────────────────────────────────────

    @Test
    void fifoEviction_whenMaxSizeReached() {
        var log = new InMemoryAuditLogger(3); // maxSize = 3

        var oldestEvent = AuditEvent.of("req_oldest", "key1", AuditEventType.REQUEST_BLOCKED, "First");
        log.record(oldestEvent);
        log.record(AuditEvent.of("req_2", "key1", AuditEventType.REQUEST_BLOCKED, "Second"));
        log.record(AuditEvent.of("req_3", "key1", AuditEventType.REQUEST_BLOCKED, "Third"));

        // Add one more — should evict the oldest
        log.record(AuditEvent.of("req_new", "key1", AuditEventType.REQUEST_BLOCKED, "Fourth"));

        assertThat(log.count()).isEqualTo(3);
        List<AuditEvent> results = log.query(AuditQuery.recent(100)).join();
        // Oldest should have been evicted
        assertThat(results.stream().map(AuditEvent::requestId))
            .doesNotContain("req_oldest")
            .contains("req_new");
    }

    // ── Filtering ──────────────────────────────────────────────────────────────

    @Test
    void queryByVirtualKeyId_filtersCorrectly() {
        var log = new InMemoryAuditLogger();
        log.record(AuditEvent.of("req_1", "key_a", AuditEventType.REQUEST_BLOCKED, "For key_a"));
        log.record(AuditEvent.of("req_2", "key_b", AuditEventType.REQUEST_BLOCKED, "For key_b"));
        log.record(AuditEvent.of("req_3", "key_a", AuditEventType.RATE_LIMIT_EXCEEDED, "For key_a again"));

        var query = new AuditQuery(null, null, "key_a", null, 50, 0);
        List<AuditEvent> results = log.query(query).join();

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> "key_a".equals(e.virtualKeyId()));
    }

    @Test
    void queryByEventType_filtersCorrectly() {
        var log = new InMemoryAuditLogger();
        log.record(AuditEvent.of("req_1", "key1", AuditEventType.REQUEST_BLOCKED, "Blocked"));
        log.record(AuditEvent.of("req_2", "key2", AuditEventType.RATE_LIMIT_EXCEEDED, "Too fast"));
        log.record(AuditEvent.of("req_3", "key1", AuditEventType.REQUEST_BLOCKED, "Blocked again"));

        var query = new AuditQuery(null, null, null, AuditEventType.REQUEST_BLOCKED, 50, 0);
        List<AuditEvent> results = log.query(query).join();

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> e.type() == AuditEventType.REQUEST_BLOCKED);
    }

    @Test
    void queryByTimeRange_filtersCorrectly() throws InterruptedException {
        var log = new InMemoryAuditLogger();
        Instant before = Instant.now();

        Thread.sleep(10);
        log.record(AuditEvent.of("req_after", "key1", AuditEventType.REQUEST_BLOCKED, "After"));
        Instant after = Instant.now();
        Thread.sleep(10);
        log.record(AuditEvent.of("req_much_later", "key1", AuditEventType.REQUEST_BLOCKED, "Much later"));

        // Query only the "after" period
        var query = new AuditQuery(before, after, null, null, 50, 0);
        List<AuditEvent> results = log.query(query).join();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).requestId()).isEqualTo("req_after");
    }

    // ── Pagination ─────────────────────────────────────────────────────────────

    @Test
    void queryLimit_limitsResults() {
        var log = new InMemoryAuditLogger();
        for (int i = 0; i < 20; i++) {
            log.record(AuditEvent.of("req_" + i, "key1", AuditEventType.REQUEST_BLOCKED, "Event " + i));
        }

        var query = new AuditQuery(null, null, null, null, 5, 0);
        List<AuditEvent> results = log.query(query).join();

        assertThat(results).hasSize(5);
    }

    @Test
    void queryOffset_skipsResults() {
        var log = new InMemoryAuditLogger();
        for (int i = 0; i < 10; i++) {
            log.record(AuditEvent.of("req_" + i, "key1", AuditEventType.REQUEST_BLOCKED, "Event " + i));
        }

        var query = new AuditQuery(null, null, null, null, 10, 5);
        List<AuditEvent> results = log.query(query).join();

        assertThat(results).hasSize(5);
    }

    @Test
    void defaultQuery_returns50Results() {
        var log = new InMemoryAuditLogger();
        for (int i = 0; i < 100; i++) {
            log.record(AuditEvent.of("req_" + i, "key1", AuditEventType.REQUEST_BLOCKED, "Event " + i));
        }

        List<AuditEvent> results = log.query(AuditQuery.defaults()).join();

        assertThat(results).hasSize(50);
    }

    // ── Ordering ───────────────────────────────────────────────────────────────

    @Test
    void query_returnsResultsInDescendingTimestampOrder() throws InterruptedException {
        var log = new InMemoryAuditLogger();

        var early = AuditEvent.of("req_early", "key1", AuditEventType.REQUEST_BLOCKED, "First");
        Thread.sleep(10);
        var later = AuditEvent.of("req_later", "key1", AuditEventType.REQUEST_BLOCKED, "Second");

        log.record(early);
        log.record(later);

        List<AuditEvent> results = log.query(AuditQuery.defaults()).join();

        assertThat(results.get(0).requestId()).isEqualTo("req_later"); // most recent first
        assertThat(results.get(1).requestId()).isEqualTo("req_early");
    }

    // ── Null query ─────────────────────────────────────────────────────────────

    @Test
    void query_withNull_usesDefaults() {
        var log = new InMemoryAuditLogger();
        for (int i = 0; i < 5; i++) {
            log.record(AuditEvent.of("req_" + i, "key1", AuditEventType.REQUEST_BLOCKED, "Event " + i));
        }

        List<AuditEvent> results = log.query(null).join();

        assertThat(results).hasSize(5);
    }

    // ── Concurrent access ─────────────────────────────────────────────────────

    @Test
    void concurrentRecord_isThreadSafe() throws InterruptedException {
        var log = new InMemoryAuditLogger(10_000);
        int threads = 100;
        var latch = new java.util.concurrent.CountDownLatch(threads);

        try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                exec.submit(() -> {
                    log.record(AuditEvent.of("req_" + idx, "key1", AuditEventType.REQUEST_BLOCKED, "Event " + idx));
                    latch.countDown();
                });
            }
            latch.await();
        }

        assertThat(log.count()).isEqualTo(threads);
    }
}
