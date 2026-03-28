package io.legate.server.constants;

/**
 * HTTP header name and value constants used by the Legate gateway.
 *
 * <p>Centralising these prevents silent drift when a header name is referenced
 * in multiple places (handler, filter, tests). All names follow the
 * {@code X-Legate-*} vendor prefix convention.</p>
 */
public final class LegateHeaders {

    // ── Header names ─────────────────────────────────────────────────────────

    /** Unique identifier assigned to every request, propagated in responses. */
    public static final String REQUEST_ID = "X-Legate-Request-Id";

    /**
     * Cache-status header set on every chat-completion response.
     *
     * @see #CACHE_HIT
     * @see #CACHE_MISS
     */
    public static final String CACHE_STATUS = "X-Legate-Cache";

    /**
     * Set on responses where a fallback provider was used.
     * Value is the name of the provider that ultimately served the request.
     */
    public static final String FALLBACK_PROVIDER = "X-Legate-Fallback";

    // ── Cache-status values ───────────────────────────────────────────────────

    /** {@code X-Legate-Cache} value indicating the response was served from cache. */
    public static final String CACHE_HIT = "HIT";

    /** {@code X-Legate-Cache} value indicating the upstream was called (cache miss). */
    public static final String CACHE_MISS = "MISS";

    /**
     * Request-time header value instructing Legate to skip the cache lookup
     * for this request without populating the cache with the response.
     */
    public static final String CACHE_SKIP = "skip";

    /**
     * Request-time header value instructing Legate to skip the cache lookup
     * but store the fresh upstream response in the cache.
     */
    public static final String CACHE_REFRESH = "refresh";

    /** Standard HTTP {@code Retry-After} header (seconds until rate limit resets). */
    public static final String RETRY_AFTER = "Retry-After";

    private LegateHeaders() {
        // constants class — no instances
    }
}
