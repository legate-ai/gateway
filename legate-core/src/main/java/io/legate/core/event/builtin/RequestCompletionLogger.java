package io.legate.core.event.builtin;

import io.legate.core.event.CompletionEvent;
import io.legate.core.event.EventSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Subscriber that enriches the MDC with {@link CompletionEvent} fields and emits
 * a single structured log line per completed request.
 *
 * <p>When Spring Boot ECS structured logging is active
 * ({@code logging.structured.format.console: ecs}), all MDC fields are automatically
 * serialised into the JSON log line under the {@code labels} namespace. Without ECS,
 * the MDC fields appear in the standard log pattern ({@code %mdc}).</p>
 *
 * <p>All MDC entries are cleared in a {@code finally} block to prevent leakage
 * across async boundaries.</p>
 */
public class RequestCompletionLogger implements EventSubscriber<CompletionEvent> {

    private static final Logger log = LoggerFactory.getLogger(RequestCompletionLogger.class);

    @Override
    public void onEvent(CompletionEvent event) {
        try {
            MDC.put("legate.request_id",         event.requestId());
            MDC.put("legate.virtual_key_id",      nvl(event.virtualKeyId()));
            MDC.put("legate.team_name",           nvl(event.teamName()));
            MDC.put("legate.requested_model",     nvl(event.requestedModel()));
            MDC.put("legate.actual_model",        nvl(event.actualModel()));
            MDC.put("legate.provider",            nvl(event.provider()));
            MDC.put("legate.input_tokens",        String.valueOf(event.inputTokens()));
            MDC.put("legate.output_tokens",       String.valueOf(event.outputTokens()));
            MDC.put("legate.total_latency_ms",    String.valueOf(event.totalLatencyMs()));
            MDC.put("legate.upstream_latency_ms", String.valueOf(event.upstreamLatencyMs()));
            MDC.put("legate.cache_hit",           String.valueOf(event.cacheHit()));
            MDC.put("legate.fallback_attempts",   String.valueOf(event.fallbackAttempts()));
            MDC.put("legate.success",             String.valueOf(event.success()));
            if (event.estimatedCostUsd() != null) {
                MDC.put("legate.cost_usd", event.estimatedCostUsd().toPlainString());
            }
            if (event.errorCode() != null) {
                MDC.put("legate.error_code", event.errorCode());
            }
            log.info("Request completed");
        } finally {
            MDC.clear();
        }
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
