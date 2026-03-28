package io.legate.core.routing;

/**
 * Result of the routing decision process.
 */
public record RoutingDecision(
    ResolvedEndpoint endpoint,
    String routingReason,
    String fallbackChainName,
    int attemptNumber
) {
    /**
     * Creates a routing decision for the primary endpoint.
     */
    public static RoutingDecision primary(ResolvedEndpoint endpoint, String reason) {
        return new RoutingDecision(endpoint, reason, null, 1);
    }

    /**
     * Creates a routing decision for a fallback endpoint.
     */
    public static RoutingDecision fallback(
        ResolvedEndpoint endpoint,
        String chainName,
        int attemptNumber,
        String reason
    ) {
        return new RoutingDecision(endpoint, reason, chainName, attemptNumber);
    }

    /**
     * Returns true if this is a fallback routing.
     */
    public boolean isFallback() {
        return attemptNumber > 1;
    }
}
