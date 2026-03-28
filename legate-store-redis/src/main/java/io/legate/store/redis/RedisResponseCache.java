package io.legate.store.redis;

import tools.jackson.databind.ObjectMapper;
import io.legate.core.cache.CacheKey;
import io.legate.core.cache.LegateCacheStats;
import io.legate.core.cache.CachedResponse;
import io.legate.core.cache.ResponseCache;
import io.legate.core.config.cache.CacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-backed {@link ResponseCache} using Spring Data Redis reactive templates.
 *
 * <p>Cached responses are serialised to JSON and stored with the configured TTL.
 * Stats (hits/misses) are maintained in-memory — reset on restart.</p>
 *
 * <h3>Redis key format</h3>
 * <pre>{@code legate:cache:<sha256-hash>}</pre>
 */
public class RedisResponseCache implements ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(RedisResponseCache.class);
    private static final String KEY_PREFIX = "legate:cache:";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CacheConfig config;

    private final AtomicLong hits      = new AtomicLong();
    private final AtomicLong misses    = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    public RedisResponseCache(
        ReactiveStringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        CacheConfig config
    ) {
        this.redisTemplate  = redisTemplate;
        this.objectMapper   = objectMapper;
        this.config         = config;
    }

    @Override
    public Optional<CachedResponse> get(CacheKey key) {
        String redisKey = KEY_PREFIX + key.hash();
        try {
            String json = redisTemplate.opsForValue()
                .get(redisKey)
                .block(Duration.ofSeconds(5));

            if (json == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }

            CachedResponse cached = objectMapper.readValue(json, CachedResponse.class);
            if (cached.isExpired()) {
                evict(key);
                misses.incrementAndGet();
                return Optional.empty();
            }

            hits.incrementAndGet();
            return Optional.of(cached);
        } catch (Exception e) {
            log.warn("Redis cache get failed for key {}: {}", redisKey, e.getMessage());
            misses.incrementAndGet();
            return Optional.empty();
        }
    }

    @Override
    public void put(CacheKey key, CachedResponse response) {
        String redisKey = KEY_PREFIX + key.hash();
        try {
            String json = objectMapper.writeValueAsString(response);
            Duration ttl = config.ttl();
            redisTemplate.opsForValue()
                .set(redisKey, json, ttl)
                .subscribe(
                    ok  -> log.debug("Cached response at {}", redisKey),
                    err -> log.warn("Redis cache put failed for {}: {}", redisKey, err.getMessage())
                );
        } catch (Exception e) {
            log.warn("Redis cache serialization failed for key {}: {}", redisKey, e.getMessage());
        }
    }

    @Override
    public void evict(CacheKey key) {
        String redisKey = KEY_PREFIX + key.hash();
        redisTemplate.delete(redisKey)
            .subscribe(
                deleted -> { if (deleted > 0) evictions.incrementAndGet(); },
                err     -> log.warn("Redis evict failed for {}: {}", redisKey, err.getMessage())
            );
    }

    @Override
    public void clear() {
        // Scan and delete all legate:cache:* keys
        redisTemplate.scan(
            org.springframework.data.redis.core.ScanOptions.scanOptions()
                .match(KEY_PREFIX + "*")
                .count(100)
                .build()
        )
        .flatMap(redisTemplate::delete)
        .subscribe(
            deleted -> log.debug("Cleared Redis cache entries"),
            err     -> log.warn("Redis clear failed: {}", err.getMessage())
        );
    }

    @Override
    public LegateCacheStats getStats() {
        // Size requires DBSIZE or a scan — too expensive; report -1 (unknown)
        return new LegateCacheStats(hits.get(), misses.get(), evictions.get(), -1L, -1L);
    }
}
