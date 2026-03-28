package io.legate.server.alert.evaluator;

import io.legate.core.event.CompletionEvent;
import io.legate.server.alert.MetricEvaluator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Computes the cache hit rate within the evaluation window.
 *
 * <p>Metric name: {@code cache_hit_rate}<br>
 * Range: {@code 0.0} (no cache hits) to {@code 1.0} (all requests served from cache).<br>
 * Returns {@code 0.0} when the window contains no events.</p>
 *
 * <p>Example alert condition: {@code cache_hit_rate < 0.2}
 * (alert when fewer than 20% of requests hit the cache).</p>
 */
@Component
public class CacheHitRateEvaluator implements MetricEvaluator {

    @Override
    public String metricName() {
        return "cache_hit_rate";
    }

    @Override
    public double compute(List<CompletionEvent> windowEvents, Duration window, BigDecimal dailyCostUsd) {
        if (windowEvents.isEmpty()) {
            return 0.0;
        }
        long hits = windowEvents.stream().filter(CompletionEvent::cacheHit).count();
        return (double) hits / windowEvents.size();
    }
}
