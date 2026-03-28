package io.legate.core.config.routing;

/**
 * A single endpoint entry within a {@link FallbackChainConfig}.
 *
 * <p>The chain is evaluated in order: the first entry is the primary endpoint.
 * If a request fails (upstream error, circuit open, health-check failed), Legate
 * tries the next entry. The {@link #weight()} field is used by the
 * {@link LoadBalancingStrategy#WEIGHTED} strategy when multiple entries at the
 * same position serve the same model.</p>
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   routing:
 *     fallback-chains:
 *       smart-chat:
 *         endpoints:
 *           - provider: openai-prod          # primary
 *             model: gpt-4o
 *             weight: 70
 *           - provider: openai-backup        # 30% traffic / fallback
 *             model: gpt-4o
 *             weight: 30
 *           - provider: anthropic-prod       # fallback only (different model)
 *             model: claude-3-5-sonnet-20241022
 * }</pre>
 */
public record ChainEndpointConfig(

    /**
     * The logical name of the provider to route to.
     * Must match a {@link io.legate.core.config.provider.ProviderConfig#name()} exactly.
     */
    String provider,

    /**
     * Optional model override applied when this endpoint is selected.
     * When set, the request's model field is replaced with this value before it is
     * forwarded to the provider. Useful for routing to a cheaper model as a fallback.
     * When {@code null}, the model from the original request is used as-is.
     */
    String model,

    /**
     * Relative weight for load-balancing within the same chain position.
     * Only used by {@link LoadBalancingStrategy#WEIGHTED}.
     * Default: {@code 100}. Must be a positive integer.
     */
    int weight

) {
    public ChainEndpointConfig {
        if (weight <= 0) weight = 100;
    }
}
