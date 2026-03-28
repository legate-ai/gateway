package io.legate.core.config.cache;

/**
 * Storage backend used for the exact-match response cache.
 *
 * <p>YAML usage:</p>
 * <pre>{@code
 * legate:
 *   cache:
 *     backend: redis    # or REDIS, MEMORY
 * }</pre>
 */
public enum CacheBackend {

    /**
     * Caffeine in-process cache. Zero infrastructure dependencies; data is lost on restart.
     * Best for single-instance deployments or development. This is the default.
     */
    MEMORY,

    /**
     * Redis distributed cache via Lettuce reactive client.
     * Requires the {@code legate-store-redis} module on the classpath and
     * a Redis connection configured under {@code spring.data.redis}.
     * Data persists across restarts and is shared across all Legate instances.
     */
    REDIS
}
