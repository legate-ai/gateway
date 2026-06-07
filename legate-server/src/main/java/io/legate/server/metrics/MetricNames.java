package io.legate.server.metrics;

/**
 * Metric name constants aligned with the OpenTelemetry GenAI semantic conventions
 * (https://opentelemetry.io/docs/specs/semconv/gen-ai/).
 *
 * <p>Micrometer translates dot-separated names to the format required by each
 * registry (e.g., dots → underscores for Prometheus).</p>
 *
 * <p>Note: the OTel spec defines {@code gen_ai.client.token.usage} and
 * {@code gen_ai.client.operation.duration} as histograms. This implementation
 * currently uses counters/timers; migrate to histograms when upgrading to
 * Micrometer's distribution-summary support.</p>
 */
public final class MetricNames {

    // ── Request lifecycle ─────────────────────────────────────────────────────

    /** Total completed operations (counter). Tags: gen_ai.system, gen_ai.request.model, gen_ai.operation.name, error.type. */
    public static final String REQUESTS_TOTAL = "gen_ai.client.requests";

    /** End-to-end operation duration (timer). Tags: gen_ai.system, gen_ai.request.model, gen_ai.operation.name. */
    public static final String REQUEST_DURATION_SECONDS = "gen_ai.client.operation.duration";

    // ── Token accounting ──────────────────────────────────────────────────────

    /** Cumulative token usage (counter). Tags: gen_ai.system, gen_ai.request.model, gen_ai.token.type. */
    public static final String TOKENS_TOTAL = "gen_ai.client.token.usage";

    /** Cumulative estimated cost in USD (counter). Tags: gen_ai.system, gen_ai.request.model, virtual_key. */
    public static final String ESTIMATED_COST_USD_TOTAL = "gen_ai.client.cost.usd";

    // ── Reliability ───────────────────────────────────────────────────────────

    /** Fallback events (counter). Tags: gen_ai.system.from, gen_ai.system.to. */
    public static final String FALLBACKS_TOTAL = "gen_ai.client.fallbacks";

    /** Circuit breaker state transitions (counter). Tags: gen_ai.system, from_state, to_state. */
    public static final String CIRCUIT_BREAKER_TRANSITIONS_TOTAL = "gen_ai.client.circuit_breaker.transitions";

    // ── Cache ─────────────────────────────────────────────────────────────────

    /** Total cache hits (counter). */
    public static final String CACHE_HITS_TOTAL = "gen_ai.client.cache.hits";

    /** Total cache misses (counter). */
    public static final String CACHE_MISSES_TOTAL = "gen_ai.client.cache.misses";

    // ── Governance ────────────────────────────────────────────────────────────

    /** Rate-limit breaches (counter). Tags: virtual_key. */
    public static final String RATE_LIMIT_BREACHES_TOTAL = "gen_ai.client.rate_limit.breaches";

    /** Spend-limit breaches (counter). Tags: virtual_key, limit_type. */
    public static final String SPEND_LIMIT_BREACHES_TOTAL = "gen_ai.client.spend_limit.breaches";

    // ── Concurrency ───────────────────────────────────────────────────────────

    /** Gauge of in-flight requests. */
    public static final String ACTIVE_REQUESTS = "gen_ai.client.active_requests";

    private MetricNames() {}
}
