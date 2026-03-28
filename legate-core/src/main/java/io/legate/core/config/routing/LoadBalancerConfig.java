package io.legate.core.config.routing;

/**
 * Load-balancing configuration for distributing requests across multiple
 * eligible endpoints.
 *
 * <h3>When does load balancing apply?</h3>
 * <p>Within a fallback chain, endpoints at the same position with the same
 * model are treated as peers. The load balancer selects one of those peers
 * for each request. Endpoints at later positions are only tried if all
 * earlier positions are exhausted (circuit open or unavailable).</p>
 *
 * <h3>Strategy details</h3>
 * <ul>
 *   <li><b>ROUND_ROBIN</b> — uses an atomic counter; best for homogeneous providers.</li>
 *   <li><b>WEIGHTED</b> — reads {@link ChainEndpointConfig#weight()} for each endpoint;
 *       default weight is 100.</li>
 *   <li><b>LEAST_LATENCY</b> — tracks an EWMA of upstream latency per endpoint;
 *       prefer this when providers have different SLAs.</li>
 *   <li><b>COST_OPTIMIZED</b> — prefers the cheapest endpoint using the model-pricing
 *       table; falls back to ROUND_ROBIN for models without pricing data.</li>
 * </ul>
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   routing:
 *     load-balancer:
 *       strategy: weighted     # or WEIGHTED, ROUND_ROBIN, LEAST_LATENCY, COST_OPTIMIZED
 * }</pre>
 */
public record LoadBalancerConfig(

    /**
     * Algorithm used to select an endpoint from among the eligible peers.
     * Default: {@link LoadBalancingStrategy#ROUND_ROBIN}.
     */
    LoadBalancingStrategy strategy

) {
    public LoadBalancerConfig {
        if (strategy == null) {
            strategy = LoadBalancingStrategy.ROUND_ROBIN;
        }
    }

    /** Default load-balancer — round-robin distribution. */
    public static LoadBalancerConfig defaults() {
        return new LoadBalancerConfig(LoadBalancingStrategy.ROUND_ROBIN);
    }

    /** Convenience factory for a specific strategy. */
    public static LoadBalancerConfig of(LoadBalancingStrategy strategy) {
        return new LoadBalancerConfig(strategy);
    }
}
