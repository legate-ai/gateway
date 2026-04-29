package io.legate.server.handler.pipeline.step;

import io.legate.core.context.RequestContext;
import io.legate.core.event.CompletionEvent;
import io.legate.core.event.EventBus;
import io.legate.core.ratelimit.RateLimiter;
import io.legate.server.handler.pipeline.PostResponsePipelineStep;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Post-response step: reports actual token usage to the rate limiter and publishes the
 * {@link CompletionEvent} that drives Prometheus metrics and audit logging.
 * Order 20 — runs after cost is set (order 10) so cost is included in the event.
 */
@Component
@Order(20)
public class EventPublishingStep implements PostResponsePipelineStep {

    private final RateLimiter rateLimiter;
    private final EventBus eventBus;

    public EventPublishingStep(RateLimiter rateLimiter, EventBus eventBus) {
        this.rateLimiter = rateLimiter;
        this.eventBus = eventBus;
    }

    @Override
    public int getOrder() { return 20; }

    @Override
    public void execute(RequestContext context) {
        reportTokenUsage(context);
        publishCompletionEvent(context);
    }

    private void reportTokenUsage(RequestContext context) {
        if (context.getVirtualKeyInfo() == null || context.getUsage() == null) return;
        int actualTokens = context.getUsage().totalTokens() != null
            ? context.getUsage().totalTokens() : 0;
        rateLimiter.reportUsage(
            context.getVirtualKeyInfo().keyId(),
            context.getReservedTokens(),
            actualTokens);
    }

    private void publishCompletionEvent(RequestContext context) {
        String keyId    = context.getVirtualKeyInfo() != null ? context.getVirtualKeyInfo().keyId()    : null;
        String team     = context.getVirtualKeyInfo() != null ? context.getVirtualKeyInfo().teamName() : null;
        String provider = context.getRoutingDecision() != null
            ? context.getRoutingDecision().endpoint().providerName() : null;

        if (context.getResponse() != null || context.getUsage() != null) {
            eventBus.publish(CompletionEvent.success(
                context.getRequestId(), keyId, team,
                context.getOriginalRequest().model(),
                context.getResponse() != null ? context.getResponse().model() : null,
                provider,
                context.getUsage() != null ? context.getUsage().promptTokens()     : null,
                context.getUsage() != null ? context.getUsage().completionTokens() : null,
                context.getEstimatedCostUsd(),
                context.getTotalLatencyMs(),
                context.getUpstreamLatencyMs(),
                context.isCacheHit(),
                context.getFallbackAttempts()
            ));
        } else {
            eventBus.publish(CompletionEvent.failure(
                context.getRequestId(), keyId, team,
                context.getOriginalRequest().model(),
                context.getTotalLatencyMs(),
                StringUtils.defaultIfBlank(context.getErrorCode(), "UNKNOWN_ERROR")
            ));
        }
    }
}
