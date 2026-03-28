package io.legate.core.config.cache;

import java.time.Duration;

/**
 * Exact-match response cache configuration.
 *
 * <p>When enabled, Legate computes a SHA-256 fingerprint of the normalised request
 * (model + messages + sampling parameters) and returns cached responses for
 * identical deterministic requests without calling the upstream provider.</p>
 *
 * <h3>Cacheability criteria</h3>
 * <p>A request is eligible for caching only when all of the following are true:</p>
 * <ul>
 *   <li>{@code stream} is {@code null} or {@code false}</li>
 *   <li>{@code temperature} is {@code null} or {@code 0.0}</li>
 *   <li>{@code n} is {@code null} or {@code 1}</li>
 * </ul>
 *
 * <h3>Cache bypass headers</h3>
 * <ul>
 *   <li>{@code X-Legate-Cache: skip} — skip the lookup but still cache the new response.</li>
 *   <li>{@code X-Legate-Cache: refresh} — skip the lookup <em>and</em> overwrite any existing entry.</li>
 *   <li>{@code X-Legate-Cache: no-store} — skip lookup and do not cache the response.</li>
 * </ul>
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   cache:
 *     enabled: true
 *     backend: memory     # or MEMORY, REDIS
 *     max-size: 10000
 *     ttl: 1h
 * }</pre>
 */
public record CacheConfig(

    /**
     * Whether exact-match caching is enabled. Default: {@code false}.
     */
    boolean enabled,

    /**
     * Cache storage backend. Default: {@link CacheBackend#MEMORY}.
     */
    CacheBackend backend,

    /**
     * Maximum number of entries in the cache (memory backend only).
     * Least-recently-used entries are evicted when this limit is reached.
     * Default: {@code 10,000}.
     */
    int maxSize,

    /**
     * Time-to-live for cached entries. After this duration the entry is
     * evicted and the next matching request is forwarded upstream.
     * Default: {@code 1 hour}.
     */
    Duration ttl

) {
    public CacheConfig {
        if (backend == null)  {
            backend = CacheBackend.MEMORY;
        }
        if (maxSize <= 0){
            maxSize = 10_000;
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofHours(1);
        }
    }

    /** Default cache config — disabled, memory backend, 10 k entries, 1 h TTL. */
    public static CacheConfig defaults() {
        return new CacheConfig(false, CacheBackend.MEMORY, 10_000, Duration.ofHours(1));
    }

    /** Returns a config with caching enabled using in-memory backend with given TTL. */
    public static CacheConfig inMemory(int maxSize, Duration ttl) {
        return new CacheConfig(true, CacheBackend.MEMORY, maxSize, ttl);
    }

    /** Returns a config with caching enabled using Redis backend with given TTL. */
    public static CacheConfig redis(Duration ttl) {
        return new CacheConfig(true, CacheBackend.REDIS, 0, ttl);
    }
}
