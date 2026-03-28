package io.legate.server.metrics;

/**
 * Micrometer metric name constants for all Legate instrumentation.
 *
 * <p>Used by both {@link MetricsCollector} (where metrics are registered)
 * and {@code AdminHandler} (where metrics are queried for the stats API).
 * Keeping them in one place eliminates drift between registration and query.</p>
 *
 * <p>Alert condition YAML also references these names under
 * {@code legate.alerts[].condition}; see {@code AlertMetricNames} for the
 * alert-specific subset.</p>
 */
public final class MetricNames {

    // ── Request lifecycle ─────────────────────────────────────────────────────

    /**
     * Total number of requests processed.
     * Tags: {@code provider}, {@code model}, {@code virtual_key}, {@code status}.
     */
    public static final String REQUESTS_TOTAL = "legate_requests_total";

    /**
     * End-to-end request duration (from received to response sent).
     * Tags: {@code provider}, {@code model}.
     */
    public static final String REQUEST_DURATION_SECONDS = "legate_request_duration_seconds";

    // ── Token accounting ──────────────────────────────────────────────────────

    /**
     * Cumulative token usage.
     * Tags: {@code provider}, {@code model}, {@code direction} ({@code input} or {@code output}).
     */
    public static final String TOKENS_TOTAL = "legate_tokens_total";

    /**
     * Cumulative estimated cost in USD.
     * Tags: {@code provider}, {@code model}, {@code virtual_key}.
     */
    public static final String ESTIMATED_COST_USD_TOTAL = "legate_estimated_cost_usd_total";

    // ── Reliability ───────────────────────────────────────────────────────────

    /**
     * Number of times a fallback provider was used.
     * Tags: {@code from_provider}, {@code to_provider}.
     */
    public static final String FALLBACKS_TOTAL = "legate_fallbacks_total";

    /**
     * Number of circuit-breaker state transitions.
     * Tags: {@code provider}, {@code from_state}, {@code to_state}.
     */
    public static final String CIRCUIT_BREAKER_TRANSITIONS_TOTAL = "legate_circuit_breaker_transitions_total";

    // ── Cache ─────────────────────────────────────────────────────────────────

    /** Total cache hits across all requests. */
    public static final String CACHE_HITS_TOTAL = "legate_cache_hits_total";

    /** Total cache misses across all requests. */
    public static final String CACHE_MISSES_TOTAL = "legate_cache_misses_total";

    // ── Governance ────────────────────────────────────────────────────────────

    /**
     * Number of rate-limit breaches.
     * Tags: {@code virtual_key}.
     */
    public static final String RATE_LIMIT_BREACHES_TOTAL = "legate_rate_limit_breaches_total";

    /**
     * Number of spend-limit breaches.
     * Tags: {@code virtual_key}, {@code limit_type}.
     */
    public static final String SPEND_LIMIT_BREACHES_TOTAL = "legate_spend_limit_breaches_total";

    // ── Concurrency ───────────────────────────────────────────────────────────

    /** Gauge tracking the number of in-flight requests at any given moment. */
    public static final String ACTIVE_REQUESTS = "legate_active_requests";

    private MetricNames() {
        // constants class — no instances
    }
}
