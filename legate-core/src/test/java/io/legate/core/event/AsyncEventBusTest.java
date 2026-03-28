package io.legate.core.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncEventBusTest {

    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new AsyncEventBus();
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    @Test
    void shouldDeliverEventToSubscriber() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<CompletionEvent> receivedEvent = new AtomicReference<>();

        eventBus.subscribe(CompletionEvent.class, event -> {
            receivedEvent.set(event);
            latch.countDown();
        });

        CompletionEvent testEvent = CompletionEvent.success(
            "req_123",
            "key_456",
            "team_a",
            "gpt-4o",
            "gpt-4o",
            "openai",
            10,
            20,
            new BigDecimal("0.0005"),
            250L,
            200L,
            false,
            0
        );

        eventBus.publish(testEvent);

        boolean received = latch.await(2, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        assertThat(receivedEvent.get()).isNotNull();
        assertThat(receivedEvent.get().requestId()).isEqualTo("req_123");
        assertThat(receivedEvent.get().provider()).isEqualTo("openai");
    }

    @Test
    void shouldDeliverToMultipleSubscribers() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger count = new AtomicInteger(0);

        for (int i = 0; i < 3; i++) {
            eventBus.subscribe(CompletionEvent.class, event -> {
                count.incrementAndGet();
                latch.countDown();
            });
        }

        eventBus.publish(CompletionEvent.success(
            "req_123", "key_456", "team_a", "gpt-4o", "gpt-4o", "openai",
            10, 20, new BigDecimal("0.0005"), 250L, 200L, false, 0
        ));

        boolean received = latch.await(2, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        assertThat(count.get()).isEqualTo(3);
    }

    @Test
    void shouldNotDeliverToWrongEventType() throws InterruptedException {
        CountDownLatch completionLatch = new CountDownLatch(1);
        CountDownLatch cacheHitLatch = new CountDownLatch(1);

        eventBus.subscribe(CompletionEvent.class, event -> completionLatch.countDown());
        eventBus.subscribe(CacheHitEvent.class, event -> cacheHitLatch.countDown());

        // Publish completion event
        eventBus.publish(CompletionEvent.success(
            "req_123", "key_456", "team_a", "gpt-4o", "gpt-4o", "openai",
            10, 20, new BigDecimal("0.0005"), 250L, 200L, false, 0
        ));

        // Only completion subscriber should receive
        boolean completionReceived = completionLatch.await(500, TimeUnit.MILLISECONDS);
        boolean cacheHitReceived = cacheHitLatch.await(100, TimeUnit.MILLISECONDS);

        assertThat(completionReceived).isTrue();
        assertThat(cacheHitReceived).isFalse();
    }

    @Test
    void shouldHandleSubscriberExceptions() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);

        // First subscriber throws exception
        eventBus.subscribe(CompletionEvent.class, event -> {
            latch.countDown();
            throw new RuntimeException("Subscriber error");
        });

        // Second subscriber should still receive event
        eventBus.subscribe(CompletionEvent.class, event -> {
            latch.countDown();
        });

        eventBus.publish(CompletionEvent.success(
            "req_123", "key_456", "team_a", "gpt-4o", "gpt-4o", "openai",
            10, 20, new BigDecimal("0.0005"), 250L, 200L, false, 0
        ));

        boolean received = latch.await(2, TimeUnit.SECONDS);
        assertThat(received).isTrue();
    }

    @Test
    void shouldHandleHighThroughput() throws InterruptedException {
        int eventCount = 1000;
        CountDownLatch latch = new CountDownLatch(eventCount);

        eventBus.subscribe(CompletionEvent.class, event -> latch.countDown());

        for (int i = 0; i < eventCount; i++) {
            eventBus.publish(CompletionEvent.success(
                "req_" + i, "key_456", "team_a", "gpt-4o", "gpt-4o", "openai",
                10, 20, new BigDecimal("0.0005"), 250L, 200L, false, 0
            ));
        }

        boolean allReceived = latch.await(5, TimeUnit.SECONDS);
        assertThat(allReceived).isTrue();
    }
}
