package io.legate.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.legate.core.config.cache.CacheConfig;

import java.time.Duration;
import java.util.Optional;

/**
 * In-memory {@link ResponseCache} backed by Caffeine.
 *
 * <p>Configured from {@link CacheConfig}:</p>
 * <ul>
 *   <li>{@code maxSize}  — maximum number of entries (LRU eviction when full)</li>
 *   <li>{@code ttl}      — per-entry time-to-live after write</li>
 * </ul>
 *
 * <p>Thread-safe: Caffeine is inherently concurrent.</p>
 */
public class CaffeineResponseCache implements ResponseCache {

    private static final int DEFAULT_MAX_SIZE = 10_000;
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final Cache<String, CachedResponse> cache;
    private final long maxSize;

    /**
     * Creates a cache from the given configuration.
     */
    public CaffeineResponseCache(CacheConfig config) {
        int size = config != null && config.maxSize() > 0 ? config.maxSize() : DEFAULT_MAX_SIZE;
        Duration ttl = config != null && config.ttl() != null ? config.ttl() : DEFAULT_TTL;
        this.maxSize = size;
        this.cache = Caffeine.newBuilder()
            .maximumSize(size)
            .expireAfterWrite(ttl)
            .recordStats()
            .build();
    }

    /** Creates a cache with defaults (10 000 entries, 1 hour TTL). */
    public CaffeineResponseCache() {
        this(CacheConfig.defaults());
    }

    @Override
    public Optional<CachedResponse> get(CacheKey key) {
        CachedResponse cached = cache.getIfPresent(key.hash());
        if (cached != null && !cached.isExpired()) {
            return Optional.of(cached);
        }
        if (cached != null) {
            cache.invalidate(key.hash()); // evict expired
        }
        return Optional.empty();
    }

    @Override
    public void put(CacheKey key, CachedResponse response) {
        cache.put(key.hash(), response);
    }

    @Override
    public void evict(CacheKey key) {
        cache.invalidate(key.hash());
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }

    @Override
    public LegateCacheStats getStats() {
        CacheStats stats = cache.stats();
        return new LegateCacheStats(
            stats.hitCount(),
            stats.missCount(),
            stats.evictionCount(),
            cache.estimatedSize(),
            maxSize
        );
    }
}
