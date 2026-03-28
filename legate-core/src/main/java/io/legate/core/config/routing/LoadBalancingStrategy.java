package io.legate.core.config.routing;

/**
 * Load-balancing algorithm used when multiple endpoints are eligible for a request.
 *
 * <p>Endpoints eligible for load balancing are those that: (a) appear in the same
 * fallback chain position, (b) have their circuit breaker in CLOSED or HALF_OPEN
 * state, and (c) pass health checks.</p>
 *
 * <p>YAML usage:</p>
 * <pre>{@code
 * legate:
 *   routing:
 *     load-balancer:
 *       strategy: least-latency   # or LEAST_LATENCY
 * }</pre>
 */
public enum LoadBalancingStrategy {

    /**
     * Distributes requests evenly across all eligible endpoints in turn.
     * Uses an atomic counter mod endpoint count. This is the default strategy.
     * <p>All endpoints receive equal traffic regardless of performance or cost.</p>
     */
    ROUND_ROBIN,

    /**
     * Distributes requests proportional to each endpoint's {@code weight} field
     * in {@link ChainEndpointConfig}.
     * <p>Example: weight 200 receives twice the traffic of weight 100.</p>
     */
    WEIGHTED,

    /**
     * Prefers the endpoint with the lowest exponentially-weighted moving average
     * (EWMA) latency. Automatically routes away from slow endpoints without
     * manual reconfiguration.
     * <p>The EWMA is updated after every completed request.</p>
     */
    LEAST_LATENCY,

    /**
     * Prefers the cheapest endpoint per token based on the configured
     * {@code model-pricing} table.
     * <p>Useful when multiple providers offer the same model at different prices
     * (e.g., regional pricing differences).</p>
     * <p>Falls back to {@code ROUND_ROBIN} for models not in the pricing table.</p>
     */
    COST_OPTIMIZED
}
