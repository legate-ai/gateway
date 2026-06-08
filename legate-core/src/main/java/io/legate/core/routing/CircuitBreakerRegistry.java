package io.legate.core.routing;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.legate.core.event.CircuitBreakerStateChangeEvent;
import io.legate.core.event.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Registry that lazily creates and caches Resilience4j
 * {@link io.github.resilience4j.circuitbreaker.CircuitBreaker} instances per endpoint.
 *
 * <p>Circuit breakers are keyed by {@link ResolvedEndpoint#getKey()} which returns
 * {@code "providerName:modelName"}. All circuit breakers share the same
 * {@link io.legate.core.config.routing.CircuitBreakerConfig}.</p>
 *
 * <p>Thread-safe: Resilience4j's registry guarantees at-most-one instance per key.</p>
 */
public class CircuitBreakerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRegistry.class);

    private final io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry r4jRegistry;
    private final EventBus eventBus;

    /**
     * Creates a registry with the given Legate circuit-breaker configuration.
     *
     * @param config   circuit-breaker thresholds and timing applied to all breakers
     * @param eventBus for state-change event publication; may be {@code null}
     */
    public CircuitBreakerRegistry(
            io.legate.core.config.routing.CircuitBreakerConfig config,
            EventBus eventBus) {
        this.eventBus = eventBus;
        this.r4jRegistry = io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.of(toR4jConfig(config));

        // Register a listener so every newly-created CB automatically publishes
        // state-transition events to Legate's EventBus.
        if (eventBus != null) {
            r4jRegistry.getEventPublisher()
                    .onEntryAdded(event -> registerStateListener(event.getAddedEntry()));
        }
    }

    /**
     * Returns {@code true} if a call to the given endpoint is currently permitted.
     * Creates a circuit breaker for the endpoint if one does not yet exist.
     *
     * @param endpoint the target upstream endpoint
     * @return {@code true} if the circuit is CLOSED or transitioning to HALF_OPEN
     */
    public boolean isCallPermitted(ResolvedEndpoint endpoint) {
        return getOrCreate(endpoint).tryAcquirePermission();
    }

    /**
     * Records a successful upstream call for the given endpoint.
     *
     * @param endpoint the endpoint that completed successfully
     */
    public void recordSuccess(ResolvedEndpoint endpoint) {
        getOrCreate(endpoint).onSuccess(0, TimeUnit.NANOSECONDS);
    }

    /**
     * Records a failed upstream call for the given endpoint.
     *
     * @param endpoint the endpoint whose call failed
     */
    public void recordFailure(ResolvedEndpoint endpoint) {
        getOrCreate(endpoint).onError(0, TimeUnit.NANOSECONDS,
                new RuntimeException("upstream failure"));
    }

    // -------------------------------------------------------------------------

    private io.github.resilience4j.circuitbreaker.CircuitBreaker getOrCreate(ResolvedEndpoint endpoint) {
        return r4jRegistry.circuitBreaker(endpoint.getKey());
    }

    private void registerStateListener(io.github.resilience4j.circuitbreaker.CircuitBreaker cb) {
        cb.getEventPublisher().onStateTransition(event -> {
            try {
                String key = cb.getName();
                String provider = key;
                String model = "";
                int colon = key.indexOf(':');
                if (colon >= 0) {
                    provider = key.substring(0, colon);
                    model = key.substring(colon + 1);
                }
                eventBus.publish(new CircuitBreakerStateChangeEvent(
                        provider, model,
                        event.getStateTransition().getFromState().name(),
                        event.getStateTransition().getToState().name(),
                        null));
            } catch (Exception e) {
                log.debug("Failed to publish CircuitBreakerStateChangeEvent", e);
            }
        });
    }

    /**
     * Maps Legate's {@link io.legate.core.config.routing.CircuitBreakerConfig} to a
     * Resilience4j {@link CircuitBreakerConfig}.
     *
     * <p>Mapping:</p>
     * <ul>
     *   <li>{@code failureThreshold / slidingWindowSize * 100} → {@code failureRateThreshold} (%)</li>
     *   <li>{@code slidingWindowSize} → {@code slidingWindowSize} and {@code minimumNumberOfCalls}</li>
     *   <li>{@code waitDuration} → {@code waitDurationInOpenState}</li>
     *   <li>{@code successThreshold} → {@code permittedNumberOfCallsInHalfOpenState}</li>
     * </ul>
     */
    private static CircuitBreakerConfig toR4jConfig(
            io.legate.core.config.routing.CircuitBreakerConfig config) {
        float failureRateThreshold =
                (float) config.failureThreshold() / config.slidingWindowSize() * 100f;

        return CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(config.slidingWindowSize())
                .failureRateThreshold(failureRateThreshold)
                .minimumNumberOfCalls(config.slidingWindowSize())
                .waitDurationInOpenState(config.waitDuration())
                .permittedNumberOfCallsInHalfOpenState(config.successThreshold())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build();
    }
}
