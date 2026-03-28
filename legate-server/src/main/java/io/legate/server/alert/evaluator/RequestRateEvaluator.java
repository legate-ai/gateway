package io.legate.server.alert.evaluator;

import io.legate.core.event.CompletionEvent;
import io.legate.server.alert.MetricEvaluator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Computes request throughput (requests per minute) within the evaluation window.
 *
 * <p>Metric name: {@code requests_per_minute}<br>
 * The window duration is used to normalise the event count to a per-minute rate.
 * A minimum window of one minute is enforced to avoid division by near-zero.</p>
 *
 * <p>Example alert condition: {@code requests_per_minute > 1000}</p>
 */
@Component
public class RequestRateEvaluator implements MetricEvaluator {

    private static final double MIN_WINDOW_MINUTES = 1.0;
    private static final double SECONDS_PER_MINUTE = 60.0;

    @Override
    public String metricName() {
        return "requests_per_minute";
    }

    @Override
    public double compute(List<CompletionEvent> windowEvents, Duration window, BigDecimal dailyCostUsd) {
        if (windowEvents.isEmpty()) {
            return 0.0;
        }
        double windowMinutes = Math.max(MIN_WINDOW_MINUTES, window.toSeconds() / SECONDS_PER_MINUTE);
        return windowEvents.size() / windowMinutes;
    }
}
