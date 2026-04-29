package io.legate.server.metrics;

/**
 * Metric tag key and value constants aligned with the OTel GenAI semantic conventions.
 *
 * <p>Micrometer translates dot-separated tag names to underscore form for Prometheus.</p>
 */
public final class MetricTags {

    // ── Tag keys (OTel GenAI) ─────────────────────────────────────────────────

    /** AI system name (e.g., "openai", "anthropic"). Maps to {@code gen_ai.system}. */
    public static final String GEN_AI_SYSTEM = "gen_ai.system";

    /** Model name requested by the client. Maps to {@code gen_ai.request.model}. */
    public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";

    /** Operation type: "chat", "embeddings", "text_completion". Maps to {@code gen_ai.operation.name}. */
    public static final String GEN_AI_OPERATION = "gen_ai.operation.name";

    /** Token type: "input" or "output". Maps to {@code gen_ai.token.type}. */
    public static final String GEN_AI_TOKEN_TYPE = "gen_ai.token.type";

    /** Error class name on failure (OTel standard). */
    public static final String ERROR_TYPE = "error.type";

    // ── Legate-specific tag keys ──────────────────────────────────────────────

    /** Virtual key ID that authenticated the request; "none" if unauthenticated. */
    public static final String VIRTUAL_KEY = "virtual_key";

    /** Provider that triggered a fallback. */
    public static final String FROM_PROVIDER = "gen_ai.system.from";

    /** Provider that served the request after a fallback. */
    public static final String TO_PROVIDER = "gen_ai.system.to";

    /** Spend limit type that was breached (e.g., "daily", "monthly"). */
    public static final String LIMIT_TYPE = "limit_type";

    /** Circuit breaker state before a transition. */
    public static final String FROM_STATE = "from_state";

    /** Circuit breaker state after a transition. */
    public static final String TO_STATE = "to_state";

    // ── Tag values ────────────────────────────────────────────────────────────

    /** {@link #GEN_AI_TOKEN_TYPE} value for prompt/input tokens. */
    public static final String TOKEN_TYPE_INPUT = "input";

    /** {@link #GEN_AI_TOKEN_TYPE} value for completion/output tokens. */
    public static final String TOKEN_TYPE_OUTPUT = "output";

    /** Sentinel for an absent or unknown tag value. */
    public static final String UNKNOWN = "unknown";

    /** Sentinel for {@link #VIRTUAL_KEY} when no key authenticated the request. */
    public static final String NONE = "none";

    private MetricTags() {}
}
