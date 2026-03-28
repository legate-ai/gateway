package io.legate.server.alert.evaluator;

import io.legate.core.event.CompletionEvent;
import io.legate.server.alert.MetricEvaluator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Returns the accumulated estimated USD cost for the current calendar day (UTC).
 *
 * <p>Metric name: {@code daily_cost_usd}<br>
 * The value is maintained by {@link io.legate.server.alert.AlertEvaluator} as a
 * running total that resets at UTC midnight. The {@code windowEvents} parameter is
 * unused — cost is always a daily total, not a sliding-window metric.</p>
 *
 * <p>Example alert condition: {@code daily_cost_usd > 500}</p>
 */
@Component
public class DailyCostEvaluator implements MetricEvaluator {

    @Override
    public String metricName() {
        return "daily_cost_usd";
    }

    @Override
    public double compute(List<CompletionEvent> windowEvents, Duration window, BigDecimal dailyCostUsd) {
        return dailyCostUsd != null ? dailyCostUsd.doubleValue() : 0.0;
    }
}
