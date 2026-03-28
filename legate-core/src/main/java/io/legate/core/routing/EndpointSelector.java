package io.legate.core.routing;

import io.legate.core.config.provider.ProviderConfig;
import io.legate.core.config.provider.ProviderType;
import io.legate.core.config.routing.LoadBalancingStrategy;
import io.legate.core.exception.NoEndpointAvailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Selects a {@link ResolvedEndpoint} for a given canonical model name,
 * applying the configured {@link LoadBalancingStrategy} when multiple
 * providers declare the same model.
 *
 * <p>At startup (or on hot-reload), the selector builds an internal map from
 * model ID → {@code List<ResolvedEndpoint>} by iterating over all configured
 * {@link ProviderConfig} entries. When multiple providers serve the same model,
 * the configured load-balancing strategy determines which endpoint is returned.</p>
 *
 * <p>Thread-safe: the endpoint map is published atomically via {@link AtomicReference}.</p>
 *
 * <h3>Strategy behaviour</h3>
 * <ul>
 *   <li>{@code ROUND_ROBIN} — rotates through endpoints in order using an atomic counter.</li>
 *   <li>{@code WEIGHTED} — weighted random selection using {@link ResolvedEndpoint#weight()}.</li>
 *   <li>{@code LEAST_LATENCY} — prefers the endpoint with the lowest EWMA latency
 *       from {@link LatencyTracker}.</li>
 *   <li>{@code COST_OPTIMIZED} — falls back to ROUND_ROBIN (pricing data not available
 *       at this layer without {@code PricingService}).</li>
 * </ul>
 *
 * <h3>Credential resolution</h3>
 * <ul>
 *   <li>{@link ProviderType#OPENAI} / {@link ProviderType#AZURE_OPENAI}: {@code Bearer <key>}</li>
 *   <li>{@link ProviderType#ANTHROPIC}: {@code x-api-key: <key>}</li>
 *   <li>{@link ProviderType#OLLAMA}: no auth ({@link ProviderCredentials.None})</li>
 *   <li>{@link ProviderType#BEDROCK}: AWS SigV4</li>
 *   <li>{@link ProviderType#VERTEXAI}: OAuth2</li>
 * </ul>
 */
public class EndpointSelector {

    private static final Logger log = LoggerFactory.getLogger(EndpointSelector.class);

    private final AtomicReference<Map<String, List<ResolvedEndpoint>>> endpointMapRef;
    private final LoadBalancingStrategy strategy;
    private final LatencyTracker latencyTracker;
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    /**
     * Creates an EndpointSelector with explicit load-balancing configuration.
     *
     * @param providers      the list of configured providers; may be null or empty
     * @param strategy       the load-balancing algorithm to apply when multiple endpoints
     *                       serve the same model
     * @param latencyTracker tracker used by the {@code LEAST_LATENCY} strategy
     */
    public EndpointSelector(
        List<ProviderConfig> providers,
        LoadBalancingStrategy strategy,
        LatencyTracker latencyTracker
    ) {
        this.strategy       = (strategy != null) ? strategy : LoadBalancingStrategy.ROUND_ROBIN;
        this.latencyTracker = (latencyTracker != null) ? latencyTracker : new LatencyTracker();
        this.endpointMapRef = new AtomicReference<>(buildEndpointMap(providers));
    }

    /**
     * Creates an EndpointSelector with round-robin load balancing (backward-compatible
     * convenience constructor).
     *
     * @param providers the list of configured providers; may be null or empty
     */
    public EndpointSelector(List<ProviderConfig> providers) {
        this(providers, LoadBalancingStrategy.ROUND_ROBIN, new LatencyTracker());
    }

    /**
     * Selects an endpoint for the given canonical model name using the configured
     * load-balancing strategy.
     *
     * @param model the canonical model name (already alias-resolved)
     * @return the selected endpoint
     * @throws NoEndpointAvailableException if no provider is configured for the model
     */
    public ResolvedEndpoint select(String model) {
        List<ResolvedEndpoint> candidates = endpointMapRef.get().get(model);
        if (candidates == null || candidates.isEmpty()) {
            throw new NoEndpointAvailableException(model,
                "No provider configuration lists this model. " +
                "Add it under legate.providers[].models or use an alias.");
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return applyStrategy(candidates);
    }

    /**
     * Finds an endpoint for the given model, returning empty if not configured.
     * When multiple endpoints exist, returns the first one (prefer {@link #select}
     * for load-balanced access).
     *
     * @param model the canonical model name
     * @return the first configured endpoint, or empty
     */
    public Optional<ResolvedEndpoint> find(String model) {
        List<ResolvedEndpoint> candidates = endpointMapRef.get().get(model);
        return (candidates == null || candidates.isEmpty())
            ? Optional.empty()
            : Optional.of(candidates.get(0));
    }

    /**
     * Returns true if an endpoint is configured for the given model.
     *
     * @param model the canonical model name
     */
    public boolean hasEndpoint(String model) {
        List<ResolvedEndpoint> candidates = endpointMapRef.get().get(model);
        return candidates != null && !candidates.isEmpty();
    }

    /**
     * Returns all configured model names.
     */
    public Set<String> configuredModels() {
        return endpointMapRef.get().keySet();
    }

    /**
     * Atomically replaces the endpoint map (hot-reload support).
     *
     * @param providers the updated provider list
     */
    public void reload(List<ProviderConfig> providers) {
        endpointMapRef.set(buildEndpointMap(providers));
        log.info("EndpointSelector reloaded: {} model(s) configured",
            endpointMapRef.get().size());
    }

    // -------------------------------------------------------------------------
    // Strategy selection
    // -------------------------------------------------------------------------

    private ResolvedEndpoint applyStrategy(List<ResolvedEndpoint> candidates) {
        return switch (strategy) {
            case ROUND_ROBIN, COST_OPTIMIZED -> {
                // COST_OPTIMIZED falls back to ROUND_ROBIN (PricingService not available here)
                int idx = Math.abs(roundRobinCounter.getAndIncrement()) % candidates.size();
                yield candidates.get(idx);
            }
            case WEIGHTED -> selectWeighted(candidates);
            case LEAST_LATENCY -> selectLeastLatency(candidates);
        };
    }

    private ResolvedEndpoint selectWeighted(List<ResolvedEndpoint> candidates) {
        int totalWeight = candidates.stream().mapToInt(ResolvedEndpoint::weight).sum();
        if (totalWeight <= 0) {
            return candidates.get(0);
        }
        int rand = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;
        for (ResolvedEndpoint ep : candidates) {
            cumulative += Math.max(ep.weight(), 0);
            if (rand < cumulative) {
                return ep;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private ResolvedEndpoint selectLeastLatency(List<ResolvedEndpoint> candidates) {
        ResolvedEndpoint best = candidates.get(0);
        long bestLatency = latencyTracker.getAvgLatencyMs(best);
        for (int i = 1; i < candidates.size(); i++) {
            ResolvedEndpoint ep = candidates.get(i);
            long latency = latencyTracker.getAvgLatencyMs(ep);
            if (latency < bestLatency) {
                bestLatency = latency;
                best = ep;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // Map construction
    // -------------------------------------------------------------------------

    private static Map<String, List<ResolvedEndpoint>> buildEndpointMap(List<ProviderConfig> providers) {
        if (providers == null || providers.isEmpty()) {
            return Map.of();
        }
        Map<String, List<ResolvedEndpoint>> map = new HashMap<>();
        for (ProviderConfig provider : providers) {
            ProviderCredentials credentials = resolveCredentials(provider);
            for (String model : provider.models()) {
                ResolvedEndpoint endpoint = new ResolvedEndpoint(
                    provider.type().adapterName(),
                    model,
                    provider.baseUrl(),
                    credentials,
                    provider.connectTimeout(),
                    provider.readTimeout(),
                    provider.weight()
                );
                map.computeIfAbsent(model, k -> new ArrayList<>()).add(endpoint);
            }
        }
        // Make each list immutable
        Map<String, List<ResolvedEndpoint>> immutable = new HashMap<>();
        map.forEach((model, list) -> immutable.put(model, List.copyOf(list)));
        log.debug("Built endpoint map with {} model(s): {}", immutable.size(), immutable.keySet());
        return Map.copyOf(immutable);
    }

    /**
     * Builds the appropriate {@link ProviderCredentials} variant for the given provider config.
     */
    static ProviderCredentials resolveCredentials(ProviderConfig provider) {
        return switch (provider.type()) {

            case OLLAMA -> ProviderCredentials.None.INSTANCE;

            case ANTHROPIC -> {
                String apiKey = provider.resolveApiKey();
                if (apiKey != null && !apiKey.isBlank()) {
                    yield new ProviderCredentials.ApiKeyHeader("x-api-key", apiKey);
                }
                log.warn("Anthropic provider '{}' has no API key configured (env var: {}).",
                    provider.name(), provider.apiKeyEnvVar());
                yield ProviderCredentials.None.INSTANCE;
            }

            case OPENAI, AZURE_OPENAI -> {
                String apiKey = provider.resolveApiKey();
                if (apiKey != null && !apiKey.isBlank()) {
                    yield new ProviderCredentials.BearerToken(apiKey);
                }
                log.warn("Provider '{}' ({}) has no API key configured (env var: {}).",
                    provider.name(), provider.type(), provider.apiKeyEnvVar());
                yield ProviderCredentials.None.INSTANCE;
            }

            case BEDROCK -> {
                String accessKeyId = provider.resolvePropertyEnvVar("aws-access-key-id-env-var");
                String secretKey   = provider.resolvePropertyEnvVar("aws-secret-access-key-env-var");
                String region      = provider.properties().get("region");
                if (accessKeyId != null && secretKey != null && region != null) {
                    yield new ProviderCredentials.AwsSigV4(accessKeyId, secretKey, region, "bedrock");
                }
                log.warn("Bedrock provider '{}' is missing AWS credentials or region.", provider.name());
                yield ProviderCredentials.None.INSTANCE;
            }

            case VERTEXAI -> {
                String oauthToken = provider.resolvePropertyEnvVar("service-account-key-env-var");
                if (oauthToken != null && !oauthToken.isBlank()) {
                    yield new ProviderCredentials.OAuth2(oauthToken);
                }
                log.warn("VertexAI provider '{}' has no service-account-key-env-var configured.",
                    provider.name());
                yield ProviderCredentials.None.INSTANCE;
            }
        };
    }
}
