package io.legate.core.cache;

/**
 * Snapshot of cache performance metrics.
 *
 * @param hits      total number of cache hits since startup
 * @param misses    total number of cache misses since startup
 * @param evictions total number of entries evicted (TTL or capacity)
 * @param size      current number of entries in the cache
 * @param maxSize   maximum configured cache capacity
 */
public record LegateCacheStats(long hits, long misses, long evictions, long size, long maxSize) {

    /** Returns the cache hit rate (0.0–1.0), or 0.0 if no requests have been made. */
    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }
}
