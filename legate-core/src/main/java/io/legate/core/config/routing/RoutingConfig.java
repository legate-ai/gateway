package io.legate.core.config.routing;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Routing configuration controlling how incoming requests are mapped to provider endpoints.
 *
 * <h3>Evaluation order</h3>
 * <ol>
 *   <li><b>Alias resolution</b> — {@link #aliases()} maps short names to real model IDs.</li>
 *   <li><b>Rule matching</b> — {@link #rules()} are tried in order; first match wins.</li>
 *   <li><b>Chain selection</b> — the matched rule's chain, or {@link #defaultChain()} as fallback.</li>
 *   <li><b>Endpoint selection</b> — the {@link #loadBalancer()} picks from eligible chain endpoints.</li>
 *   <li><b>Fallback iteration</b> — on failure, the {@link #retry()} policy drives the next attempt.</li>
 * </ol>
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   routing:
 *     aliases:
 *       smart: gpt-4o
 *       fast: gpt-4o-mini
 *       claude: claude-3-5-sonnet-20241022
 *     default-chain: default
 *     fallback-chains:
 *       default:
 *         endpoints:
 *           - provider: openai-prod
 *             model: gpt-4o
 *           - provider: anthropic-prod
 *             model: claude-3-5-sonnet-20241022
 *       cheap:
 *         endpoints:
 *           - provider: openai-prod
 *             model: gpt-4o-mini
 *     rules:
 *       - name: short-prompts-use-cheap-chain
 *         conditions:
 *           max-input-tokens: "500"
 *         target-chain: cheap
 *     retry:
 *       max-attempts: 3
 *       backoff: exponential
 *     circuit-breaker:
 *       failure-threshold: 5
 *     load-balancer:
 *       strategy: least-latency
 * }</pre>
 */
public record RoutingConfig(

    /**
     * Model alias map. Clients can use short names (e.g., {@code "smart"}) that
     * are transparently resolved to full model IDs before routing.
     */
    Map<String, String> aliases,

    /**
     * Name of the fallback chain to use when no routing rule matches.
     * Must match a key in {@link #fallbackChains()}.
     * Default: {@code "default"}.
     */
    String defaultChain,

    /**
     * Named fallback chains. The key {@code "default"} is used when
     * {@link #defaultChain()} is not set or no other chain is selected.
     */
    Map<String, FallbackChainConfig> fallbackChains,

    /**
     * Conditional routing rules evaluated after alias resolution.
     * Rules are tried in declaration order; first match wins.
     */
    List<RouteRuleConfig> rules,

    /** Retry policy applied on upstream failure. */
    RetryConfig retry,

    /** Circuit-breaker settings applied independently per endpoint. */
    CircuitBreakerConfig circuitBreaker,

    /** Load-balancing strategy for distributing requests across eligible endpoints. */
    LoadBalancerConfig loadBalancer

) {
    public RoutingConfig {
        if (aliases == null)  {
            aliases = Map.of();
        }
        if (defaultChain == null || defaultChain.isBlank()) {
            defaultChain = "default";
        }
        if (fallbackChains == null) {
            fallbackChains = Map.of();
        }
        if (rules == null) {
            rules = List.of();
        }
        if (retry == null) {
            retry = RetryConfig.defaults();
        }
        if (circuitBreaker == null) {
            circuitBreaker = CircuitBreakerConfig.defaults();
        }
        if (loadBalancer == null) {
            loadBalancer = LoadBalancerConfig.defaults();
        }
    }

    /** Returns a minimal routing config with no aliases, chains, or rules. */
    public static RoutingConfig empty() {
        return new RoutingConfig(null, null, null, null, null, null, null);
    }

    /**
     * Resolves a model alias to its canonical model name.
     *
     * @param modelOrAlias the raw model name from the client request
     * @return the resolved model name, or the original value if no alias exists
     */
    public String resolveAlias(String modelOrAlias) {
        return aliases.getOrDefault(modelOrAlias, modelOrAlias);
    }

    /**
     * Returns the chain configured under {@link #defaultChain()}, if present.
     *
     * @return the default chain, or empty if not configured
     */
    public Optional<FallbackChainConfig> resolveDefaultChain() {
        return Optional.ofNullable(fallbackChains.get(defaultChain));
    }
}
