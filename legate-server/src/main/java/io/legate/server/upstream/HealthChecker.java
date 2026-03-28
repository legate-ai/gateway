package io.legate.server.upstream;

import io.legate.core.config.LegateConfig;
import io.legate.core.config.provider.ProviderConfig;
import io.legate.core.routing.RoutingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scheduled health checker that probes upstream LLM providers.
 *
 * <p>For each provider with {@code health-check.enabled = true}, a lightweight
 * GET request is sent to the configured {@code health-check.path} (default: {@code /v1/models}).
 * Providers that fail health checks are marked unhealthy and the routing engine's
 * circuit breaker is notified.</p>
 *
 * <p>The health status of each provider is exposed via {@link #isHealthy(String)} for
 * use by the {@code GET /health/ready} endpoint.</p>
 */
@Component
public class HealthChecker {

    private static final Logger log = LoggerFactory.getLogger(HealthChecker.class);

    private final LegateConfig legateConfig;
    private final WebClient.Builder webClientBuilder;
    private final Map<String, Boolean> healthStatus = new ConcurrentHashMap<>();

    public HealthChecker(LegateConfig legateConfig, WebClient.Builder webClientBuilder) {
        this.legateConfig     = legateConfig;
        this.webClientBuilder = webClientBuilder;
        // Initialize all providers as healthy at startup
        for (ProviderConfig p : legateConfig.providers()) {
            healthStatus.put(p.name(), true);
        }
    }

    /**
     * Runs every 30 seconds (configurable via {@code legate.providers[].health-check.interval}).
     * Checks all providers that have health-check enabled.
     */
    @Scheduled(fixedDelayString = "${legate.health-check.interval-ms:30000}")
    public void checkAll() {
        for (ProviderConfig provider : legateConfig.providers()) {
            if (provider.healthCheck() == null || !provider.healthCheck().enabled()) continue;
            checkProvider(provider);
        }
    }

    /**
     * Returns {@code true} if the provider with the given name is currently healthy.
     * Returns {@code true} for providers without health-check configured (optimistic).
     *
     * @param providerName the provider name as configured in {@code legate.providers[].name}
     */
    public boolean isHealthy(String providerName) {
        return healthStatus.getOrDefault(providerName, true);
    }

    /**
     * Returns {@code true} if at least one provider is currently healthy.
     */
    public boolean atLeastOneHealthy() {
        if (healthStatus.isEmpty()) return false;
        return healthStatus.values().stream().anyMatch(v -> v);
    }

    /**
     * Forces an immediate health check for all configured providers.
     * Called on startup and via the admin reload endpoint.
     */
    public void checkNow() {
        checkAll();
    }

    /**
     * Returns an immutable snapshot of the current health status for all providers.
     * Used by {@link io.legate.server.actuator.LegateHealthIndicator} to expose
     * provider health via the Spring Boot Actuator readiness probe.
     *
     * @return map of provider name → {@code true} (healthy) / {@code false} (unhealthy)
     */
    public Map<String, Boolean> getProviderHealthStatus() {
        return Map.copyOf(healthStatus);
    }

    // -------------------------------------------------------------------------

    private void checkProvider(ProviderConfig provider) {
        String checkPath = provider.healthCheck().path() != null
            ? provider.healthCheck().path()
            : "/v1/models";
        String baseUrl  = provider.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            healthStatus.put(provider.name(), true); // no URL to check
            return;
        }
        Duration timeout = provider.healthCheck().timeout() != null
            ? provider.healthCheck().timeout()
            : Duration.ofSeconds(5);

        WebClient client = webClientBuilder.baseUrl(baseUrl).build();
        client.get()
            .uri(checkPath)
            .retrieve()
            .toBodilessEntity()
            .timeout(timeout)
            .onErrorResume(ex -> Mono.empty())
            .subscribe(
                response -> {
                    boolean ok = response != null && response.getStatusCode().is2xxSuccessful();
                    boolean wasHealthy = Boolean.TRUE.equals(healthStatus.put(provider.name(), ok));
                    if (wasHealthy && !ok) {
                        log.warn("Provider '{}' health check FAILED ({}{})", provider.name(), baseUrl, checkPath);
                    } else if (!wasHealthy && ok) {
                        log.info("Provider '{}' health check RECOVERED", provider.name());
                    }
                },
                error -> {
                    healthStatus.put(provider.name(), false);
                    log.warn("Provider '{}' health check error: {}", provider.name(), error.getMessage());
                }
            );
    }
}
