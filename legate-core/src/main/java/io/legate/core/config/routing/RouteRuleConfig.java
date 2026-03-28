package io.legate.core.config.routing;

import java.util.Map;

/**
 * A conditional routing rule evaluated before endpoint selection.
 *
 * <p>Rules are tried in declaration order; the first match wins. When a rule
 * matches, the routing engine resolves {@link #targetModel()} (if set) as a new
 * model alias, or routes to the chain named by {@link #targetChain()} (if set).
 * At least one of {@code targetModel} or {@code targetChain} must be non-null.</p>
 *
 * <h3>Supported condition keys</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Description</th><th>Example value</th></tr>
 *   <tr><td>{@code max-input-tokens}</td><td>Matches when the estimated input token count is &le; this value.</td><td>{@code "500"}</td></tr>
 *   <tr><td>{@code min-input-tokens}</td><td>Matches when the estimated input token count is &ge; this value.</td><td>{@code "2000"}</td></tr>
 *   <tr><td>{@code virtual-key}</td><td>Matches when the virtual key ID equals this value exactly.</td><td>{@code "wdn_live_team_a"}</td></tr>
 *   <tr><td>{@code model}</td><td>Matches when the (post-alias-resolution) model name equals or glob-matches this value.</td><td>{@code "gpt-4*"}</td></tr>
 *   <tr><td>{@code header.<name>}</td><td>Matches when request header {@code <name>} equals this value.</td><td>{@code "premium"} for key {@code header.x-tier}</td></tr>
 * </table>
 *
 * <p>All conditions in a single rule are AND-ed together.</p>
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   routing:
 *     rules:
 *       - name: route-short-prompts-to-mini
 *         conditions:
 *           max-input-tokens: "500"
 *         target-model: gpt-4o-mini
 *       - name: premium-key-uses-best-chain
 *         conditions:
 *           header.x-tier: premium
 *         target-chain: premium-chain
 * }</pre>
 */
public record RouteRuleConfig(

    /** Human-readable name for logging and debugging. */
    String name,

    /**
     * Map of condition type to expected value.
     * All entries must match for the rule to fire (AND logic).
     */
    Map<String, String> conditions,

    /**
     * Model name (or alias) to substitute into the request when this rule matches.
     * Mutually exclusive with {@link #targetChain()} — set exactly one.
     */
    String targetModel,

    /**
     * Name of the fallback chain to use when this rule matches.
     * Must match a key in {@link RoutingConfig#fallbackChains()}.
     * Mutually exclusive with {@link #targetModel()} — set exactly one.
     */
    String targetChain

) {
    public RouteRuleConfig {
        if (conditions == null) {
            conditions = Map.of();
        }
    }

    /** Returns {@code true} if this rule has at least one condition. */
    public boolean hasConditions() {
        return !conditions.isEmpty();
    }
}
