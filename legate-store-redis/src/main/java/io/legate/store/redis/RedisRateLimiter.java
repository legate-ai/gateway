package io.legate.store.redis;

import io.legate.core.config.ratelimit.RateLimitingConfig;
import io.legate.core.config.ratelimit.RateLimitingConfig.RateLimitConfig;
import io.legate.core.ratelimit.RateLimitResult;
import io.legate.core.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Redis-backed {@link RateLimiter} using atomic INCR operations.
 *
 * <p>Two independent quotas are enforced per key:</p>
 * <ol>
 *   <li><b>Request quota</b> — counter with 60-second TTL; capped at {@code requestsPerMinute}.</li>
 *   <li><b>Token quota</b> — daily counter keyed by date; expires at UTC midnight.</li>
 * </ol>
 *
 * <p>Falls back gracefully on Redis failures: a warning is logged and the request is allowed
 * (fail-open) to avoid blocking traffic when Redis is unavailable.</p>
 *
 * <h3>Redis key formats</h3>
 * <pre>
 *   legate:rl:req:{keyId}            — request-rate counter (60s TTL)
 *   legate:rl:req:__global__         — global request-rate counter
 *   legate:rl:tokens:{keyId}:{date}  — daily token counter (expires at midnight)
 * </pre>
 */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final String REQ_PREFIX = "legate:rl:req:";
    private static final String TOKEN_PREFIX = "legate:rl:tokens:";
    static final String GLOBAL_KEY = "__global__";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RateLimitingConfig config;

    public RedisRateLimiter(ReactiveStringRedisTemplate redisTemplate, RateLimitingConfig config) {
        this.redisTemplate = redisTemplate;
        this.config = config;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int estimatedTokens) {
        try {
            // Check global limit
            RateLimitConfig globalCfg = config.global();
            if (globalCfg != null && !globalCfg.isUnlimited()) {
                RateLimitResult result = checkLimits(GLOBAL_KEY, globalCfg, estimatedTokens);
                if (result instanceof RateLimitResult.Denied) {
                    return result;
                }
            }

            // Check per-key limit
            RateLimitConfig keyCfg = config.resolvePerKeyLimit(key);
            if (!keyCfg.isUnlimited()) {
                return checkLimits(key, keyCfg, estimatedTokens);
            }

            return new RateLimitResult.Allowed(Integer.MAX_VALUE, Long.MAX_VALUE, Instant.now().plusSeconds(60));
        } catch (Exception e) {
            log.warn("Redis rate limiter error (fail-open): {}", e.getMessage());
            return new RateLimitResult.Allowed(Integer.MAX_VALUE, Long.MAX_VALUE, Instant.now().plusSeconds(60));
        }
    }

    @Override
    public void reportUsage(String key, int actualTokens) {
        if (actualTokens <= 0) {
            return;
        }
        try {
            // Report to global counter
            RateLimitConfig globalCfg = config.global();
            if (globalCfg != null && globalCfg.hasTokenLimit()) {
                incrementTokenCounter(GLOBAL_KEY, actualTokens);
            }
            // Report to per-key counter
            RateLimitConfig keyCfg = config.resolvePerKeyLimit(key);
            if (keyCfg.hasTokenLimit()) {
                incrementTokenCounter(key, actualTokens);
            }
        } catch (Exception e) {
            log.warn("Redis reportUsage error for key {}: {}", key, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------

    private RateLimitResult checkLimits(String key, RateLimitConfig cfg, int estimatedTokens) {
        // Check request rate
        if (cfg.hasRequestLimit()) {
            String reqKey = REQ_PREFIX + key;
            Long count = redisTemplate.opsForValue()
                    .increment(reqKey)
                    .flatMap(c -> {
                        if (c == 1L) {
                            // First request in window — set 60-second TTL
                            return redisTemplate.expire(reqKey, Duration.ofSeconds(60)).thenReturn(c);
                        }
                        return reactor.core.publisher.Mono.just(c);
                    })
                    .block(Duration.ofSeconds(5));

            if (count != null && count > cfg.requestsPerMinute()) {
                return new RateLimitResult.Denied(
                        "Request rate limit exceeded: " + cfg.requestsPerMinute() + " req/min for '" + key + "'",
                        60L,
                        cfg.requestsPerMinute(),
                        count
                );
            }
        }

        // Check token quota (pre-flight check only; actual deduction in reportUsage)
        if (cfg.hasTokenLimit() && estimatedTokens > 0) {
            String todayKey = TOKEN_PREFIX + key + ":" + LocalDate.now(ZoneOffset.UTC);
            String currentStr = redisTemplate.opsForValue()
                    .get(todayKey)
                    .defaultIfEmpty("0")
                    .block(Duration.ofSeconds(5));
            long current = parseOrZero(currentStr);

            if (current + estimatedTokens > cfg.tokensPerDay()) {
                return new RateLimitResult.Denied(
                        "Daily token limit exceeded: " + cfg.tokensPerDay() + " tokens/day for '" + key + "'",
                        secondsUntilMidnight(),
                        cfg.tokensPerDay(),
                        current
                );
            }
        }

        int remaining = cfg.hasRequestLimit() ? (int) Math.max(0, cfg.requestsPerMinute() - currentReqCount(key)) : Integer.MAX_VALUE;
        long remainingTokens = cfg.hasTokenLimit()
                ? cfg.tokensPerDay() - parseOrZero(redisTemplate.opsForValue()
                .get(TOKEN_PREFIX + key + ":" + LocalDate.now(ZoneOffset.UTC))
                .defaultIfEmpty("0")
                .block(Duration.ofSeconds(3)))
                : Long.MAX_VALUE;

        return new RateLimitResult.Allowed(remaining, remainingTokens, Instant.now().plusSeconds(60));
    }

    private void incrementTokenCounter(String key, int tokens) {
        String todayKey = TOKEN_PREFIX + key + ":" + LocalDate.now(ZoneOffset.UTC);
        redisTemplate.opsForValue()
                .increment(todayKey, tokens)
                .flatMap(v -> {
                    if (v == tokens) {
                        // First tokens today — expire at midnight UTC
                        long secondsUntilMidnight = secondsUntilMidnight();
                        return redisTemplate.expire(todayKey, Duration.ofSeconds(secondsUntilMidnight + 60));
                    }
                    return reactor.core.publisher.Mono.just(true);
                })
                .subscribe(
                        _ -> {
                        },
                        err -> log.warn("Token counter increment failed for {}: {}", key, err.getMessage())
                );
    }

    private long currentReqCount(String key) {
        try {
            String val = redisTemplate.opsForValue()
                    .get(REQ_PREFIX + key)
                    .defaultIfEmpty("0")
                    .block(Duration.ofSeconds(3));
            return parseOrZero(val);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long parseOrZero(String val) {
        if (val == null || val.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long secondsUntilMidnight() {
        long now = Instant.now().getEpochSecond();
        long midnight = LocalDate.now(ZoneOffset.UTC)
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toEpochSecond();
        return Math.max(60L, midnight - now);
    }
}
