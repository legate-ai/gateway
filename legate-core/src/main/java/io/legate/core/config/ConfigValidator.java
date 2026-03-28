package io.legate.core.config;

import io.legate.core.config.alert.AlertConfig;
import io.legate.core.config.alert.AlertTemplate;
import io.legate.core.config.cache.CacheBackend;
import io.legate.core.config.guard.BuiltInRequestGuardType;
import io.legate.core.config.guard.BuiltInResponseGuardType;
import io.legate.core.config.guard.PiiDetectorConfig;
import io.legate.core.config.guard.RequestGuardConfig;
import io.legate.core.config.guard.ResponseGuardConfig;
import io.legate.core.config.pricing.ModelPricingConfig;
import io.legate.core.config.provider.ProviderConfig;
import io.legate.core.config.provider.ProviderType;
import io.legate.core.config.ratelimit.RateLimitingConfig;
import io.legate.core.config.routing.ChainEndpointConfig;
import io.legate.core.config.routing.FallbackChainConfig;
import io.legate.core.config.routing.RouteRuleConfig;
import io.legate.core.config.routing.RoutingConfig;
import io.legate.core.config.spend.SpendControlConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Validates a {@link LegateConfig} and returns a list of human-readable findings.
 *
 * <p>Validation is non-throwing. Callers receive a {@link List} of
 * {@link ValidationError} objects. Legate fails to start (or refuses a hot-reload)
 * when any {@link Severity#ERROR} entries are present. {@link Severity#WARNING}
 * entries are logged but do not prevent startup.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * List<ValidationError> errors = ConfigValidator.validate(config);
 * errors.forEach(e -> log.warn("{}", e));
 * if (!ConfigValidator.isValid(config)) {
 *     throw new ConfigValidationException(errors);
 * }
 * }</pre>
 */
public final class ConfigValidator {

    private ConfigValidator() {
    }

    /**
     * Validates the supplied configuration and returns all discovered findings.
     *
     * @param config the config to validate — must not be {@code null}
     * @return unmodifiable list of validation findings; empty means the config is clean
     */
    public static List<ValidationError> validate(LegateConfig config) {
        if (config == null) {
            return List.of(ValidationError.error("(root)", "LegateConfig must not be null"));
        }

        List<ValidationError> errors = new ArrayList<>();

        validateProviders(config, errors);
        validateRouting(config, errors);
        validateGuards(config, errors);
        validateCache(config, errors);
        validateRateLimiting(config, errors);
        validateSpendControl(config, errors);
        validateAdmin(config, errors);
        validateModelPricing(config, errors);
        validateAlerts(config, errors);

        return List.copyOf(errors);
    }

    /**
     * Returns {@code true} when the config contains no {@link Severity#ERROR} entries.
     *
     * @param config the config to check (may be null; returns false if null)
     */
    public static boolean isValid(LegateConfig config) {
        if (config == null) {
            return false;
        }
        return validate(config).stream().noneMatch(e -> e.severity() == Severity.ERROR);
    }

    // =========================================================================
    // Providers
    // =========================================================================

    private static void validateProviders(LegateConfig config, List<ValidationError> errors) {
        List<ProviderConfig> providers = config.providers();

        if (providers.isEmpty()) {
            errors.add(ValidationError.error("providers",
                    "At least one provider must be configured under legate.providers"));
            return;
        }

        Set<String> names = new HashSet<>();

        for (int i = 0; i < providers.size(); i++) {
            ProviderConfig providerConfig = providers.get(i);
            String prefix = "providers[" + i + "]";

            // Name
            if (providerConfig.name() == null || providerConfig.name().isBlank()) {
                errors.add(ValidationError.error(prefix + ".name",
                        "Provider name must not be blank"));
            } else if (!names.add(providerConfig.name())) {
                errors.add(ValidationError.error(prefix + ".name",
                        "Duplicate provider name: '" + providerConfig.name() + "'"));
            }

            // Type
            if (providerConfig.type() == null) {
                errors.add(ValidationError.error(prefix + ".type",
                        "Provider type must not be null. Valid values: " +
                                Arrays.toString(ProviderType.values())));
            }

            // Base URL
            if (providerConfig.baseUrl() == null || providerConfig.baseUrl().isBlank()) {
                if (providerConfig.type() != ProviderType.BEDROCK && providerConfig.type() != ProviderType.VERTEXAI) {
                    errors.add(ValidationError.error(prefix + ".base-url",
                            "Provider base URL is required for type " + providerConfig.type() +
                                    " (Bedrock and VertexAI compute it from properties)"));
                }
            } else if (providerConfig.baseUrl().endsWith("/")) {
                errors.add(ValidationError.warning(prefix + ".base-url",
                        "Provider base URL should not end with a trailing slash — it will be doubled in request URLs"));
            }

            // API key env var
            if (providerConfig.type() != ProviderType.OLLAMA && providerConfig.type() != ProviderType.BEDROCK) {
                if (providerConfig.apiKeyEnvVar() == null || providerConfig.apiKeyEnvVar().isBlank()) {
                    errors.add(ValidationError.warning(prefix + ".api-key-env-var",
                            "No API key env var configured for provider '" + providerConfig.name() +
                                    "' (type: " + providerConfig.type() + "); provider may reject unauthenticated requests"));
                } else {
                    String resolved = System.getenv(providerConfig.apiKeyEnvVar());
                    if (resolved == null || resolved.isBlank()) {
                        errors.add(ValidationError.warning(prefix + ".api-key-env-var",
                                "Environment variable '" + providerConfig.apiKeyEnvVar() +
                                        "' is not set or empty for provider '" + providerConfig.name() + "'"));
                    }
                }
            }

            // Provider-specific property validation
            validateProviderProperties(providerConfig, prefix, errors);

            // Models
            if (providerConfig.models().isEmpty()) {
                errors.add(ValidationError.warning(prefix + ".models",
                        "Provider '" + providerConfig.name() + "' has no models configured; " +
                                "it will never be selected by the routing engine"));
            }

            // Weight
            if (providerConfig.weight() <= 0) {
                errors.add(ValidationError.error(prefix + ".weight",
                        "Provider weight must be a positive integer, got: " + providerConfig.weight()));
            }
        }
    }

    private static void validateProviderProperties(ProviderConfig p, String prefix,
                                                   List<ValidationError> errors) {
        if (p.type() == null) return;

        switch (p.type()) {
            case AZURE_OPENAI -> {
                requireProperty(p, "deployment-id", prefix, errors);
                // api-version has a sensible default in the adapter; warn if missing
                if (!p.properties().containsKey("api-version")) {
                    errors.add(ValidationError.warning(prefix + ".properties",
                            "Azure OpenAI provider '" + p.name() + "' does not specify 'api-version'; " +
                                    "the adapter will use its built-in default (2024-10-21)"));
                }
            }
            case BEDROCK -> {
                requireProperty(p, "region", prefix, errors);
                requireProperty(p, "aws-access-key-id-env-var", prefix, errors);
                requireProperty(p, "aws-secret-access-key-env-var", prefix, errors);
                // Validate that the referenced env vars exist
                checkEnvVarProperty(p, "aws-access-key-id-env-var", prefix, errors);
                checkEnvVarProperty(p, "aws-secret-access-key-env-var", prefix, errors);
            }
            case VERTEXAI -> {
                requireProperty(p, "project-id", prefix, errors);
                requireProperty(p, "location", prefix, errors);
                requireProperty(p, "service-account-key-env-var", prefix, errors);
                checkEnvVarProperty(p, "service-account-key-env-var", prefix, errors);
            }
            default -> {
                // No extra property requirements for OPENAI, ANTHROPIC, OLLAMA
            }
        }
    }

    private static void requireProperty(ProviderConfig p, String key, String prefix,
                                        List<ValidationError> errors) {
        if (!p.properties().containsKey(key) || p.properties().get(key).isBlank()) {
            errors.add(ValidationError.error(prefix + ".properties." + key,
                    "Provider '" + p.name() + "' (type: " + p.type() + ") requires property '" + key + "'"));
        }
    }

    private static void checkEnvVarProperty(ProviderConfig p, String propertyKey, String prefix,
                                            List<ValidationError> errors) {
        String envVarName = p.properties().get(propertyKey);
        if (envVarName != null && !envVarName.isBlank()) {
            String value = System.getenv(envVarName);
            if (value == null || value.isBlank()) {
                errors.add(ValidationError.warning(prefix + ".properties." + propertyKey,
                        "Environment variable '" + envVarName + "' (referenced by '" + propertyKey +
                                "' for provider '" + p.name() + "') is not set or empty"));
            }
        }
    }

    // =========================================================================
    // Routing
    // =========================================================================

    private static void validateRouting(LegateConfig config, List<ValidationError> errors) {
        RoutingConfig routing = config.routing();
        Set<String> providerNames = providerNames(config);
        Set<String> chainNames = routing.fallbackChains().keySet();

        // Validate fallback chains
        routing.fallbackChains().forEach((chainName, chain) -> {
            String prefix = "routing.fallback-chains." + chainName;

            if (chain.endpoints().isEmpty()) {
                errors.add(ValidationError.error(prefix,
                        "Fallback chain '" + chainName + "' has no endpoints configured"));
                return;
            }

            for (int i = 0; i < chain.endpoints().size(); i++) {
                ChainEndpointConfig ep = chain.endpoints().get(i);
                String epPrefix = prefix + ".endpoints[" + i + "]";

                if (ep.provider() == null || ep.provider().isBlank()) {
                    errors.add(ValidationError.error(epPrefix + ".provider",
                            "Endpoint provider must not be blank"));
                } else if (!providerNames.contains(ep.provider())) {
                    String suggestion = closestMatch(ep.provider(), providerNames);
                    errors.add(ValidationError.error(epPrefix + ".provider",
                            "Endpoint references unknown provider '" + ep.provider() + "'" +
                                    (suggestion != null ? ". Did you mean '" + suggestion + "'?" : "") +
                                    ". Configured providers: " + providerNames));
                }

                if (ep.weight() <= 0) {
                    errors.add(ValidationError.error(epPrefix + ".weight",
                            "Endpoint weight must be positive, got: " + ep.weight()));
                }
            }
        });

        // Validate defaultChain references an existing chain (if chains are configured)
        String defaultChain = routing.defaultChain();
        if (!routing.fallbackChains().isEmpty() && !routing.fallbackChains().containsKey(defaultChain)) {
            String suggestion = closestMatch(defaultChain, chainNames);
            errors.add(ValidationError.warning("routing.default-chain",
                    "defaultChain '" + defaultChain + "' does not match any configured chain" +
                            (suggestion != null ? ". Did you mean '" + suggestion + "'?" : "") +
                            ". Configured chains: " + chainNames));
        }

        // Validate routing rules
        for (int i = 0; i < routing.rules().size(); i++) {
            RouteRuleConfig rule = routing.rules().get(i);
            String prefix = "routing.rules[" + i + "]";

            if (rule.name() == null || rule.name().isBlank()) {
                errors.add(ValidationError.warning(prefix + ".name",
                        "Rule at index " + i + " has no name; consider adding one for easier debugging"));
            }

            if (!rule.hasConditions()) {
                errors.add(ValidationError.warning(prefix + ".conditions",
                        "Rule '" + rule.name() + "' has no conditions; it will match every request " +
                                "(subsequent rules will never be evaluated)"));
            }

            boolean hasTarget = (rule.targetModel() != null && !rule.targetModel().isBlank())
                    || (rule.targetChain() != null && !rule.targetChain().isBlank());
            if (!hasTarget) {
                errors.add(ValidationError.error(prefix,
                        "Rule '" + rule.name() + "' must specify either targetModel or targetChain"));
            }

            if (rule.targetChain() != null && !rule.targetChain().isBlank()
                    && !routing.fallbackChains().containsKey(rule.targetChain())) {
                String suggestion = closestMatch(rule.targetChain(), chainNames);
                errors.add(ValidationError.error(prefix + ".target-chain",
                        "Rule '" + rule.name() + "' references unknown chain '" + rule.targetChain() + "'" +
                                (suggestion != null ? ". Did you mean '" + suggestion + "'?" : "")));
            }

            if (rule.targetModel() != null && rule.targetChain() != null) {
                errors.add(ValidationError.error(prefix,
                        "Rule '" + rule.name() + "' must specify either targetModel OR targetChain, not both"));
            }
        }

        // Validate retry config
        if (routing.retry().maxAttempts() < 1) {
            errors.add(ValidationError.error("routing.retry.max-attempts",
                    "maxAttempts must be >= 1, got: " + routing.retry().maxAttempts()));
        }

        // CircuitBreaker
        if (routing.circuitBreaker().failureThreshold() < 1) {
            errors.add(ValidationError.error("routing.circuit-breaker.failure-threshold",
                    "failureThreshold must be >= 1"));
        }
        if (routing.circuitBreaker().successThreshold() < 1) {
            errors.add(ValidationError.error("routing.circuit-breaker.success-threshold",
                    "successThreshold must be >= 1"));
        }
        if (routing.circuitBreaker().slidingWindowSize() < 1) {
            errors.add(ValidationError.error("routing.circuit-breaker.sliding-window-size",
                    "slidingWindowSize must be >= 1"));
        }

        // backoffMultiplier
        if (routing.retry().backoffMultiplier() <= 0) {
            errors.add(ValidationError.error("routing.retry.backoff-multiplier",
                    "backoffMultiplier must be > 0, got: " + routing.retry().backoffMultiplier()));
        }
    }

    // =========================================================================
    // Guards
    // =========================================================================

    private static void validateGuards(LegateConfig config, List<ValidationError> errors) {
        List<RequestGuardConfig> requestGuards = config.guards().requestGuards();

        for (int i = 0; i < requestGuards.size(); i++) {
            RequestGuardConfig g = requestGuards.get(i);
            String prefix = "guards.request-guards[" + i + "]";

            if (g.type() == null || g.type().isBlank()) {
                errors.add(ValidationError.error(prefix + ".type", "Guard type must not be blank"));
                continue;
            }

            BuiltInRequestGuardType builtIn = BuiltInRequestGuardType.fromConfigKey(g.type());

            if (builtIn == null && !looksLikeClassName(g.type())) {
                Set<String> knownKeys = Arrays.stream(BuiltInRequestGuardType.values())
                        .map(BuiltInRequestGuardType::configKey)
                        .collect(Collectors.toSet());
                String suggestion = closestMatch(g.type(), knownKeys);
                errors.add(ValidationError.warning(prefix + ".type",
                        "Unknown built-in request guard type '" + g.type() + "'" +
                                (suggestion != null ? ". Did you mean '" + suggestion + "'?" : "") +
                                ". Built-in types: " + knownKeys +
                                ". For custom guards use a fully-qualified class name (e.g., com.example.MyGuard)."));
            }

            if (builtIn != null) {
                switch (builtIn) {
                    case SYSTEM_PROMPT_INJECTOR -> {
                        if (g.systemPrompt() == null || g.systemPrompt().isBlank()) {
                            errors.add(ValidationError.error(prefix + ".system-prompt",
                                    "system-prompt-injector guard requires a non-blank systemPrompt"));
                        }
                    }
                    case MAX_TOKENS -> {
                        if (g.maxInputTokens() == null || g.maxInputTokens() <= 0) {
                            errors.add(ValidationError.error(prefix + ".max-input-tokens",
                                    "max-tokens guard requires maxInputTokens > 0"));
                        }
                    }
                    case KEYWORD_BLOCKER -> {
                        if (g.keywords().isEmpty()) {
                            errors.add(ValidationError.warning(prefix + ".keywords",
                                    "keyword-blocker guard has no keywords configured; it will never block"));
                        }
                    }
                    case PII_DETECTOR -> {
                        if (g.pii() != null) {
                            validatePiiConfig(g.pii(), prefix + ".pii", errors);
                        }
                    }
                }
            }
        }

        List<ResponseGuardConfig> responseGuards = config.guards().responseGuards();

        for (int i = 0; i < responseGuards.size(); i++) {
            ResponseGuardConfig g = responseGuards.get(i);
            String prefix = "guards.response-guards[" + i + "]";

            if (g.type() == null || g.type().isBlank()) {
                errors.add(ValidationError.error(prefix + ".type", "Guard type must not be blank"));
                continue;
            }

            BuiltInResponseGuardType builtIn = BuiltInResponseGuardType.fromConfigKey(g.type());

            if (builtIn == null && !looksLikeClassName(g.type())) {
                Set<String> knownKeys = Arrays.stream(BuiltInResponseGuardType.values())
                        .map(BuiltInResponseGuardType::configKey)
                        .collect(Collectors.toSet());
                String suggestion = closestMatch(g.type(), knownKeys);
                errors.add(ValidationError.warning(prefix + ".type",
                        "Unknown built-in response guard type '" + g.type() + "'" +
                                (suggestion != null ? ". Did you mean '" + suggestion + "'?" : "") +
                                ". Built-in types: " + knownKeys));
            }

            if (builtIn == BuiltInResponseGuardType.PII_DETECTOR && g.pii() != null) {
                validatePiiConfig(g.pii(), prefix + ".pii", errors);
            }
        }
    }

    private static void validatePiiConfig(PiiDetectorConfig pii, String prefix,
                                          List<ValidationError> errors) {
        // action is an enum — type-system prevents invalid values; null check only
        if (pii.action() == null) {
            errors.add(ValidationError.error(prefix + ".action", "PII action must not be null"));
        }

        for (int j = 0; j < pii.customPatterns().size(); j++) {
            try {
                java.util.regex.Pattern.compile(pii.customPatterns().get(j));
            } catch (PatternSyntaxException e) {
                errors.add(ValidationError.error(prefix + ".custom-patterns[" + j + "]",
                        "Invalid regex pattern '" + pii.customPatterns().get(j) + "': " + e.getDescription()));
            }
        }
    }

    // =========================================================================
    // Cache
    // =========================================================================

    private static void validateCache(LegateConfig config, List<ValidationError> errors) {
        var cache = config.cache();

        // backend is an enum — type-system prevents invalid values
        if (cache.backend() == null) {
            errors.add(ValidationError.error("cache.backend", "Cache backend must not be null"));
        }
        if (cache.backend() == CacheBackend.REDIS && cache.maxSize() > 0) {
            errors.add(ValidationError.warning("cache.max-size",
                    "maxSize is ignored for the Redis cache backend (TTL-based eviction only)"));
        }
        if (cache.maxSize() <= 0 && cache.backend() == CacheBackend.MEMORY) {
            errors.add(ValidationError.error("cache.max-size",
                    "Cache maxSize must be > 0 for the memory backend, got: " + cache.maxSize()));
        }
        if (cache.ttl() == null || cache.ttl().isNegative() || cache.ttl().isZero()) {
            errors.add(ValidationError.error("cache.ttl",
                    "Cache TTL must be a positive duration"));
        }
    }

    // =========================================================================
    // Rate limiting
    // =========================================================================

    private static void validateRateLimiting(LegateConfig config, List<ValidationError> errors) {
        RateLimitingConfig rl = config.rateLimiting();

        if (rl.global() != null) {
            validateRateLimitConfig(rl.global(), "rate-limiting.global", errors);
        }
        if (rl.perVirtualKeyDefault() != null) {
            validateRateLimitConfig(rl.perVirtualKeyDefault(), "rate-limiting.per-virtual-key-default", errors);
        }
        rl.overrides().forEach((key, cfg) ->
                validateRateLimitConfig(cfg, "rate-limiting.overrides." + key, errors));
    }

    private static void validateRateLimitConfig(RateLimitingConfig.RateLimitConfig cfg,
                                                String prefix,
                                                List<ValidationError> errors) {
        if (cfg.requestsPerMinute() < 0) {
            errors.add(ValidationError.error(prefix + ".requests-per-minute",
                    "requestsPerMinute must be >= 0 (0 = unlimited), got: " + cfg.requestsPerMinute()));
        }
        if (cfg.tokensPerDay() < 0) {
            errors.add(ValidationError.error(prefix + ".tokens-per-day",
                    "tokensPerDay must be >= 0 (0 = unlimited), got: " + cfg.tokensPerDay()));
        }
    }

    // =========================================================================
    // Spend control
    // =========================================================================

    private static void validateSpendControl(LegateConfig config, List<ValidationError> errors) {
        SpendControlConfig sc = config.spendControl();

        if (sc.global() != null) {
            validateSpendLimit(sc.global(), "spend-control.global", errors);
        }
        sc.perVirtualKey().forEach((key, limit) ->
                validateSpendLimit(limit, "spend-control.per-virtual-key." + key, errors));
    }

    private static void validateSpendLimit(SpendControlConfig.SpendLimitConfig limit,
                                           String prefix,
                                           List<ValidationError> errors) {
        // actionOnBreach is an enum — type-safe
        if (limit.actionOnBreach() == null) {
            errors.add(ValidationError.error(prefix + ".action-on-breach",
                    "actionOnBreach must not be null"));
        }
        if (limit.dailyLimitUsd() != null && limit.dailyLimitUsd().signum() < 0) {
            errors.add(ValidationError.error(prefix + ".daily-limit-usd",
                    "dailyLimitUsd must be non-negative, got: " + limit.dailyLimitUsd()));
        }
        if (limit.monthlyLimitUsd() != null && limit.monthlyLimitUsd().signum() < 0) {
            errors.add(ValidationError.error(prefix + ".monthly-limit-usd",
                    "monthlyLimitUsd must be non-negative, got: " + limit.monthlyLimitUsd()));
        }
        if (limit.isUnlimited()) {
            errors.add(ValidationError.warning(prefix,
                    "Spend limit has neither daily nor monthly limit configured; it is effectively unlimited"));
        }
    }

    // =========================================================================
    // Admin
    // =========================================================================

    private static void validateAdmin(LegateConfig config, List<ValidationError> errors) {
        var admin = config.admin();
        if (admin.requireAuth()) {
            String token = admin.resolveToken();
            if (token == null || token.isBlank()) {
                errors.add(ValidationError.warning("admin.token-env-var",
                        "Admin auth is enabled but env var '" + admin.tokenEnvVar() +
                                "' is not set. The admin API will reject all requests."));
            }
        } else {
            errors.add(ValidationError.warning("admin.require-auth",
                    "Admin authentication is disabled. Do not use this setting in production."));
        }
    }

    // =========================================================================
    // Model pricing
    // =========================================================================

    private static void validateModelPricing(LegateConfig config, List<ValidationError> errors) {
        Set<String> seen = new HashSet<>();
        List<ModelPricingConfig> pricing = config.modelPricing();

        for (int i = 0; i < pricing.size(); i++) {
            ModelPricingConfig mp = pricing.get(i);
            String prefix = "model-pricing[" + i + "]";

            if (mp.model() == null || mp.model().isBlank()) {
                errors.add(ValidationError.error(prefix + ".model",
                        "Model name must not be blank"));
                continue;
            }
            if (!seen.add(mp.model())) {
                errors.add(ValidationError.warning(prefix + ".model",
                        "Duplicate pricing entry for model '" + mp.model() + "'; only the first entry is used"));
            }
            if (mp.inputCostPerMillionTokens() == null || mp.inputCostPerMillionTokens().signum() < 0) {
                errors.add(ValidationError.error(prefix + ".input-cost-per-million-tokens",
                        "inputCostPerMillionTokens must be non-negative for model '" + mp.model() + "'"));
            }
            if (mp.outputCostPerMillionTokens() == null || mp.outputCostPerMillionTokens().signum() < 0) {
                errors.add(ValidationError.error(prefix + ".output-cost-per-million-tokens",
                        "outputCostPerMillionTokens must be non-negative for model '" + mp.model() + "'"));
            }
        }
    }

    // =========================================================================
    // Alerts
    // =========================================================================

    private static void validateAlerts(LegateConfig config, List<ValidationError> errors) {
        Set<String> names = new HashSet<>();
        List<AlertConfig> alerts = config.alerts();

        for (int i = 0; i < alerts.size(); i++) {
            AlertConfig a = alerts.get(i);
            String prefix = "alerts[" + i + "]";

            if (a.name() == null || a.name().isBlank()) {
                errors.add(ValidationError.error(prefix + ".name", "Alert name must not be blank"));
            } else if (!names.add(a.name())) {
                errors.add(ValidationError.warning(prefix + ".name",
                        "Duplicate alert name: '" + a.name() + "'"));
            }

            if (a.condition() == null || a.condition().isBlank()) {
                errors.add(ValidationError.error(prefix + ".condition",
                        "Alert condition must not be blank"));
            }

            if (a.webhook() == null || a.webhook().isBlank()) {
                errors.add(ValidationError.error(prefix + ".webhook",
                        "Alert webhook URL must not be blank"));
            }

            if (a.template() == null) {
                errors.add(ValidationError.error(prefix + ".template",
                        "Alert template must not be null. Valid values: " +
                                Arrays.toString(AlertTemplate.values())));
            }

            if (a.window() == null || a.window().isNegative() || a.window().isZero()) {
                errors.add(ValidationError.error(prefix + ".window",
                        "Alert window must be a positive duration"));
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Set<String> providerNames(LegateConfig config) {
        Set<String> names = new HashSet<>();
        config.providers().forEach(p -> {
            if (p.name() != null) names.add(p.name());
        });
        return names;
    }

    /**
     * Returns {@code true} if the string looks like a Java fully-qualified class name.
     */
    private static boolean looksLikeClassName(String s) {
        return s != null && s.contains(".");
    }

    /**
     * Returns the closest string in {@code candidates} to {@code input} using
     * Levenshtein edit distance, or {@code null} if the closest candidate is more
     * than 3 edits away.
     *
     * @param input      the misspelled or unknown string
     * @param candidates the set of valid alternatives
     * @return the best candidate, or {@code null}
     */
    static String closestMatch(String input, Set<String> candidates) {
        if (input == null || candidates.isEmpty()) return null;
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int dist = levenshtein(input.toLowerCase(), candidate.toLowerCase());
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return bestDist <= 3 ? best : null;
    }

    private static int levenshtein(String a, String b) {
        int[] dp = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) dp[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int prev = dp[0];
            dp[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int temp = dp[j];
                dp[j] = (a.charAt(i - 1) == b.charAt(j - 1))
                        ? prev
                        : 1 + Math.min(prev, Math.min(dp[j], dp[j - 1]));
                prev = temp;
            }
        }
        return dp[b.length()];
    }

    // =========================================================================
    // Result types
    // =========================================================================

    /**
     * Severity of a validation finding.
     */
    public enum Severity {ERROR, WARNING}

    /**
     * A single validation finding.
     *
     * @param field    dot-separated path to the offending config field
     *                 (relative to {@code legate.})
     * @param severity how serious the issue is
     * @param message  human-readable explanation including the problematic value
     *                 and, where possible, the correct alternative
     */
    public record ValidationError(String field, Severity severity, String message) {

        /**
         * Creates an {@link Severity#ERROR}-severity finding.
         */
        public static ValidationError error(String field, String message) {
            return new ValidationError(field, Severity.ERROR, message);
        }

        /**
         * Creates a {@link Severity#WARNING}-severity finding.
         */
        public static ValidationError warning(String field, String message) {
            return new ValidationError(field, Severity.WARNING, message);
        }

        @Override
        public String toString() {
            return "[" + severity + "] legate." + field + ": " + message;
        }
    }
}
