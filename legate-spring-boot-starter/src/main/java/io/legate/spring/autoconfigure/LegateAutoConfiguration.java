package io.legate.spring.autoconfigure;

import tools.jackson.databind.ObjectMapper;
import io.legate.core.audit.AuditLogger;
import io.legate.core.audit.NoOpAuditLogger;
import io.legate.core.cache.CaffeineResponseCache;
import io.legate.core.cache.ResponseCache;
import io.legate.core.config.LegateConfig;
import io.legate.core.event.AsyncEventBus;
import io.legate.core.event.EventBus;
import io.legate.core.key.FileBasedVirtualKeyStore;
import io.legate.core.key.VirtualKeyHasher;
import io.legate.core.key.VirtualKeyStore;
import io.legate.core.meter.CostCalculator;
import io.legate.core.meter.PricingService;
import io.legate.core.meter.SpendTracker;
import io.legate.core.provider.ProviderAdapterRegistry;
import io.legate.core.ratelimit.Resilience4jRateLimiter;
import io.legate.core.ratelimit.RateLimiter;
import io.legate.core.routing.AccessController;
import io.legate.core.routing.RoutingEngine;
import io.legate.provider.anthropic.AnthropicProviderAdapter;
import io.legate.provider.openai.OpenAiProviderAdapter;
import io.legate.spring.properties.LegateProperties;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Legate core components.
 *
 * <p>All beans are guarded by {@code @ConditionalOnMissingBean} so that application
 * code (or the standalone {@code legate-server}) can override any bean.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(LegateProperties.class)
public class LegateAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LegateConfig legateConfig(LegateProperties legateProperties) {
        return legateProperties.toLegateConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public RoutingEngine legateRoutingEngine(LegateConfig legateConfig, EventBus eventBus) {
        return new RoutingEngine(legateConfig, eventBus);
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper legateObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventBus legateEventBus() {
        return new AsyncEventBus();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderAdapterRegistry legateProviderAdapterRegistry(ObjectMapper objectMapper) {
        ProviderAdapterRegistry registry = new ProviderAdapterRegistry();
        registry.register(new OpenAiProviderAdapter(objectMapper));
        registry.register(new AnthropicProviderAdapter(objectMapper));
        return registry;
    }

    /** BCrypt hasher for virtual key storage — more secure than the SHA-256 default. */
    @Bean
    @ConditionalOnMissingBean
    public VirtualKeyHasher legateVirtualKeyHasher() {
        return new BcryptVirtualKeyHasher();
    }

    @Bean
    @ConditionalOnMissingBean
    public VirtualKeyStore legateVirtualKeyStore(VirtualKeyHasher virtualKeyHasher) {
        return new FileBasedVirtualKeyStore(java.nio.file.Path.of("legate-keys.yml"), virtualKeyHasher);
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimiter legateRateLimiter(LegateConfig legateConfig) {
        return new Resilience4jRateLimiter(legateConfig.rateLimiting());
    }

    // -------------------------------------------------------------------------
    // Phase 3 beans
    // -------------------------------------------------------------------------

    /** Default in-memory response cache. Override with a custom bean for Redis. */
    @Bean
    @ConditionalOnMissingBean
    public ResponseCache legateResponseCache(LegateConfig legateConfig) {
        return new CaffeineResponseCache(legateConfig.cache());
    }

    /**
     * No-op audit log — discards events and warns once on first use.
     * Override by configuring {@code legate.store.type=postgres} to activate
     * {@code PostgresAuditLogger} via {@code PostgresAutoConfiguration}.
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditLogger legateAuditLog() {
        return new NoOpAuditLogger();
    }

    /** Pricing service backed by the model-pricing config table. */
    @Bean
    @ConditionalOnMissingBean
    public PricingService legatePricingService(LegateConfig legateConfig) {
        return new PricingService(legateConfig.modelPricing());
    }

    /** Cost calculator that uses the pricing service. */
    @Bean
    @ConditionalOnMissingBean
    public CostCalculator legateCostCalculator(PricingService pricingService) {
        return new CostCalculator(pricingService);
    }

    /** In-memory spend tracker. Override with PostgresSpendTracker in Phase 4. */
    @Bean
    @ConditionalOnMissingBean
    public SpendTracker legateSpendTracker(LegateConfig legateConfig) {
        return new SpendTracker(legateConfig.spendControl());
    }

    /** Model access controller for virtual key allow/deny checks. */
    @Bean
    @ConditionalOnMissingBean
    public AccessController legateAccessController() {
        return new AccessController();
    }

    /** No-op tracer — used when no tracing backend (OTel, Brave) is configured. */
    @Bean
    @ConditionalOnMissingBean
    public Tracer legateTracer() {
        return Tracer.NOOP;
    }
}
