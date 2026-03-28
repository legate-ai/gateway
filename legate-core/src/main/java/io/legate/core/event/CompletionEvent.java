package io.legate.core.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event fired when a completion request finishes (success or failure).
 * This is the richest event, containing full request/response telemetry.
 */
public record CompletionEvent(
        String requestId,
        Instant timestamp,
        String virtualKeyId,
        String teamName,
        String requestedModel,
        String actualModel,
        String provider,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal estimatedCostUsd,
        long totalLatencyMs,
        Long upstreamLatencyMs,
        boolean cacheHit,
        int fallbackAttempts,
        boolean success,
        String errorCode
) implements LegateEvent {

    /**
     * Creates a successful completion event.
     */
    public static CompletionEvent success(
            String requestId,
            String virtualKeyId,
            String teamName,
            String requestedModel,
            String actualModel,
            String provider,
            Integer inputTokens,
            Integer outputTokens,
            BigDecimal estimatedCostUsd,
            long totalLatencyMs,
            Long upstreamLatencyMs,
            boolean cacheHit,
            int fallbackAttempts
    ) {
        return new CompletionEvent(
                requestId,
                Instant.now(),
                virtualKeyId,
                teamName,
                requestedModel,
                actualModel,
                provider,
                inputTokens,
                outputTokens,
                estimatedCostUsd,
                totalLatencyMs,
                upstreamLatencyMs,
                cacheHit,
                fallbackAttempts,
                true,
                null
        );
    }

    /**
     * Creates a failure completion event.
     */
    public static CompletionEvent failure(
            String requestId,
            String virtualKeyId,
            String teamName,
            String requestedModel,
            long totalLatencyMs,
            String errorCode
    ) {
        return new CompletionEvent(
                requestId,
                Instant.now(),
                virtualKeyId,
                teamName,
                requestedModel,
                null,
                null,
                null,
                null,
                null,
                totalLatencyMs,
                null,
                false,
                0,
                false,
                errorCode
        );
    }
}
