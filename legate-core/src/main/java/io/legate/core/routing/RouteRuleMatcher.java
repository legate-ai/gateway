package io.legate.core.routing;

import io.legate.core.config.routing.RouteRuleConfig;
import io.legate.core.context.VirtualKeyInfo;
import io.legate.core.model.ChatCompletionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluates ordered {@link RouteRuleConfig} rules against an incoming request
 * and returns the first matching rule's target (model override or chain name).
 *
 * <h3>Condition keys</h3>
 * <ul>
 *   <li>{@code max-input-tokens: N} — matches when estimated input tokens ≤ N</li>
 *   <li>{@code min-input-tokens: N} — matches when estimated input tokens ≥ N</li>
 *   <li>{@code virtual-key: X} — matches when virtual key ID equals X</li>
 *   <li>{@code model: gpt-*} — glob pattern on the request model name</li>
 *   <li>{@code header.<name>: value} — matches when request header {@code <name>} equals {@code value}</li>
 * </ul>
 *
 * <p>All conditions in a rule are AND-ed. The first rule where all conditions match wins.</p>
 *
 * <p>Thread-safe: holds only immutable state after construction.</p>
 */
public class RouteRuleMatcher {

    private static final Logger log = LoggerFactory.getLogger(RouteRuleMatcher.class);

    /**
     * The outcome of a matching rule. Exactly one of {@link #targetModel} or
     * {@link #targetChain} will be non-null.
     *
     * @param ruleName    the name of the matched rule, for logging
     * @param targetModel model name/alias to substitute, or {@code null}
     * @param targetChain fallback chain name to use, or {@code null}
     */
    public record RuleMatch(String ruleName, String targetModel, String targetChain) {}

    private final List<RouteRuleConfig> rules;

    /**
     * Creates a matcher with an empty rule list (always returns empty).
     */
    public RouteRuleMatcher() {
        this.rules = List.of();
    }

    /**
     * Creates a matcher from the given list of rules.
     *
     * @param rules ordered list of routing rules; must not be null
     */
    public RouteRuleMatcher(List<RouteRuleConfig> rules) {
        this.rules = rules != null ? List.copyOf(rules) : List.of();
    }

    /**
     * Evaluates all rules in order and returns the first match.
     *
     * @param request   the chat completion request being routed
     * @param keyInfo   the authenticated virtual key, or {@code null} for unauthenticated requests
     * @param headers   the lowercase request headers map
     * @return the first matching rule's target, or empty if no rule matches
     */
    public Optional<RuleMatch> match(
        ChatCompletionRequest request,
        VirtualKeyInfo keyInfo,
        Map<String, String> headers
    ) {
        int estimatedTokens = estimateTokens(request);

        for (RouteRuleConfig rule : rules) {
            if (!rule.hasConditions()) continue;
            if (allConditionsMatch(rule, request, keyInfo, headers, estimatedTokens)) {
                String ruleName = rule.name() != null ? rule.name() : "(unnamed)";
                log.debug("Route rule '{}' matched — targetModel='{}', targetChain='{}'",
                    ruleName, rule.targetModel(), rule.targetChain());
                return Optional.of(new RuleMatch(ruleName, rule.targetModel(), rule.targetChain()));
            }
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private boolean allConditionsMatch(
        RouteRuleConfig rule,
        ChatCompletionRequest request,
        VirtualKeyInfo keyInfo,
        Map<String, String> headers,
        int estimatedTokens
    ) {
        for (Map.Entry<String, String> condition : rule.conditions().entrySet()) {
            String key   = condition.getKey();
            String value = condition.getValue();

            boolean match = switch (key) {
                case "max-input-tokens" -> {
                    try { yield estimatedTokens <= Integer.parseInt(value); }
                    catch (NumberFormatException e) {
                        log.warn("Route rule '{}': invalid max-input-tokens value '{}'",
                            rule.name(), value);
                        yield false;
                    }
                }
                case "min-input-tokens" -> {
                    try { yield estimatedTokens >= Integer.parseInt(value); }
                    catch (NumberFormatException e) {
                        log.warn("Route rule '{}': invalid min-input-tokens value '{}'",
                            rule.name(), value);
                        yield false;
                    }
                }
                case "virtual-key" -> {
                    yield keyInfo != null && value.equals(keyInfo.keyId());
                }
                case "model" -> {
                    String model = request.model();
                    yield model != null && globMatches(value, model);
                }
                default -> {
                    if (key.startsWith("header.")) {
                        String headerName = key.substring("header.".length()).toLowerCase();
                        yield value.equals(headers.get(headerName));
                    }
                    log.warn("Route rule '{}': unknown condition key '{}' — skipping rule", rule.name(), key);
                    yield false;
                }
            };

            if (!match) return false;
        }
        return true;
    }

    private static int estimateTokens(ChatCompletionRequest request) {
        if (request.messages() == null) return 0;
        return request.messages().stream()
            .mapToInt(m -> m.content() != null ? m.content().length() / 4 : 0)
            .sum();
    }

    /**
     * Simple glob pattern matcher supporting {@code *} (any sequence) and {@code ?} (any char).
     */
    static boolean globMatches(String pattern, String text) {
        if (pattern == null || text == null) return false;
        // Convert glob to a simple recursive match
        return globMatchesRecursive(pattern, 0, text, 0);
    }

    private static boolean globMatchesRecursive(String pattern, int pi, String text, int ti) {
        while (pi < pattern.length() && ti < text.length()) {
            char pc = pattern.charAt(pi);
            if (pc == '*') {
                // Skip consecutive stars
                while (pi < pattern.length() && pattern.charAt(pi) == '*') pi++;
                if (pi == pattern.length()) return true; // trailing star matches all
                // Try matching the rest of the pattern against every position
                for (int i = ti; i <= text.length(); i++) {
                    if (globMatchesRecursive(pattern, pi, text, i)) return true;
                }
                return false;
            } else if (pc == '?' || pc == text.charAt(ti)) {
                pi++;
                ti++;
            } else {
                return false;
            }
        }
        // Consume trailing stars
        while (pi < pattern.length() && pattern.charAt(pi) == '*') pi++;
        return pi == pattern.length() && ti == text.length();
    }
}
