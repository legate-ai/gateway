package io.legate.server.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.legate.core.event.CacheHitEvent;
import io.legate.core.event.CacheMissEvent;
import io.legate.core.event.CircuitBreakerStateChangeEvent;
import io.legate.core.event.CompletionEvent;
import io.legate.core.event.EventBus;
import io.legate.core.event.FallbackTriggeredEvent;
import io.legate.core.event.RateLimitBreachedEvent;
import io.legate.core.event.RequestReceivedEvent;
import io.legate.core.event.SpendLimitBreachedEvent;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Subscribes to the {@link EventBus} and updates Micrometer metrics for Prometheus scraping.
 *
 * <p>All metric names are centralised in {@link MetricNames} and all tag keys/values in
 * {@link MetricTags}, so renaming a metric requires changing exactly one constant.</p>
 *
 * <p>Tag values are always non-null — unknown/absent values use the sentinel
 * {@link MetricTags#UNKNOWN} or {@link MetricTags#NONE} to avoid cardinality explosions
 * from {@code null} labels.</p>
 */
@Component
public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    private final EventBus eventBus;
    private final MeterRegistry registry;

    /**
     * Approximation of in-flight requests; incremented on received, decremented on completion.
     */
    private final AtomicLong activeRequests = new AtomicLong();

    public MetricsCollector(EventBus eventBus, MeterRegistry registry) {
        this.eventBus = eventBus;
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        registry.gauge(MetricNames.ACTIVE_REQUESTS, activeRequests);

        eventBus.subscribe(RequestReceivedEvent.class, event -> activeRequests.incrementAndGet());

        eventBus.subscribe(CompletionEvent.class, this::onCompletion);
        eventBus.subscribe(CacheHitEvent.class, event -> incrementCounter(MetricNames.CACHE_HITS_TOTAL));
        eventBus.subscribe(CacheMissEvent.class, event -> incrementCounter(MetricNames.CACHE_MISSES_TOTAL));
        eventBus.subscribe(FallbackTriggeredEvent.class, this::onFallback);
        eventBus.subscribe(RateLimitBreachedEvent.class, this::onRateLimitBreach);
        eventBus.subscribe(SpendLimitBreachedEvent.class, this::onSpendLimitBreach);
        eventBus.subscribe(CircuitBreakerStateChangeEvent.class, this::onCircuitBreakerTransition);

        log.info("MetricsCollector initialised — subscribed to EventBus for Prometheus metrics");
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    private void onCompletion(CompletionEvent event) {
        try {
            String system = StringUtils.defaultIfBlank(event.provider(), MetricTags.UNKNOWN);
            String model = StringUtils.defaultIfBlank(event.requestedModel(), MetricTags.UNKNOWN);
            String virtualKey = StringUtils.defaultIfBlank(event.virtualKeyId(), MetricTags.NONE);
            String errorType = event.success() ? null : MetricTags.UNKNOWN;

            Counter.builder(MetricNames.REQUESTS_TOTAL)
                .tag(MetricTags.GEN_AI_SYSTEM, system)
                .tag(MetricTags.GEN_AI_REQUEST_MODEL, model)
                .tag(MetricTags.GEN_AI_OPERATION, "chat")
                .tag(MetricTags.VIRTUAL_KEY, virtualKey)
                .tags(errorType != null ? new String[]{MetricTags.ERROR_TYPE, errorType} : new String[0])
                .register(registry)
                .increment();

            Timer.builder(MetricNames.REQUEST_DURATION_SECONDS)
                .tag(MetricTags.GEN_AI_SYSTEM, system)
                .tag(MetricTags.GEN_AI_REQUEST_MODEL, model)
                .tag(MetricTags.GEN_AI_OPERATION, "chat")
                .register(registry)
                .record(Duration.ofMillis(event.totalLatencyMs()));

            if (event.inputTokens() != null) {
                Counter.builder(MetricNames.TOKENS_TOTAL)
                    .tag(MetricTags.GEN_AI_SYSTEM, system)
                    .tag(MetricTags.GEN_AI_REQUEST_MODEL, model)
                    .tag(MetricTags.GEN_AI_TOKEN_TYPE, MetricTags.TOKEN_TYPE_INPUT)
                    .register(registry)
                    .increment(event.inputTokens());
            }
            if (event.outputTokens() != null) {
                Counter.builder(MetricNames.TOKENS_TOTAL)
                    .tag(MetricTags.GEN_AI_SYSTEM, system)
                    .tag(MetricTags.GEN_AI_REQUEST_MODEL, model)
                    .tag(MetricTags.GEN_AI_TOKEN_TYPE, MetricTags.TOKEN_TYPE_OUTPUT)
                    .register(registry)
                    .increment(event.outputTokens());
            }
            if (event.estimatedCostUsd() != null) {
                Counter.builder(MetricNames.ESTIMATED_COST_USD_TOTAL)
                    .tag(MetricTags.GEN_AI_SYSTEM, system)
                    .tag(MetricTags.GEN_AI_REQUEST_MODEL, model)
                    .tag(MetricTags.VIRTUAL_KEY, virtualKey)
                    .register(registry)
                    .increment(event.estimatedCostUsd().doubleValue());
            }

            activeRequests.decrementAndGet();
        } catch (Exception e) {
            log.error("MetricsCollector: error processing CompletionEvent", e);
        }
    }

    private void onFallback(FallbackTriggeredEvent event) {
        try {
            Counter.builder(MetricNames.FALLBACKS_TOTAL)
                .tag(MetricTags.FROM_PROVIDER, StringUtils.defaultIfBlank(event.fromProvider(), MetricTags.NONE))
                .tag(MetricTags.TO_PROVIDER, StringUtils.defaultIfBlank(event.toProvider(), MetricTags.UNKNOWN))
                .register(registry)
                .increment();
        } catch (Exception e) {
            log.warn("MetricsCollector: error processing FallbackTriggeredEvent", e);
        }
    }

    private void onRateLimitBreach(RateLimitBreachedEvent event) {
        try {
            Counter.builder(MetricNames.RATE_LIMIT_BREACHES_TOTAL)
                    .tag(MetricTags.VIRTUAL_KEY, StringUtils.defaultIfBlank(event.virtualKeyId(), MetricTags.NONE))
                    .register(registry)
                    .increment();
        } catch (Exception e) {
            log.warn("MetricsCollector: error processing RateLimitBreachedEvent", e);
        }
    }

    private void onSpendLimitBreach(SpendLimitBreachedEvent event) {
        try {
            Counter.builder(MetricNames.SPEND_LIMIT_BREACHES_TOTAL)
                .tag(MetricTags.VIRTUAL_KEY, StringUtils.defaultIfBlank(event.virtualKeyId(), MetricTags.NONE))
                .tag(MetricTags.LIMIT_TYPE, StringUtils.defaultIfBlank(event.limitType(), MetricTags.UNKNOWN))
                .register(registry)
                .increment();
        } catch (Exception e) {
            log.warn("MetricsCollector: error processing SpendLimitBreachedEvent", e);
        }
    }

    private void onCircuitBreakerTransition(CircuitBreakerStateChangeEvent event) {
        try {
            Counter.builder(MetricNames.CIRCUIT_BREAKER_TRANSITIONS_TOTAL)
                .tag(MetricTags.GEN_AI_SYSTEM, StringUtils.defaultIfBlank(event.provider(), MetricTags.UNKNOWN))
                .tag(MetricTags.FROM_STATE, StringUtils.defaultIfBlank(event.fromState(), MetricTags.UNKNOWN))
                .tag(MetricTags.TO_STATE, StringUtils.defaultIfBlank(event.toState(), MetricTags.UNKNOWN))
                .register(registry)
                .increment();
        } catch (Exception e) {
            log.warn("MetricsCollector: error processing CircuitBreakerStateChangeEvent", e);
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private void incrementCounter(String metricName) {
        try {
            Counter.builder(metricName).register(registry).increment();
        } catch (Exception e) {
            log.warn("MetricsCollector: error incrementing counter '{}'", metricName, e);
        }
    }
}
