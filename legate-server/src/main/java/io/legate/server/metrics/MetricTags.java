package io.legate.server.metrics;

/**
 * Micrometer tag key and well-known tag value constants for Legate metrics.
 *
 * <p>Using typed constants rather than inline strings eliminates typo-induced
 * tag drift and makes refactoring safe. Every tag key used in
 * {@link MetricsCollector} or queried in {@code AdminHandler} must be declared here.</p>
 */
public final class MetricTags {

    // ── Tag keys ──────────────────────────────────────────────────────────────

    /** Upstream LLM provider name (e.g., {@code openai}, {@code anthropic}). */
    public static final String PROVIDER = "provider";

    /** Model name as requested by the client (e.g., {@code gpt-4o}). */
    public static final String MODEL = "model";

    /** Virtual key ID that authenticated the request; {@code none} if unauthenticated. */
    public static final String VIRTUAL_KEY = "virtual_key";

    /**
     * Request outcome status.
     *
     * @see #STATUS_SUCCESS
     * @see #STATUS_ERROR
     */
    public static final String STATUS = "status";

    /**
     * Token counting direction.
     *
     * @see #DIRECTION_INPUT
     * @see #DIRECTION_OUTPUT
     */
    public static final String DIRECTION = "direction";

    /** Provider that failed before the fallback was triggered. */
    public static final String FROM_PROVIDER = "from_provider";

    /** Provider that served the request after a fallback. */
    public static final String TO_PROVIDER = "to_provider";

    /**
     * Spend limit type that was breached.
     * Typical values: {@code daily}, {@code monthly}.
     */
    public static final String LIMIT_TYPE = "limit_type";

    /** Circuit breaker state before a transition. */
    public static final String FROM_STATE = "from_state";

    /** Circuit breaker state after a transition. */
    public static final String TO_STATE = "to_state";

    // ── Tag values ────────────────────────────────────────────────────────────

    /** {@link #STATUS} value for successfully completed requests. */
    public static final String STATUS_SUCCESS = "success";

    /** {@link #STATUS} value for failed requests (any error). */
    public static final String STATUS_ERROR = "error";

    /** {@link #DIRECTION} value for prompt/input tokens. */
    public static final String DIRECTION_INPUT = "input";

    /** {@link #DIRECTION} value for completion/output tokens. */
    public static final String DIRECTION_OUTPUT = "output";

    /** Fallback value used when a tag value is absent or unknown. */
    public static final String UNKNOWN = "unknown";

    /** Fallback value for {@link #VIRTUAL_KEY} when no key authenticated the request. */
    public static final String NONE = "none";

    private MetricTags() {
        // constants class — no instances
    }
}
