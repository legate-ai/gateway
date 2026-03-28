package io.legate.core.config;

import io.legate.core.config.admin.AdminConfig;
import io.legate.core.config.alert.AlertConfig;
import io.legate.core.config.cache.CacheConfig;
import io.legate.core.config.guard.GuardConfig;
import io.legate.core.config.logging.LoggingConfig;
import io.legate.core.config.pricing.ModelPricingConfig;
import io.legate.core.config.provider.ProviderConfig;
import io.legate.core.config.ratelimit.RateLimitingConfig;
import io.legate.core.config.routing.RoutingConfig;
import io.legate.core.config.spend.SpendControlConfig;

import java.util.List;

/**
 * Root configuration model for the Legate AI Gateway.
 *
 * <p>In standalone server mode this record is populated from {@code legate.yml}
 * and bound via {@code @ConfigurationProperties(prefix = "legate")} in the
 * Spring Boot starter. In embedded mode it can be constructed programmatically
 * and passed to the {@code LegateClient} builder.</p>
 *
 * <h3>Minimal configuration (single OpenAI provider)</h3>
 * <pre>{@code
 * legate:
 *   providers:
 *     - name: openai
 *       type: openai
 *       base-url: https://api.openai.com
 *       api-key-env-var: OPENAI_API_KEY
 *       models:
 *         - gpt-4o
 *         - gpt-4o-mini
 *   routing:
 *     aliases:
 *       smart: gpt-4o
 *       fast: gpt-4o-mini
 *     fallback-chains:
 *       default:
 *         endpoints:
 *           - provider: openai
 *   admin:
 *     require-auth: true
 * }</pre>
 *
 * <h3>Validation</h3>
 * <p>Use {@link ConfigValidator#validate(LegateConfig)} to obtain a list of
 * errors and warnings before applying the config. Legate refuses to start (or
 * hot-reload) if any {@link ConfigValidator.Severity#ERROR} findings are returned.</p>
 */
public record LegateConfig(

        /**
         * One or more upstream LLM provider endpoints.
         * At least one provider must be configured.
         */
        List<ProviderConfig> providers,

        /**
         * Alias resolution, fallback chains, conditional routing rules, retry,
         * circuit breaker, and load-balancing settings.
         */
        RoutingConfig routing,

        /** Request and response guard pipeline. */
        GuardConfig guards,

        /** Exact-match response cache. */
        CacheConfig cache,

        /** Per-key and global API rate limiting. */
        RateLimitingConfig rateLimiting,

        /** Cost budget controls per virtual key and globally. */
        SpendControlConfig spendControl,

        /** Log destination and content capture settings. */
        LoggingConfig logging,

        /** Alert rules evaluated against sliding-window metrics. */
        List<AlertConfig> alerts,

        /** Admin API access control and port configuration. */
        AdminConfig admin,

        /**
         * Model pricing table used for cost estimation and cost-optimised
         * load balancing. Models not listed here will not have costs estimated.
         */
        List<ModelPricingConfig> modelPricing

) {
    public LegateConfig {
        if (providers == null) {
            providers = List.of();
        }
        if (routing == null) {
            routing = RoutingConfig.empty();
        }
        if (guards == null) {
            guards = GuardConfig.empty();
        }
        if (cache == null) {
            cache = CacheConfig.defaults();
        }
        if (rateLimiting == null) {
            rateLimiting = RateLimitingConfig.disabled();
        }
        if (spendControl == null) {
            spendControl = SpendControlConfig.disabled();
        }
        if (logging == null) {
            logging = LoggingConfig.defaults();
        }
        if (alerts == null) {
            alerts = List.of();
        }
        if (admin == null) {
            admin = AdminConfig.defaults();
        }
        if (modelPricing == null) {
            modelPricing = List.of();
        }
    }
}
