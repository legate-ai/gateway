package io.legate.core.routing;

import io.legate.core.config.routing.CircuitBreakerConfig;
import io.legate.core.event.CircuitBreakerStateChangeEvent;
import io.legate.core.event.EventBus;
import io.legate.core.event.LegateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerRegistryTest {

    private static final CircuitBreakerConfig CONFIG = new CircuitBreakerConfig(
            5,                    // failureThreshold
            2,                    // successThreshold
            Duration.ofMillis(200), // waitDuration (short for tests)
            10                    // slidingWindowSize
    );

    private final List<LegateEvent> publishedEvents = new ArrayList<>();
    private final EventBus eventBus = new EventBus() {
        @Override public void publish(LegateEvent event) { publishedEvents.add(event); }
        @Override public <E extends LegateEvent> void subscribe(Class<E> t, io.legate.core.event.EventSubscriber<E> s) {}
        @Override public void shutdown() {}
    };
    private CircuitBreakerRegistry registry;
    private ResolvedEndpoint endpoint;

    @BeforeEach
    void setUp() {
        registry = new CircuitBreakerRegistry(CONFIG, eventBus);
        endpoint = new ResolvedEndpoint("openai", "gpt-4o", "https://api.openai.com",
                new ProviderCredentials.BearerToken("sk-test"), Duration.ofSeconds(5),
                Duration.ofSeconds(30), 100);
    }

    @Test
    void isCallPermitted_newEndpoint_permitsCall() {
        assertThat(registry.isCallPermitted(endpoint)).isTrue();
    }

    @Test
    void recordSuccess_closedCircuit_remainsPermitted() {
        registry.recordSuccess(endpoint);
        assertThat(registry.isCallPermitted(endpoint)).isTrue();
    }

    @Test
    void isCallPermitted_afterEnoughFailures_opensCircuit() {
        // Fill the sliding window with failures (need slidingWindowSize calls)
        for (int i = 0; i < CONFIG.slidingWindowSize(); i++) {
            registry.isCallPermitted(endpoint);
            registry.recordFailure(endpoint);
        }

        assertThat(registry.isCallPermitted(endpoint)).isFalse();
    }

    @Test
    void isCallPermitted_afterWaitDuration_transitionsToHalfOpen() throws InterruptedException {
        // Open the circuit
        for (int i = 0; i < CONFIG.slidingWindowSize(); i++) {
            registry.isCallPermitted(endpoint);
            registry.recordFailure(endpoint);
        }
        assertThat(registry.isCallPermitted(endpoint)).isFalse();

        // Wait for the circuit to allow a probe
        Thread.sleep(CONFIG.waitDuration().toMillis() + 50);

        assertThat(registry.isCallPermitted(endpoint)).isTrue();
    }

    @Test
    void recordSuccess_halfOpenAfterSufficientSuccesses_closesCircuit() throws InterruptedException {
        // Open the circuit
        for (int i = 0; i < CONFIG.slidingWindowSize(); i++) {
            registry.isCallPermitted(endpoint);
            registry.recordFailure(endpoint);
        }

        // Wait for HALF_OPEN
        Thread.sleep(CONFIG.waitDuration().toMillis() + 50);

        // Probe calls: record enough successes to close
        for (int i = 0; i < CONFIG.successThreshold(); i++) {
            registry.isCallPermitted(endpoint);
            registry.recordSuccess(endpoint);
        }

        // Circuit should now be closed and permitting calls
        assertThat(registry.isCallPermitted(endpoint)).isTrue();
    }

    @Test
    void recordFailure_halfOpen_reopensCircuit() throws InterruptedException {
        // Open the circuit
        for (int i = 0; i < CONFIG.slidingWindowSize(); i++) {
            registry.isCallPermitted(endpoint);
            registry.recordFailure(endpoint);
        }

        // Wait for HALF_OPEN
        Thread.sleep(CONFIG.waitDuration().toMillis() + 50);

        // Resilience4j evaluates HALF_OPEN failure rate after all permitted probe calls complete.
        // Fill all permittedNumberOfCallsInHalfOpenState (= successThreshold) probes with failures.
        for (int i = 0; i < CONFIG.successThreshold(); i++) {
            registry.isCallPermitted(endpoint);
            registry.recordFailure(endpoint);
        }

        // All probes failed → circuit re-opens
        assertThat(registry.isCallPermitted(endpoint)).isFalse();
    }

    @Test
    void stateChangeEvents_published_onTransitions() throws InterruptedException {
        publishedEvents.clear();

        // Drive CLOSED → OPEN
        for (int i = 0; i < CONFIG.slidingWindowSize(); i++) {
            registry.isCallPermitted(endpoint);
            registry.recordFailure(endpoint);
        }

        // Wait for OPEN → HALF_OPEN transition to be triggerable
        Thread.sleep(CONFIG.waitDuration().toMillis() + 50);
        registry.isCallPermitted(endpoint); // triggers OPEN → HALF_OPEN

        List<CircuitBreakerStateChangeEvent> stateEvents = publishedEvents.stream()
                .filter(e -> e instanceof CircuitBreakerStateChangeEvent)
                .map(e -> (CircuitBreakerStateChangeEvent) e)
                .toList();

        assertThat(stateEvents).isNotEmpty();

        CircuitBreakerStateChangeEvent openEvent = stateEvents.stream()
                .filter(e -> "OPEN".equals(e.toState()))
                .findFirst()
                .orElseThrow();
        assertThat(openEvent.provider()).isEqualTo("openai");
        assertThat(openEvent.fromState()).isEqualTo("CLOSED");
    }

    @Test
    void multipleEndpoints_circuitBreakersAreIsolated() {
        ResolvedEndpoint endpoint2 = new ResolvedEndpoint("anthropic", "claude-3-5-sonnet",
                "https://api.anthropic.com", new ProviderCredentials.ApiKeyHeader("x-api-key", "key"),
                Duration.ofSeconds(5), Duration.ofSeconds(30), 100);

        // Open endpoint1's circuit
        for (int i = 0; i < CONFIG.slidingWindowSize(); i++) {
            registry.isCallPermitted(endpoint);
            registry.recordFailure(endpoint);
        }

        // endpoint2 should still be permitted
        assertThat(registry.isCallPermitted(endpoint)).isFalse();
        assertThat(registry.isCallPermitted(endpoint2)).isTrue();
    }

    @Test
    void nullEventBus_doesNotThrow() {
        CircuitBreakerRegistry registryNoEvents = new CircuitBreakerRegistry(CONFIG, null);
        assertThat(registryNoEvents.isCallPermitted(endpoint)).isTrue();
        registryNoEvents.recordSuccess(endpoint);
        registryNoEvents.recordFailure(endpoint);
    }
}
