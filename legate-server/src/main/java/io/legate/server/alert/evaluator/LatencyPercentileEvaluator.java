package io.legate.server.alert.evaluator;

import io.legate.core.event.CompletionEvent;
import io.legate.server.alert.MetricEvaluator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Computes latency percentiles from the evaluation window.
 *
 * <p>This class is instantiated twice — once for p95 and once for p99 — each
 * with a different metric name and percentile. Both are registered as Spring
 * beans and discovered by {@link io.legate.server.alert.AlertEvaluator}.</p>
 *
 * <p>Supported metric names:</p>
 * <ul>
 *   <li>{@code p99_latency_ms} — 99th-percentile total request latency in milliseconds</li>
 *   <li>{@code p95_latency_ms} — 95th-percentile total request latency in milliseconds</li>
 * </ul>
 *
 * <p>Example alert condition: {@code p99_latency_ms > 5000}</p>
 */
@Component
public class LatencyPercentileEvaluator implements MetricEvaluator {

    /** Percentile to compute (e.g., 0.99 for p99). */
    private final double percentile;

    /** Metric name used in alert condition YAML (e.g., {@code p99_latency_ms}). */
    private final String name;

    /** Creates a p99 evaluator. Spring instantiates this via component scan. */
    public LatencyPercentileEvaluator() {
        this(0.99, "p99_latency_ms");
    }

    private LatencyPercentileEvaluator(double percentile, String name) {
        this.percentile = percentile;
        this.name       = name;
    }

    /** Factory method — creates a p95 evaluator for manual registration if needed. */
    public static LatencyPercentileEvaluator p95() {
        return new LatencyPercentileEvaluator(0.95, "p95_latency_ms");
    }

    @Override
    public String metricName() {
        return name;
    }

    @Override
    public double compute(List<CompletionEvent> windowEvents, Duration window, BigDecimal dailyCostUsd) {
        if (windowEvents.isEmpty()) {
            return 0.0;
        }

        List<Long> sortedLatencies = windowEvents.stream()
            .map(CompletionEvent::totalLatencyMs)
            .sorted()
            .toList();

        int index = (int) Math.ceil(percentile * sortedLatencies.size()) - 1;
        int clampedIndex = Math.clamp(index, 0, sortedLatencies.size() - 1);
        return sortedLatencies.get(clampedIndex);
    }
}
