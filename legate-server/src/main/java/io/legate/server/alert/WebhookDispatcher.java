package io.legate.server.alert;

import io.legate.core.config.alert.AlertConfig;
import io.legate.core.config.alert.AlertTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;

/**
 * Dispatches alert webhook POST requests when an alert condition is breached.
 *
 * <p>Supports two payload formats:</p>
 * <ul>
 *   <li>{@link AlertTemplate#SLACK} — Slack Incoming Webhook attachment format</li>
 *   <li>{@link AlertTemplate#GENERIC} — Simple JSON with condition details</li>
 * </ul>
 *
 * <p>Retries up to 3 times with exponential back-off on transient failures.
 * Failures are logged but never propagated to the alert evaluation loop.</p>
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebClient webClient;

    public WebhookDispatcher(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
            .defaultHeader("User-Agent", "Legate-Gateway/1.0")
            .build();
    }

    /**
     * Asynchronously dispatches an alert to the configured webhook URL.
     *
     * @param alert    the alert configuration
     * @param metric   the metric name that breached the threshold
     * @param current  the current metric value
     */
    public void dispatch(AlertConfig alert, String metric, double current) {
        if (alert.webhook() == null || alert.webhook().isBlank()) {
            log.warn("Alert '{}' has no webhook URL configured", alert.name());
            return;
        }

        String payload = buildPayload(alert, metric, current);

        webClient.post()
            .uri(alert.webhook())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .toBodilessEntity()
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)))
            .subscribe(
                _  -> log.info("Alert dispatched: name={} condition={} current={}", alert.name(), alert.condition(), current),
                e  -> log.error("Alert webhook dispatch failed for '{}': {}", alert.name(), e.getMessage())
            );
    }

    private String buildPayload(AlertConfig alert, String metric, double current) {
        return switch (alert.template()) {
            case SLACK -> buildSlackPayload(alert, current);
            case GENERIC -> buildGenericPayload(alert, current);
        };
    }

    private String buildSlackPayload(AlertConfig alert, double current) {
        long epochSecs = Instant.now().getEpochSecond();
        return String.format("""
            {
              "attachments": [{
                "color": "danger",
                "title": "Legate Alert: %s",
                "text": "Condition: %s\\nCurrent value: %.4f",
                "footer": "Legate AI Gateway",
                "ts": %d
              }]
            }
            """, escapeJson(alert.name()), escapeJson(alert.condition()), current, epochSecs).trim();
    }

    private String buildGenericPayload(AlertConfig alert, double current) {
        return String.format("""
            {
              "name": "%s",
              "condition": "%s",
              "current_value": %.4f,
              "timestamp": "%s",
              "window": "%s"
            }
            """, escapeJson(alert.name()), escapeJson(alert.condition()),
            current, Instant.now(), alert.window()).trim();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
