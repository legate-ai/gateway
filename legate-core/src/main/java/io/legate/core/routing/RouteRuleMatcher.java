package io.legate.core.routing;

import io.legate.core.config.routing.RouteRuleConfig;
import io.legate.core.context.VirtualKeyInfo;
import io.legate.core.model.ChatCompletionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Evaluates ordered {@link RouteRuleConfig} rules and returns the first matching rule's target.
 *
 * <h3>Condition keys</h3>
 * <ul>
 *   <li>{@code max-input-tokens: N} — estimated input tokens &le; N</li>
 *   <li>{@code min-input-tokens: N} — estimated input tokens &ge; N</li>
 *   <li>{@code virtual-key: X} — virtual key ID equals X</li>
 *   <li>{@code team: X} — virtual key team name glob-matches X (e.g. {@code "team-*"})</li>
 *   <li>{@code model: gpt-*} — glob pattern on request model</li>
 *   <li>{@code header.<name>: value} — request header equals value</li>
 * </ul>
 *
 * <p>All conditions in a rule are AND-ed. The first rule where all conditions match wins,
 * subject to optional percentage-based sampling.</p>
 *
 * <p>Thread-safe: holds only immutable state after construction.</p>
 */
public class RouteRuleMatcher {

    private static final Logger log = LoggerFactory.getLogger(RouteRuleMatcher.class);

    /**
     * The outcome of a matching rule.
     *
     * @param ruleName    name of the matched rule, for logging
     * @param targetModel model name/alias to substitute, or {@code null}
     * @param targetChain fallback chain name to use, or {@code null}
     * @param stickyKey   value to hash for consistent endpoint selection, or {@code null}
     */
    public record RuleMatch(
        String ruleName,
        String targetModel,
        String targetChain,
        String stickyKey
    ) {
        /** Convenience constructor without sticky key (backward compatibility). */
        public RuleMatch(String ruleName, String targetModel, String targetChain) {
            this(ruleName, targetModel, targetChain, null);
        }
    }

    private final List<RouteRuleConfig> rules;

    public RouteRuleMatcher() {
        this.rules = List.of();
    }

    public RouteRuleMatcher(List<RouteRuleConfig> rules) {
        this.rules = rules != null ? List.copyOf(rules) : List.of();
    }

    public Optional<RuleMatch> match(
        ChatCompletionRequest request,
        VirtualKeyInfo keyInfo,
        Map<String, String> headers
    ) {
        int estimatedTokens = estimateTokens(request);

        for (RouteRuleConfig rule : rules) {
            if (!rule.hasConditions()) {
                continue;
            }
            if (!allConditionsMatch(rule, request, keyInfo, headers, estimatedTokens)) {
                continue;
            }
            // Percentage-based sampling: skip this rule with (100 - percentage)% probability
            if (rule.hasSampling() && ThreadLocalRandom.current().nextInt(100) >= rule.percentage()) {
                continue;
            }

            String ruleName = rule.name() != null ? rule.name() : "(unnamed)";
            String stickyKey = resolveSticky(rule, headers);

            log.debug("Route rule '{}' matched — targetModel='{}', targetChain='{}', sticky='{}'",
                ruleName, rule.targetModel(), rule.targetChain(), stickyKey);

            return Optional.of(new RuleMatch(ruleName, rule.targetModel(), rule.targetChain(), stickyKey));
        }
        return Optional.empty();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private boolean allConditionsMatch(
        RouteRuleConfig rule,
        ChatCompletionRequest request,
        VirtualKeyInfo keyInfo,
        Map<String, String> headers,
        int estimatedTokens
    ) {
        for (Map.Entry<String, String> condition : rule.conditions().entrySet()) {
            String key = condition.getKey();
            String value = condition.getValue();

            boolean match = switch (key) {
                case "max-input-tokens" -> {
                    try {
                        yield estimatedTokens <= Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        log.warn("Route rule '{}': invalid max-input-tokens '{}'", rule.name(), value);
                        yield false;
                    }
                }
                case "min-input-tokens" -> {
                    try {
                        yield estimatedTokens >= Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        log.warn("Route rule '{}': invalid min-input-tokens '{}'", rule.name(), value);
                        yield false;
                    }
                }
                case "virtual-key" -> keyInfo != null && value.equals(keyInfo.keyId());
                case "team" -> {
                    yield keyInfo != null
                        && keyInfo.teamName() != null
                        && globMatches(value, keyInfo.teamName());
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
                    log.warn("Route rule '{}': unknown condition key '{}' — skipping", rule.name(), key);
                    yield false;
                }
            };

            if (!match) {
                return false;
            }
        }
        return true;
    }

    private String resolveSticky(RouteRuleConfig rule, Map<String, String> headers) {
        if (!rule.hasStickyHeader()) {
            return null;
        }
        return headers.get(rule.stickyHeader().toLowerCase());
    }

    private static int estimateTokens(ChatCompletionRequest request) {
        if (request.messages() == null) {
            return 0;
        }
        return request.messages().stream()
            .mapToInt(m -> m.content() != null ? m.content().length() / 4 : 0)
            .sum();
    }

    static boolean globMatches(String pattern, String text) {
        if (pattern == null || text == null) {
            return false;
        }
        return globMatchesRecursive(pattern, 0, text, 0);
    }

    private static boolean globMatchesRecursive(String pattern, int pi, String text, int ti) {
        while (pi < pattern.length() && ti < text.length()) {
            char pc = pattern.charAt(pi);
            if (pc == '*') {
                while (pi < pattern.length() && pattern.charAt(pi) == '*') {
                    pi++;
                }
                if (pi == pattern.length()) {
                    return true;
                }
                for (int i = ti; i <= text.length(); i++) {
                    if (globMatchesRecursive(pattern, pi, text, i)) {
                        return true;
                    }
                }
                return false;
            } else if (pc == '?' || pc == text.charAt(ti)) {
                pi++;
                ti++;
            } else {
                return false;
            }
        }
        while (pi < pattern.length() && pattern.charAt(pi) == '*') {
            pi++;
        }
        return pi == pattern.length() && ti == text.length();
    }
}
