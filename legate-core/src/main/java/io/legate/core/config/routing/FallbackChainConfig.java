package io.legate.core.config.routing;

import java.util.List;

/**
 * An ordered list of provider endpoints that Legate tries in sequence when
 * routing a request.
 *
 * <p>The chain name is used as a key in {@link RoutingConfig#fallbackChains()} and
 * referenced by {@link RoutingConfig#defaultChain()} and
 * {@link RouteRuleConfig#targetChain()}. The reserved name {@code "default"} is
 * used when no other chain is explicitly selected.</p>
 *
 * <p>YAML example:</p>
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
 *       cheap:
 *         endpoints:
 *           - provider: openai-prod
 *             model: gpt-4o-mini
 *           - provider: ollama-local
 *             model: llama3.2
 * }</pre>
 */
public record FallbackChainConfig(

    /**
     * Ordered list of endpoints. Entry 0 is the primary; subsequent entries are
     * fallbacks tried in order when the previous entry is unavailable.
     *
     * <p>The chain name is the map key in {@link RoutingConfig#fallbackChains()} —
     * it is NOT stored inside this record to avoid YAML binding conflicts.</p>
     */
    List<ChainEndpointConfig> endpoints

) {
    public FallbackChainConfig {
        if (endpoints == null) endpoints = List.of();
    }
}
