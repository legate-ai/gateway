package io.legate.spring.properties;

import io.legate.core.config.LegateConfig;
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
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Spring Boot {@code @ConfigurationProperties} binding for the {@code legate.*} namespace.
 *
 * <p>This class lives in the Spring Boot starter (which is allowed to depend on Spring) and
 * acts as a thin bridge between Spring's YAML binding mechanism and the pure-Java
 * {@link LegateConfig} record in {@code legate-core}. After binding, call
 * {@link #toLegateConfig()} to obtain the immutable domain object.</p>
 *
 * <h3>Minimal YAML example</h3>
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
 *       fast:  gpt-4o-mini
 *   admin:
 *     require-auth: false
 * }</pre>
 *
 * <h3>How Spring Boot binds nested records</h3>
 * <p>All nested types ({@link ProviderConfig}, {@link RoutingConfig}, etc.) are Java records.
 * Spring Boot 3.0+ uses implicit constructor binding for records — it calls the record's
 * canonical constructor with values resolved from YAML. Unspecified fields default to
 * {@code null} (or {@code 0} for primitives), and the record's own compact constructor
 * fills in business defaults (timeouts, weights, etc.).</p>
 *
 * <h3>Enum binding</h3>
 * <p>Spring Boot relaxed binding converts YAML values to enum constants
 * case-insensitively and also handles kebab-case:
 * {@code openai} → {@code ProviderType.OPENAI},
 * {@code azure-openai} → {@code ProviderType.AZURE_OPENAI}.</p>
 */
@ConfigurationProperties(prefix = "legate")
public class LegateProperties {

    private List<ProviderConfig> providers;
    private RoutingConfig routing;
    private GuardConfig guards;
    private CacheConfig cache;
    private RateLimitingConfig rateLimiting;
    private SpendControlConfig spendControl;
    private LoggingConfig logging;
    private List<AlertConfig> alerts;
    private AdminConfig admin;
    private List<ModelPricingConfig> modelPricing;

    // -------------------------------------------------------------------------
    // Conversion
    // -------------------------------------------------------------------------

    /**
     * Converts this mutable properties object to the immutable {@link LegateConfig} domain record.
     *
     * <p>The {@link LegateConfig} compact constructor applies defaults for any field
     * that was not set in YAML.</p>
     *
     * @return an immutable {@link LegateConfig} representing the current property values
     */
    public LegateConfig toLegateConfig() {
        return new LegateConfig(
            providers,
            routing,
            guards,
            cache,
            rateLimiting,
            spendControl,
            logging,
            alerts,
            admin,
            modelPricing
        );
    }

    // -------------------------------------------------------------------------
    // Getters and setters (required for Spring Boot JavaBean binding)
    // -------------------------------------------------------------------------

    public List<ProviderConfig> getProviders() { return providers; }
    public void setProviders(List<ProviderConfig> providers) { this.providers = providers; }

    public RoutingConfig getRouting() { return routing; }
    public void setRouting(RoutingConfig routing) { this.routing = routing; }

    public GuardConfig getGuards() { return guards; }
    public void setGuards(GuardConfig guards) { this.guards = guards; }

    public CacheConfig getCache() { return cache; }
    public void setCache(CacheConfig cache) { this.cache = cache; }

    public RateLimitingConfig getRateLimiting() { return rateLimiting; }
    public void setRateLimiting(RateLimitingConfig rateLimiting) { this.rateLimiting = rateLimiting; }

    public SpendControlConfig getSpendControl() { return spendControl; }
    public void setSpendControl(SpendControlConfig spendControl) { this.spendControl = spendControl; }

    public LoggingConfig getLogging() { return logging; }
    public void setLogging(LoggingConfig logging) { this.logging = logging; }

    public List<AlertConfig> getAlerts() { return alerts; }
    public void setAlerts(List<AlertConfig> alerts) { this.alerts = alerts; }

    public AdminConfig getAdmin() { return admin; }
    public void setAdmin(AdminConfig admin) { this.admin = admin; }

    public List<ModelPricingConfig> getModelPricing() { return modelPricing; }
    public void setModelPricing(List<ModelPricingConfig> modelPricing) { this.modelPricing = modelPricing; }
}
