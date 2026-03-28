package io.legate.core.event;

import java.time.Instant;

/**
 * Base sealed interface for all Legate events.
 * Events flow through the EventBus for async telemetry and observability.
 */
public sealed interface LegateEvent permits
        RequestReceivedEvent,
        CompletionEvent,
        CacheHitEvent,
        CacheMissEvent,
        UpstreamCallStartedEvent,
        UpstreamCallCompletedEvent,
        GuardDecisionEvent,
        CircuitBreakerStateChangeEvent,
        RateLimitBreachedEvent,
        SpendLimitBreachedEvent,
        FallbackTriggeredEvent {

    /**
     * Request ID associated with this event.
     */
    String requestId();

    /**
     * Timestamp when the event occurred.
     */
    Instant timestamp();
}
