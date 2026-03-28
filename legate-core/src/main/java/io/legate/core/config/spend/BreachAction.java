package io.legate.core.config.spend;

/**
 * Action taken when a virtual key or global spend limit is exceeded.
 *
 * <p>YAML usage:</p>
 * <pre>{@code
 * legate:
 *   spend-control:
 *     global:
 *       action-on-breach: block    # or BLOCK, WARN, LOG_ONLY
 * }</pre>
 */
public enum BreachAction {

    /**
     * Reject the request with HTTP 403 and a {@code SpendLimitExceededException}.
     * The upstream provider is never called.
     * A {@code SpendLimitBreachedEvent} is emitted for audit.
     */
    BLOCK,

    /**
     * Allow the request to proceed but emit a {@code SpendLimitBreachedEvent}.
     * Use to get advance warning before switching to {@code BLOCK}.
     */
    WARN,

    /**
     * Allow the request and write a log entry only. No event is emitted to the bus.
     * Use during baseline measurement to understand spend patterns without impacting traffic.
     */
    LOG_ONLY
}
