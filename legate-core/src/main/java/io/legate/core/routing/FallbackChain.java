package io.legate.core.routing;

import io.legate.core.config.provider.ProviderConfig;
import io.legate.core.config.routing.ChainEndpointConfig;
import io.legate.core.config.routing.FallbackChainConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A resolved fallback chain — an ordered list of {@link ResolvedEndpoint} objects
 * built from a {@link FallbackChainConfig} and the provider registry.
 *
 * <p>The first entry is the primary endpoint; subsequent entries are tried in order
 * when the preceding entry fails or its circuit breaker is open.</p>
 *
 * <p>YAML example for the {@code default} chain:</p>
 * <pre>{@code
 * legate:
 *   routing:
 *     fallback-chains:
 *       default:
 *         endpoints:
 *           - provider: openai-prod
 *             model: gpt-4o
 *           - provider: anthropic-prod
 *             model: claude-3-5-sonnet-20241022
 * }</pre>
 */
public class FallbackChain {

    private static final Logger log = LoggerFactory.getLogger(FallbackChain.class);

    private final String name;
    private final List<ResolvedEndpoint> endpoints;

    private FallbackChain(String name, List<ResolvedEndpoint> endpoints) {
        this.name      = name;
        this.endpoints = List.copyOf(endpoints);
    }

    /**
     * Builds a {@link FallbackChain} from a config entry and provider map.
     *
     * @param name           the chain name (map key from YAML)
     * @param chainConfig    the chain configuration
     * @param providersByName map of provider name → {@link ProviderConfig}
     * @return the resolved chain; endpoints for unknown providers are skipped with a warning
     */
    public static FallbackChain from(
        String name,
        FallbackChainConfig chainConfig,
        Map<String, ProviderConfig> providersByName
    ) {
        List<ResolvedEndpoint> resolved = new ArrayList<>();
        for (ChainEndpointConfig entry : chainConfig.endpoints()) {
            ProviderConfig provider = providersByName.get(entry.provider());
            if (provider == null) {
                log.warn("FallbackChain '{}': provider '{}' not found — skipping entry.",
                    name, entry.provider());
                continue;
            }
            String model = (entry.model() != null && !entry.model().isBlank())
                ? entry.model()
                : (provider.models().isEmpty() ? null : provider.models().get(0));
            if (model == null) {
                log.warn("FallbackChain '{}': provider '{}' has no model — skipping entry.",
                    name, entry.provider());
                continue;
            }
            ProviderCredentials credentials = EndpointSelector.resolveCredentials(provider);
            ResolvedEndpoint endpoint = new ResolvedEndpoint(
                provider.type().adapterName(),
                model,
                provider.baseUrl(),
                credentials,
                provider.connectTimeout(),
                provider.readTimeout(),
                entry.weight() > 0 ? entry.weight() : provider.weight()
            );
            resolved.add(endpoint);
        }
        log.debug("FallbackChain '{}' resolved: {} endpoint(s)", name, resolved.size());
        return new FallbackChain(name, resolved);
    }

    /**
     * Returns the chain name.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the ordered list of resolved endpoints.
     */
    public List<ResolvedEndpoint> endpoints() {
        return endpoints;
    }

    /**
     * Returns the endpoint at {@code index}, or empty if the index is out of range.
     */
    public Optional<ResolvedEndpoint> get(int index) {
        if (index < 0 || index >= endpoints.size()) return Optional.empty();
        return Optional.of(endpoints.get(index));
    }

    /**
     * Returns the first endpoint whose circuit breaker is not OPEN, starting from
     * {@code startIndex}.
     *
     * @param startIndex     0-based index to start searching from
     * @param cbRegistry     circuit breaker registry; may be null (all endpoints returned as-is)
     * @return the first available endpoint and its index, or empty if all are blocked
     */
    public Optional<IndexedEndpoint> getNextAvailable(
        int startIndex,
        CircuitBreakerRegistry cbRegistry
    ) {
        for (int i = startIndex; i < endpoints.size(); i++) {
            ResolvedEndpoint ep = endpoints.get(i);
            if (cbRegistry == null || cbRegistry.isCallPermitted(ep)) {
                return Optional.of(new IndexedEndpoint(i, ep));
            }
            log.debug("FallbackChain '{}': endpoint[{}] '{}' blocked by circuit breaker — skipping.",
                name, i, ep.getKey());
        }
        return Optional.empty();
    }

    /**
     * Returns the total number of endpoints in this chain.
     */
    public int size() {
        return endpoints.size();
    }

    /**
     * Returns {@code true} if this chain has no endpoints.
     */
    public boolean isEmpty() {
        return endpoints.isEmpty();
    }

    /**
     * Returns a chain containing exactly one endpoint (used when no fallback chain
     * is configured but a direct model lookup succeeds).
     *
     * @param endpoint the single endpoint
     * @return a one-entry chain
     */
    public static FallbackChain single(String name, ResolvedEndpoint endpoint) {
        return new FallbackChain(name, List.of(endpoint));
    }

    /**
     * Returns an empty chain (no endpoints). Using this chain will always result in
     * {@code NoEndpointAvailableException} in the handler.
     *
     * @param name descriptive name for logging
     */
    public static FallbackChain empty(String name) {
        return new FallbackChain(name, List.of());
    }

    /**
     * Pairs an endpoint with its zero-based index in the chain.
     *
     * @param index    position in the chain
     * @param endpoint the resolved endpoint
     */
    public record IndexedEndpoint(int index, ResolvedEndpoint endpoint) {}
}
