package io.legate.core.routing;

import io.legate.core.config.LegateConfig;
import io.legate.core.config.provider.ProviderConfig;
import io.legate.core.config.routing.FallbackChainConfig;
import io.legate.core.config.routing.LoadBalancingStrategy;
import io.legate.core.config.routing.RoutingConfig;
import io.legate.core.context.VirtualKeyInfo;
import io.legate.core.event.EventBus;
import io.legate.core.exception.NoEndpointAvailableException;
import io.legate.core.model.ChatCompletionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the Phase-2 routing pipeline:
 * <ol>
 *   <li>Alias resolution — converts short names to canonical model IDs.</li>
 *   <li>Fallback chain resolution — resolves the ordered list of endpoints to try.</li>
 *   <li>Circuit-breaker check — skips endpoints whose circuit is OPEN.</li>
 *   <li>Returns the first available endpoint as a {@link RoutingDecision}.</li>
 * </ol>
 *
 * <p>For retry/fallback support, use {@link #resolveChain(String)} which returns
 * the full {@link FallbackChain} for the handler to iterate on upstream failures.</p>
 *
 * <p>Thread-safe: all internal state is published through {@link AtomicReference}
 * so concurrent requests always observe a consistent snapshot even during hot-reload.</p>
 */
public class RoutingEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingEngine.class);

    private final AtomicReference<AliasResolver> aliasResolverRef;
    private final AtomicReference<EndpointSelector> endpointSelectorRef;
    private final AtomicReference<Map<String, FallbackChain>> chainsRef;
    private final AtomicReference<String> defaultChainNameRef;
    private final AtomicReference<Map<String, ProviderConfig>> providerMapRef;
    private final AtomicReference<RouteRuleMatcher> ruleMatcherRef;
    private final AtomicReference<Map<String, String>> modelChainIndexRef;
    private final CircuitBreakerRegistry cbRegistry;
    private final LatencyTracker latencyTracker;

    /**
     * Creates a RoutingEngine from the given Legate configuration.
     *
     * @param config the current Legate configuration; must not be null
     */
    public RoutingEngine(LegateConfig config) {
        this(config, null);
    }

    /**
     * Creates a RoutingEngine from the given configuration and event bus.
     *
     * @param config   the current Legate configuration
     * @param eventBus for circuit-breaker state-change events; may be null
     */
    public RoutingEngine(LegateConfig config, EventBus eventBus) {
        Map<String, ProviderConfig> providerMap = buildProviderMap(config);
        this.providerMapRef = new AtomicReference<>(providerMap);
        this.aliasResolverRef = new AtomicReference<>(buildAliasResolver(config));
        this.latencyTracker = new LatencyTracker();
        LoadBalancingStrategy lbStrategy = config.routing().loadBalancer().strategy();
        this.endpointSelectorRef = new AtomicReference<>(
            new EndpointSelector(config.providers(), lbStrategy, this.latencyTracker));
        this.cbRegistry = new CircuitBreakerRegistry(config.routing().circuitBreaker(), eventBus);
        Map<String, FallbackChain> initialChains = buildChains(config, providerMap);
        this.chainsRef = new AtomicReference<>(initialChains);
        this.modelChainIndexRef = new AtomicReference<>(buildModelChainIndex(initialChains));
        this.defaultChainNameRef = new AtomicReference<>(config.routing().defaultChain());
        this.ruleMatcherRef = new AtomicReference<>(buildRuleMatcher(config));

        log.info("RoutingEngine initialised: {} provider(s), {} model(s), {} chain(s), default-chain='{}'",
                config.providers().size(),
                endpointSelectorRef.get().configuredModels().size(),
                chainsRef.get().size(),
                defaultChainNameRef.get());
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Routes a model name (or alias) to a {@link RoutingDecision}.
     *
     * <p>Returns the first endpoint from the resolved {@link FallbackChain} that
     * is permitted by the circuit breaker. Falls back to direct model lookup if no
     * chain is configured.</p>
     *
     * @param modelOrAlias the model name as supplied by the client (may be an alias)
     * @return the routing decision containing the selected endpoint
     * @throws NoEndpointAvailableException if no available endpoint exists
     * @throws IllegalArgumentException     if {@code modelOrAlias} is null or blank
     */
    public RoutingDecision route(String modelOrAlias) {
        FallbackChain chain = resolveChain(modelOrAlias);
        Optional<FallbackChain.IndexedEndpoint> candidate = chain.getNextAvailable(0, cbRegistry);

        if (candidate.isPresent()) {
            ResolvedEndpoint endpoint = candidate.get().endpoint();
            String reason = "chain:" + chain.name() + ",attempt:0";
            log.debug("Routed '{}' → provider='{}', model='{}', chain='{}'",
                    modelOrAlias, endpoint.providerName(), endpoint.modelName(), chain.name());
            return RoutingDecision.primary(endpoint, reason);
        }

        throw new NoEndpointAvailableException(modelOrAlias,
                "All endpoints in chain '" + chain.name() + "' are blocked by circuit breakers. " +
                        "Chain has " + chain.size() + " endpoint(s).");
    }

    /**
     * Resolves the fallback chain for the given model or alias.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>The {@code default-chain} name from YAML (default: {@code "default"}).</li>
     *   <li>A synthetic single-endpoint chain from direct model lookup.</li>
     * </ol>
     *
     * <p>The handler should use this to drive retry logic:</p>
     * <pre>{@code
     * FallbackChain chain = routingEngine.resolveChain(model);
     * chain.getNextAvailable(attempt, cbRegistry).ifPresent(ie -> {
     *     ResolvedEndpoint ep = ie.endpoint();
     *     // call upstream, on failure:  cbRegistry.recordFailure(ep);  attempt++;
     * });
     * }</pre>
     *
     * @param modelOrAlias the model name or alias
     * @return the resolved chain; never null
     */
    public FallbackChain resolveChain(String modelOrAlias) {
        // 1. Resolve alias to canonical model name first
        String canonicalModel = aliasResolverRef.get().resolve(modelOrAlias);
        Map<String, FallbackChain> chains = chainsRef.get();

        // 2. Look for a chain that explicitly serves this canonical model
        String matchedChainName = modelChainIndexRef.get().get(canonicalModel);
        if (matchedChainName != null) {
            FallbackChain chain = chains.get(matchedChainName);
            if (chain != null && !chain.isEmpty()) {
                log.debug("Resolved '{}' → canonical='{}' → chain '{}'",
                        modelOrAlias, canonicalModel, matchedChainName);
                return chain;
            }
        }

        // 3. Fall back to configured default chain
        String defaultChainName = defaultChainNameRef.get();
        FallbackChain defaultChain = chains.get(defaultChainName);
        if (defaultChain != null && !defaultChain.isEmpty()) {
            log.debug("Resolved '{}' → canonical='{}' → default chain '{}'",
                    modelOrAlias, canonicalModel, defaultChainName);
            return defaultChain;
        }

        // 4. Synthetic single-endpoint chain from direct model lookup
        Optional<ResolvedEndpoint> ep = endpointSelectorRef.get().find(canonicalModel);
        if (ep.isPresent()) {
            return FallbackChain.single("direct:" + canonicalModel, ep.get());
        }

        // 5. Nothing found
        return FallbackChain.empty("none:" + modelOrAlias);
    }

    /**
     * Returns the {@link CircuitBreakerRegistry} for recording success/failure
     * outcomes after upstream calls.
     */
    public CircuitBreakerRegistry getCircuitBreakerRegistry() {
        return cbRegistry;
    }

    /**
     * Returns the {@link LatencyTracker} used by the {@code LEAST_LATENCY} strategy.
     * Callers should invoke {@link LatencyTracker#record(ResolvedEndpoint, long)} after
     * each successful upstream call to keep the tracker up to date.
     */
    public LatencyTracker getLatencyTracker() {
        return latencyTracker;
    }

    /**
     * Evaluates conditional routing rules against the request and returns the first match.
     *
     * <p>Rules are evaluated after alias resolution but before access control and endpoint selection.
     * A matching rule may override the target model or select a specific fallback chain.</p>
     *
     * @param request the effective chat completion request
     * @param keyInfo authenticated virtual key info, or {@code null}
     * @param headers lowercase request headers map
     * @return the matched rule's target, or empty if no rule matches
     */
    public Optional<RouteRuleMatcher.RuleMatch> matchRule(
            ChatCompletionRequest request,
            VirtualKeyInfo keyInfo,
            Map<String, String> headers
    ) {
        return ruleMatcherRef.get().match(request, keyInfo, headers);
    }

    /**
     * Atomically reloads routing configuration (hot-reload support).
     *
     * @param newConfig the updated Legate configuration
     */
    public void reload(LegateConfig newConfig) {
        Map<String, ProviderConfig> providerMap = buildProviderMap(newConfig);
        providerMapRef.set(providerMap);
        aliasResolverRef.set(buildAliasResolver(newConfig));
        // Replace EndpointSelector to pick up new strategy/providers from the updated config
        LoadBalancingStrategy lbStrategy = newConfig.routing().loadBalancer().strategy();
        endpointSelectorRef.set(new EndpointSelector(newConfig.providers(), lbStrategy, latencyTracker));
        Map<String, FallbackChain> reloadedChains = buildChains(newConfig, providerMap);
        chainsRef.set(reloadedChains);
        modelChainIndexRef.set(buildModelChainIndex(reloadedChains));
        defaultChainNameRef.set(newConfig.routing().defaultChain());
        ruleMatcherRef.set(buildRuleMatcher(newConfig));
        log.info("RoutingEngine reloaded: {} provider(s), {} model(s), {} chain(s), default-chain='{}'",
                newConfig.providers().size(),
                endpointSelectorRef.get().configuredModels().size(),
                chainsRef.get().size(),
                defaultChainNameRef.get());
    }

    /**
     * Resolves an alias to its canonical model name.
     *
     * <p>Used by the handler to perform access-control checks against the resolved model
     * before routing. If no alias mapping exists, the input is returned unchanged.</p>
     *
     * @param modelOrAlias a raw model name or configured alias
     * @return the canonical model name
     */
    public String resolveAlias(String modelOrAlias) {
        return aliasResolverRef.get().resolve(modelOrAlias);
    }

    /**
     * Returns the named fallback chain, or the default chain if the name is not found.
     *
     * <p>Used by the handler when a routing rule specifies a {@code targetChain}.</p>
     *
     * @param chainName the chain name as configured in YAML
     * @return the named chain, or the default chain, or an empty chain if none exist
     */
    public FallbackChain resolveChainByName(String chainName) {
        FallbackChain chain = chainsRef.get().get(chainName);
        if (chain != null && !chain.isEmpty()) {
            return chain;
        }
        log.warn("RoutingEngine: chain '{}' not found — falling back to default chain", chainName);
        return resolveChain(chainName); // uses default chain resolution
    }

    /**
     * Returns the set of model names that have a configured endpoint.
     * Useful for the {@code GET /v1/models} response.
     */
    public Set<String> configuredModels() {
        return endpointSelectorRef.get().configuredModels();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static RouteRuleMatcher buildRuleMatcher(LegateConfig config) {
        RoutingConfig routing = config.routing();
        return (routing != null && routing.rules() != null)
                ? new RouteRuleMatcher(routing.rules())
                : new RouteRuleMatcher();
    }

    private static AliasResolver buildAliasResolver(LegateConfig config) {
        RoutingConfig routing = config.routing();
        return (routing != null && routing.aliases() != null)
                ? new AliasResolver(routing.aliases())
                : new AliasResolver();
    }

    private static Map<String, ProviderConfig> buildProviderMap(LegateConfig config) {
        Map<String, ProviderConfig> providerConfigMap = new HashMap<>();
        if (config.providers() != null) {
            for (ProviderConfig providerConfig : config.providers()) {
                providerConfigMap.put(providerConfig.name(), providerConfig);
            }
        }
        return Map.copyOf(providerConfigMap);
    }

    private static Map<String, FallbackChain> buildChains(
            LegateConfig config,
            Map<String, ProviderConfig> providerMap
    ) {
        RoutingConfig routing = config.routing();
        if (routing == null || routing.fallbackChains().isEmpty()) {
            return Map.of();
        }

        Map<String, FallbackChain> chains = new HashMap<>();
        for (Map.Entry<String, FallbackChainConfig> entry : routing.fallbackChains().entrySet()) {
            chains.put(entry.getKey(),
                    FallbackChain.from(entry.getKey(), entry.getValue(), providerMap));
        }
        return Map.copyOf(chains);
    }

    /**
     * Builds an index of model name → chain name for O(1) model-to-chain lookup.
     * When the same model appears in multiple chains, the first chain encountered wins.
     */
    private static Map<String, String> buildModelChainIndex(Map<String, FallbackChain> chains) {
        Map<String, String> index = new HashMap<>();
        for (Map.Entry<String, FallbackChain> entry : chains.entrySet()) {
            for (ResolvedEndpoint ep : entry.getValue().endpoints()) {
                index.putIfAbsent(ep.modelName(), entry.getKey());
            }
        }
        return Map.copyOf(index);
    }
}
