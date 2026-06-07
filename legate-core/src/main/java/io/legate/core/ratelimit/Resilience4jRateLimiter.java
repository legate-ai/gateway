package io.legate.core.ratelimit;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.legate.core.config.ratelimit.RateLimitingConfig;
import io.legate.core.config.ratelimit.RateLimitingConfig.RateLimitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter backed by Resilience4j for per-key request-rate enforcement (RPM),
 * with a lightweight custom {@code DailyTokenCounter} for daily LLM-token quota.
 *
 * <p>Two independent axes per virtual key:</p>
 * <ol>
 *   <li><b>Request rate</b> — managed by a Resilience4j {@link io.github.resilience4j.ratelimiter.RateLimiter}
 *       with {@code limitForPeriod = requestsPerMinute} and a 60-second refresh window.</li>
 *   <li><b>Daily token quota</b> — tracked by an {@link AtomicLong} counter that resets at UTC midnight.
 *       Resilience4j does not support post-call permit deduction, so a simple counter is used here
 *       to allow accurate accounting via {@link #reportUsage}.</li>
 * </ol>
 *
 * <p>Both the global key ({@code "__global__"}) and per-virtual-key buckets are maintained
 * independently. A request is denied if <em>either</em> is exhausted.</p>
 *
 * <p>Thread-safe: Resilience4j's registry and {@code AtomicLong} ensure concurrent safety.</p>
 */
public class Resilience4jRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(Resilience4jRateLimiter.class);
    static final String GLOBAL_KEY = "__global__";

    static final long ROLLING_WINDOW_MS = 60_000L;

    private volatile RateLimitingConfig config;
    private final RateLimiterRegistry r4jRegistry;
    private final ConcurrentHashMap<String, DailyTokenCounter> tokenCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SlidingWindowTokenBucket> rollingBuckets = new ConcurrentHashMap<>();

    /**
     * Creates a rate limiter from the given configuration.
     *
     * @param config rate-limiting configuration; must not be {@code null}
     */
    public Resilience4jRateLimiter(RateLimitingConfig config) {
        this.config = config;
        this.r4jRegistry = RateLimiterRegistry.ofDefaults();
    }

    @Override
    public RateLimitResult tryAcquire(String key, int estimatedTokens) {
        // Check per-key limit FIRST — if per-key denies, global permit is never consumed
        RateLimitConfig keyCfg = config.resolvePerKeyLimit(key);
        if (!keyCfg.isUnlimited()) {
            RateLimitResult keyResult = checkLimits(key, keyCfg, estimatedTokens);
            if (keyResult instanceof RateLimitResult.Denied) {
                return keyResult;
            }
        }

        // Check global limit only after per-key passes
        RateLimitConfig globalCfg = config.global();
        if (globalCfg != null && !globalCfg.isUnlimited()) {
            return checkLimits(GLOBAL_KEY, globalCfg, estimatedTokens);
        }

        return new RateLimitResult.Allowed(Integer.MAX_VALUE, Long.MAX_VALUE,
                Instant.now().plusSeconds(60));
    }

    /**
     * Updates the rate-limiting configuration without restart (hot-reload support).
     * Note: existing Resilience4j rate-limiter instances are not rebuilt; new per-key
     * limits take effect only for virtual keys that have not yet made any request.
     */
    public void reload(RateLimitingConfig newConfig) {
        this.config = newConfig != null ? newConfig : RateLimitingConfig.disabled();
        log.info("Resilience4jRateLimiter configuration reloaded.");
    }

    @Override
    public void reportUsage(String key, int actualTokens) {
        reportUsage(key, 0, actualTokens);
    }

    @Override
    public void reportUsage(String key, int reservedTokens, int actualTokens) {
        if (actualTokens < 0) {
            return;
        }
        int delta = actualTokens - reservedTokens;

        RateLimitConfig globalCfg = config.global();
        if (globalCfg != null && globalCfg.hasTokenLimit()) {
            if (reservedTokens > 0) {
                getOrCreateRollingBucket(GLOBAL_KEY, globalCfg).adjust(reservedTokens, actualTokens);
            } else {
                getOrCreateTokenCounter(GLOBAL_KEY).add(actualTokens);
            }
        }

        RateLimitConfig keyCfg = config.resolvePerKeyLimit(key);
        if (keyCfg.hasTokenLimit()) {
            if (reservedTokens > 0) {
                getOrCreateRollingBucket(key, keyCfg).adjust(reservedTokens, actualTokens);
            } else {
                getOrCreateTokenCounter(key).add(actualTokens);
            }
        }
    }

    // -------------------------------------------------------------------------

    private RateLimitResult checkLimits(String key, RateLimitConfig cfg, int estimatedTokens) {
        io.github.resilience4j.ratelimiter.RateLimiter rl =
                cfg.hasRequestLimit() ? getOrCreateRequestRateLimiter(key, cfg) : null;

        // Check request rate with Resilience4j
        if (rl != null && !rl.acquirePermission()) {
            long retryAfter = 60L / Math.max(1, cfg.requestsPerMinute());
            return new RateLimitResult.Denied(
                    "Request rate limit exceeded: " + cfg.requestsPerMinute()
                            + " req/min for key '" + key + "'",
                    retryAfter,
                    cfg.requestsPerMinute(),
                    cfg.requestsPerMinute() - Math.max(0,
                            rl.getMetrics().getAvailablePermissions()));
        }

        // Pre-reserve in the rolling window bucket; also check daily cap.
        if (cfg.hasTokenLimit()) {
            SlidingWindowTokenBucket bucket = getOrCreateRollingBucket(key, cfg);
            if (!bucket.tryReserve(estimatedTokens)) {
                long secondsUntilReset = secondsUntilMidnightUtc();
                return new RateLimitResult.Denied(
                        "Rolling token limit exceeded for key '" + key + "'",
                        secondsUntilReset,
                        cfg.tokensPerDay(),
                        bucket.getWindowTotal());
            }
            DailyTokenCounter daily = getOrCreateTokenCounter(key);
            long used = daily.getUsed();
            if (used + estimatedTokens > cfg.tokensPerDay()) {
                // Undo the rolling reservation since we're rejecting
                bucket.adjust(estimatedTokens, 0);
                long secondsUntilReset = secondsUntilMidnightUtc();
                return new RateLimitResult.Denied(
                        "Daily token limit exceeded: " + cfg.tokensPerDay()
                                + " tokens/day for key '" + key + "'",
                        secondsUntilReset,
                        cfg.tokensPerDay(),
                        used);
            }
            daily.add(estimatedTokens);
        }

        int remainingRequests = rl != null
                ? Math.max(0, rl.getMetrics().getAvailablePermissions())
                : Integer.MAX_VALUE;
        long remainingTokens = cfg.hasTokenLimit()
                ? cfg.tokensPerDay() - getOrCreateTokenCounter(key).getUsed()
                : Long.MAX_VALUE;

        return new RateLimitResult.Allowed(remainingRequests, remainingTokens,
                Instant.now().plusSeconds(60), estimatedTokens);
    }

    private io.github.resilience4j.ratelimiter.RateLimiter getOrCreateRequestRateLimiter(
            String key, RateLimitConfig cfg) {
        return r4jRegistry.rateLimiter(key,
                RateLimiterConfig.custom()
                        .limitForPeriod(cfg.requestsPerMinute())
                        .limitRefreshPeriod(Duration.ofSeconds(60))
                        .timeoutDuration(Duration.ZERO)   // non-blocking: return false immediately
                        .build());
    }

    private DailyTokenCounter getOrCreateTokenCounter(String key) {
        return tokenCounters.computeIfAbsent(key, k -> new DailyTokenCounter());
    }

    private SlidingWindowTokenBucket getOrCreateRollingBucket(String key, RateLimitConfig cfg) {
        return rollingBuckets.computeIfAbsent(key,
            k -> new SlidingWindowTokenBucket(cfg.tokensPerDay(), ROLLING_WINDOW_MS));
    }

    private static long secondsUntilMidnightUtc() {
        long now = Instant.now().getEpochSecond();
        long midnightTomorrow = LocalDate.now(ZoneOffset.UTC)
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toEpochSecond();
        return Math.max(60L, midnightTomorrow - now);
    }

    // -------------------------------------------------------------------------
    // Daily token counter — resets at UTC midnight
    // -------------------------------------------------------------------------

    private static final class DailyTokenCounter {
        private final AtomicLong used = new AtomicLong(0);
        private volatile LocalDate date = LocalDate.now(ZoneOffset.UTC);

        /**
         * Atomically checks whether {@code tokens} can be added without exceeding {@code limit},
         * and if so, adds them. Returns {@code true} if the reservation succeeded.
         * This eliminates the TOCTOU race between check and increment.
         */
        synchronized boolean tryReserve(long tokens, long limit) {
            resetIfNewDay();
            long current = used.get();
            if (current + tokens > limit) {
                return false;
            }
            used.addAndGet(tokens);
            return true;
        }

        void add(long tokens) {
            resetIfNewDay();
            used.addAndGet(tokens);
        }

        long getUsed() {
            resetIfNewDay();
            return used.get();
        }

        private synchronized void resetIfNewDay() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (!today.equals(date)) {
                used.set(0);
                date = today;
            }
        }
    }
}
