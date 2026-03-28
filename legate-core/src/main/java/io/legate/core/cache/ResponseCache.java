package io.legate.core.cache;

import java.util.Optional;

/**
 * SPI for the response cache.
 *
 * <p>Implementations must be thread-safe and handle concurrent access gracefully.</p>
 *
 * <p>Default implementation: {@link CaffeineResponseCache} (in-memory).
 * Phase 4 adds {@code RedisResponseCache} for distributed deployments.</p>
 */
public interface ResponseCache {

    /**
     * Retrieves a cached response.
     *
     * @param key the cache key computed from the request
     * @return the cached response, or {@link Optional#empty()} on a miss or expired entry
     */
    Optional<CachedResponse> get(CacheKey key);

    /**
     * Stores a response in the cache.
     *
     * @param key      the cache key
     * @param response the response to cache
     */
    void put(CacheKey key, CachedResponse response);

    /**
     * Evicts a single entry from the cache.
     *
     * @param key the key to evict
     */
    void evict(CacheKey key);

    /**
     * Clears all entries from the cache.
     */
    void clear();

    /**
     * Returns current cache statistics.
     */
    LegateCacheStats getStats();
}
