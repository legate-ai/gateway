package io.legate.server.alert;

import io.legate.core.event.CompletionEvent;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Strategy interface for computing a named alert metric from a window of events.
 *
 * <p>Each implementation computes exactly one metric. {@link AlertEvaluator}
 * discovers all {@code MetricEvaluator} beans at startup and builds a lookup
 * map keyed by {@link #metricName()}, enabling O(1) dispatch at evaluation time.</p>
 *
 * <p>Adding a new alertable metric requires only implementing this interface and
 * annotating with {@code @Component} — the evaluator map in {@link AlertEvaluator}
 * does not need to be modified (Open/Closed Principle).</p>
 *
 * <p>Implementations must be stateless and thread-safe.</p>
 *
 * @see AlertEvaluator
 */
public interface MetricEvaluator {

    /**
     * The metric name this evaluator handles.
     *
     * <p>Must match the metric identifier used in the alert condition YAML, e.g.:</p>
     * <pre>{@code
     * legate:
     *   alerts:
     *     - condition: "error_rate > 0.05"  ← metric name is "error_rate"
     * }</pre>
     *
     * @return the metric name (lower_snake_case)
     */
    String metricName();

    /**
     * Computes the current metric value from the events within the alert's window.
     *
     * @param windowEvents completion events that fall within the evaluation window;
     *                     never {@code null}, may be empty
     * @param window       the configured evaluation window duration
     * @param dailyCostUsd accumulated cost for the current calendar day (UTC);
     *                     provided pre-computed for cost-based metrics
     * @return the metric value; {@code 0.0} when there is insufficient data
     */
    double compute(List<CompletionEvent> windowEvents, Duration window, BigDecimal dailyCostUsd);
}
