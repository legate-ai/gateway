package io.legate.store.redis;

import tools.jackson.databind.ObjectMapper;
import io.legate.core.cache.ResponseCache;
import io.legate.core.config.cache.CacheBackend;
import io.legate.core.config.LegateConfig;
import io.legate.core.ratelimit.RateLimiter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Auto-configuration for Redis-backed Legate stores.
 *
 * <p>Activates when {@code spring-boot-starter-data-redis-reactive} (and thus
 * {@link ReactiveStringRedisTemplate}) is on the classpath.</p>
 *
 * <h3>Beans provided</h3>
 * <ul>
 *   <li>{@code legateResponseCache} — {@link RedisResponseCache}, when
 *       {@code legate.cache.backend=REDIS}</li>
 *   <li>{@code legateRateLimiter} — {@link RedisRateLimiter}, when
 *       {@code legate.rate-limiting.backend=redis} (opt-in)</li>
 * </ul>
 *
 * <p>Both beans are {@code @ConditionalOnMissingBean} so they can be overridden.</p>
 */
@AutoConfiguration(
    beforeName = "io.legate.spring.autoconfigure.LegateAutoConfiguration"
)
@ConditionalOnClass(ReactiveStringRedisTemplate.class)
public class RedisAutoConfiguration {

    /**
     * Redis-backed response cache — replaces the in-memory Caffeine cache
     * when {@code legate.cache.backend=REDIS}.
     */
    @Bean
    @ConditionalOnMissingBean(ResponseCache.class)
    @ConditionalOnProperty(prefix = "legate.cache", name = "backend", havingValue = "REDIS", matchIfMissing = false)
    public ResponseCache legateResponseCache(
        ReactiveStringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        LegateConfig legateConfig
    ) {
        return new RedisResponseCache(redisTemplate, objectMapper, legateConfig.cache());
    }

    /**
     * Redis-backed rate limiter — replaces the in-memory token-bucket limiter
     * when {@code legate.rate-limiting.backend=redis}.
     */
    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    @ConditionalOnProperty(prefix = "legate.rate-limiting", name = "backend", havingValue = "redis", matchIfMissing = false)
    public RateLimiter legateRateLimiter(
        ReactiveStringRedisTemplate redisTemplate,
        LegateConfig legateConfig
    ) {
        return new RedisRateLimiter(redisTemplate, legateConfig.rateLimiting());
    }
}
