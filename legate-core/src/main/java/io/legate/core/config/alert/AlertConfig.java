package io.legate.core.config.alert;

import java.time.Duration;

/**
 * Configuration for a single alert rule.
 *
 * <p>Legate evaluates the {@link #condition()} expression against a sliding time window
 * of aggregated metrics. When the condition is {@code true}, a webhook POST is dispatched
 * (at most once per evaluation window to prevent alert storms).</p>
 *
 * <h3>Supported condition syntax</h3>
 * <p>Conditions are simple threshold expressions: {@code <metric> <operator> <value>}</p>
 * <ul>
 *   <li>{@code error_rate > 0.05} — fraction of failed requests in the window exceeds 5%</li>
 *   <li>{@code daily_cost_usd > 500} — accumulated spend today exceeds $500</li>
 *   <li>{@code p99_latency_ms > 3000} — upstream p99 latency exceeds 3 seconds</li>
 *   <li>{@code p95_latency_ms > 1500}</li>
 *   <li>{@code requests_per_minute > 900} — request throughput exceeds threshold</li>
 *   <li>{@code cache_hit_rate < 0.1} — cache hit rate drops below 10%</li>
 * </ul>
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   alerts:
 *     - name: high-error-rate
 *       condition: "error_rate > 0.05"
 *       window: 5m
 *       webhook: "https://hooks.slack.com/services/T00/B00/xxx"
 *       template: slack
 *
 *     - name: budget-warning
 *       condition: "daily_cost_usd > 800"
 *       webhook: "https://example.com/alerts"
 *       template: generic
 * }</pre>
 */
public record AlertConfig(

    /**
     * Unique name for this alert rule. Used in log messages and the webhook payload.
     * Must not be blank.
     */
    String name,

    /**
     * Condition expression evaluated against the aggregated window metrics.
     * Must not be blank. See Javadoc for supported syntax.
     */
    String condition,

    /**
     * Evaluation window over which metrics are aggregated.
     * Default: {@code 5 minutes}.
     */
    Duration window,

    /**
     * HTTP URL to POST the alert payload to when the condition is breached.
     * Must not be blank.
     */
    String webhook,

    /**
     * Payload format template.
     * Default: {@link AlertTemplate#GENERIC}.
     */
    AlertTemplate template

) {
    public AlertConfig {
        if (window == null)   {
            window = Duration.ofMinutes(5);
        }
        if (template == null)  {
            template = AlertTemplate.GENERIC;
        }
    }
}
