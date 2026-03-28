package io.legate.core.config.alert;

/**
 * Payload template used when firing alert webhooks.
 *
 * <p>YAML usage:</p>
 * <pre>{@code
 * legate:
 *   alerts:
 *     - name: high-error-rate
 *       condition: "error_rate > 0.05"
 *       webhook: "https://hooks.slack.com/services/T00/B00/xxx"
 *       template: slack        # or SLACK, GENERIC
 * }</pre>
 */
public enum AlertTemplate {

    /**
     * Formats the alert payload as a Slack Incoming Webhook message with
     * an {@code attachments} block, colour-coded by severity, and a summary
     * field showing the breached condition.
     *
     * <p>Example payload:</p>
     * <pre>{@code
     * {
     *   "attachments": [{
     *     "color": "danger",
     *     "title": "Legate Alert: high-error-rate",
     *     "text": "Condition: error_rate > 0.05",
     *     "footer": "Legate AI Gateway",
     *     "ts": 1709462400
     *   }]
     * }
     * }</pre>
     */
    SLACK,

    /**
     * A simple JSON object with {@code name}, {@code condition}, {@code timestamp},
     * and {@code window} fields. Use this as the basis for custom integrations.
     *
     * <p>Example payload:</p>
     * <pre>{@code
     * {
     *   "name": "high-error-rate",
     *   "condition": "error_rate > 0.05",
     *   "timestamp": "2026-03-03T12:00:00Z",
     *   "window": "PT5M"
     * }
     * }</pre>
     */
    GENERIC
}
