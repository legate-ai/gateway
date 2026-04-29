package io.legate.core.config.routing;

import java.util.Map;

/**
 * A conditional routing rule evaluated before endpoint selection.
 *
 * <h3>Supported condition keys</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Description</th></tr>
 *   <tr><td>{@code max-input-tokens}</td><td>Estimated input tokens &le; N</td></tr>
 *   <tr><td>{@code min-input-tokens}</td><td>Estimated input tokens &ge; N</td></tr>
 *   <tr><td>{@code virtual-key}</td><td>Virtual key ID equals value exactly</td></tr>
 *   <tr><td>{@code team}</td><td>Virtual key team name glob-matches value (e.g. {@code "team-*"})</td></tr>
 *   <tr><td>{@code model}</td><td>Model name glob-matches value (e.g. {@code "gpt-4*"})</td></tr>
 *   <tr><td>{@code header.<name>}</td><td>Request header value equals value</td></tr>
 * </table>
 *
 * <h3>Percentage-based A/B routing</h3>
 * <p>Set {@code percentage} (1–100) to have the rule fire only for that fraction of
 * matched requests. The remaining fraction falls through to the next rule.
 * Example: two rules both matching {@code model: gpt-4o}, one with
 * {@code percentage: 10 → chain-a} and one without (100%) {@code → chain-b} gives
 * a 10/90 split.</p>
 *
 * <h3>Sticky session routing</h3>
 * <p>Set {@code sticky-header} to the name of a header (e.g. {@code x-conversation-id})
 * whose value is consistently hashed to select the same endpoint across requests
 * in the same conversation. This improves prompt-cache locality.</p>
 */
public record RouteRuleConfig(

    /** Human-readable name for logging and debugging. */
    String name,

    /** Map of condition key → expected value. All entries AND-ed. */
    Map<String, String> conditions,

    /** Model name/alias to substitute when this rule matches. */
    String targetModel,

    /** Fallback chain name to use when this rule matches. */
    String targetChain,

    /**
     * Sampling percentage (1–100). When set, the rule only fires for this fraction
     * of requests that satisfy all conditions. {@code 0} or {@code null} = always fire.
     */
    Integer percentage,

    /**
     * Header name used for consistent-hash sticky routing (e.g. {@code x-conversation-id}).
     * When set, requests that match this rule are routed to the same endpoint
     * within the target chain based on a stable hash of this header's value.
     */
    String stickyHeader

) {
    public RouteRuleConfig {
        if (conditions == null) {
            conditions = Map.of();
        }
    }

    /** Compact constructor without the new fields for backward compatibility. */
    public RouteRuleConfig(String name, Map<String, String> conditions,
                           String targetModel, String targetChain) {
        this(name, conditions, targetModel, targetChain, null, null);
    }

    /** Returns {@code true} if this rule has at least one condition. */
    public boolean hasConditions() {
        return !conditions.isEmpty();
    }

    /** Returns {@code true} if percentage-based sampling is configured. */
    public boolean hasSampling() {
        return percentage != null && percentage > 0 && percentage < 100;
    }

    /** Returns {@code true} if sticky-header routing is configured. */
    public boolean hasStickyHeader() {
        return stickyHeader != null && !stickyHeader.isBlank();
    }
}
