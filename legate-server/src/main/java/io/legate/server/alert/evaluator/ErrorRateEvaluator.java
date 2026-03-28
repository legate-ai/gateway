package io.legate.server.alert.evaluator;

import io.legate.core.event.CompletionEvent;
import io.legate.server.alert.MetricEvaluator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Computes the fraction of failed requests in the evaluation window.
 *
 * <p>Metric name: {@code error_rate}<br>
 * Range: {@code 0.0} (no errors) to {@code 1.0} (all errors).<br>
 * Returns {@code 0.0} when the window contains no events.</p>
 *
 * <p>Example alert condition: {@code error_rate > 0.05}</p>
 */
@Component
public class ErrorRateEvaluator implements MetricEvaluator {

    @Override
    public String metricName() {
        return "error_rate";
    }

    @Override
    public double compute(List<CompletionEvent> windowEvents, Duration window, BigDecimal dailyCostUsd) {
        if (windowEvents.isEmpty()) {
            return 0.0;
        }
        long errors = windowEvents.stream().filter(e -> !e.success()).count();
        return (double) errors / windowEvents.size();
    }
}
