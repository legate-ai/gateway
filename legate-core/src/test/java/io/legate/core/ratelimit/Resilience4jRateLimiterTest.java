package io.legate.core.ratelimit;

import io.legate.core.config.ratelimit.RateLimitingConfig;
import io.legate.core.config.ratelimit.RateLimitingConfig.RateLimitConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Resilience4jRateLimiterTest {

    // ── Unlimited config ──────────────────────────────────────────────────────

    @Test
    void tryAcquire_unlimitedConfig_alwaysAllowed() {
        var limiter = new Resilience4jRateLimiter(unlimitedConfig());
        assertThat(limiter.tryAcquire("key1", 1000)).isInstanceOf(RateLimitResult.Allowed.class);
        assertThat(limiter.tryAcquire("key1", 1000)).isInstanceOf(RateLimitResult.Allowed.class);
    }

    // ── Request rate limit ────────────────────────────────────────────────────

    @Test
    void tryAcquire_withinRequestRateLimit_returnsAllowed() {
        var limiter = new Resilience4jRateLimiter(perKeyConfig("k1", 10, 0));
        assertThat(limiter.tryAcquire("k1", 0)).isInstanceOf(RateLimitResult.Allowed.class);
    }

    @Test
    void tryAcquire_exceedsRequestRateLimit_returnsDenied() {
        var limiter = new Resilience4jRateLimiter(perKeyConfig("k1", 2, 0));

        assertThat(limiter.tryAcquire("k1", 0)).isInstanceOf(RateLimitResult.Allowed.class);
        assertThat(limiter.tryAcquire("k1", 0)).isInstanceOf(RateLimitResult.Allowed.class);

        RateLimitResult third = limiter.tryAcquire("k1", 0);
        assertThat(third).isInstanceOf(RateLimitResult.Denied.class);
        assertThat(((RateLimitResult.Denied) third).reason()).contains("req/min");
    }

    @Test
    void tryAcquire_requestRateLimitIsolatedPerKey() {
        var limiter = new Resilience4jRateLimiter(perKeyConfig("k1", 1, 0));

        assertThat(limiter.tryAcquire("k1", 0)).isInstanceOf(RateLimitResult.Allowed.class);
        assertThat(limiter.tryAcquire("k1", 0)).isInstanceOf(RateLimitResult.Denied.class);

        // k2 is separate — should still be allowed
        assertThat(limiter.tryAcquire("k2", 0)).isInstanceOf(RateLimitResult.Allowed.class);
    }

    @Test
    void allowed_remainingRequestsDecreases() {
        var limiter = new Resilience4jRateLimiter(perKeyConfig("k1", 5, 0));

        RateLimitResult first = limiter.tryAcquire("k1", 0);
        assertThat(first).isInstanceOf(RateLimitResult.Allowed.class);
        assertThat(((RateLimitResult.Allowed) first).remainingRequests()).isLessThan(5);
    }

    // ── Daily token quota ─────────────────────────────────────────────────────

    @Test
    void tryAcquire_withinDailyTokenLimit_returnsAllowed() {
        var limiter = new Resilience4jRateLimiter(perKeyConfig("k1", 0, 1_000));
        assertThat(limiter.tryAcquire("k1", 100)).isInstanceOf(RateLimitResult.Allowed.class);
    }

    @Test
    void tryAcquire_exceedsDailyTokenLimit_returnsDenied() {
        var limiter = new Resilience4jRateLimiter(perKeyConfig("k1", 0, 100));
        limiter.reportUsage("k1", 90);

        RateLimitResult result = limiter.tryAcquire("k1", 20); // 90 + 20 > 100
        assertThat(result).isInstanceOf(RateLimitResult.Denied.class);
        assertThat(((RateLimitResult.Denied) result).reason()).contains("token");
    }

    @Test
    void reportUsage_accumulatesTokens() {
        var limiter = new Resilience4jRateLimiter(perKeyConfig("k1", 0, 200));

        limiter.reportUsage("k1", 100);
        limiter.reportUsage("k1", 100);

        RateLimitResult result = limiter.tryAcquire("k1", 10);
        assertThat(result).isInstanceOf(RateLimitResult.Denied.class);
    }

    @Test
    void reportUsage_tokenLimitIsolatedPerKey() {
        var limiter = new Resilience4jRateLimiter(perKeyConfig("k1", 0, 100));
        limiter.reportUsage("k1", 99);

        // k1 is nearly full
        assertThat(limiter.tryAcquire("k1", 10)).isInstanceOf(RateLimitResult.Denied.class);
        // k2 is unaffected
        assertThat(limiter.tryAcquire("k2", 10)).isInstanceOf(RateLimitResult.Allowed.class);
    }

    @Test
    void allowed_remainingTokensReflectsUsage() {
        var limiter = new Resilience4jRateLimiter(perKeyConfig("k1", 0, 1_000));
        limiter.reportUsage("k1", 300);

        RateLimitResult result = limiter.tryAcquire("k1", 0);
        assertThat(result).isInstanceOf(RateLimitResult.Allowed.class);
        assertThat(((RateLimitResult.Allowed) result).remainingTokens()).isEqualTo(700);
    }

    // ── Global limit ──────────────────────────────────────────────────────────

    @Test
    void tryAcquire_globalTokenLimitExceeded_denyAllKeys() {
        RateLimitConfig global = new RateLimitConfig(0, 50);
        var config = new RateLimitingConfig(global, new RateLimitConfig(0, 0), Map.of());
        var limiter = new Resilience4jRateLimiter(config);

        limiter.reportUsage(Resilience4jRateLimiter.GLOBAL_KEY, 45);

        // key-specific check also fails because global is over
        assertThat(limiter.tryAcquire("any-key", 10)).isInstanceOf(RateLimitResult.Denied.class);
    }

    // ── Denied fields ─────────────────────────────────────────────────────────

    @Test
    void denied_retryAfterIsPositive() {
        var limiter = new Resilience4jRateLimiter(perKeyConfig("k1", 1, 0));
        limiter.tryAcquire("k1", 0); // consume the only permit

        RateLimitResult result = limiter.tryAcquire("k1", 0);
        assertThat(result).isInstanceOf(RateLimitResult.Denied.class);
        assertThat(((RateLimitResult.Denied) result).retryAfter()).isPositive();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static RateLimitingConfig unlimitedConfig() {
        return new RateLimitingConfig(
                new RateLimitConfig(0, 0),
                new RateLimitConfig(0, 0),
                Map.of());
    }

    /**
     * Config with no global limit; per-key limit for {@code keyId} only.
     *
     * @param requestsPerMinute 0 = unlimited
     * @param tokensPerDay      0 = unlimited
     */
    private static RateLimitingConfig perKeyConfig(String keyId, int requestsPerMinute,
                                                   long tokensPerDay) {
        return new RateLimitingConfig(
                new RateLimitConfig(0, 0),
                new RateLimitConfig(0, 0),
                Map.of(keyId, new RateLimitConfig(requestsPerMinute, tokensPerDay)));
    }
}
