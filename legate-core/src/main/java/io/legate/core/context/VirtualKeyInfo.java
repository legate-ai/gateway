package io.legate.core.context;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Information about a validated virtual key.
 */
public record VirtualKeyInfo(
        String keyId,
        String teamName,
        List<String> allowedModels,
        List<String> deniedModels,
        RateLimitInfo rateLimits,
        SpendLimitInfo spendLimits,
        Map<String, String> metadata
) {
    /**
     * Rate limit configuration.
     */
    public record RateLimitInfo(
            Integer requestsPerMinute,
            Integer tokensPerDay
    ) {
    }

    /**
     * Spend limit configuration.
     */
    public record SpendLimitInfo(
            BigDecimal dailyLimitUsd,
            BigDecimal monthlyLimitUsd
    ) {
    }

    /**
     * Checks if a model is allowed for this key.
     */
    public boolean isModelAllowed(String model) {
        // If explicitly denied, return false
        if (deniedModels != null && matchesAnyPattern(model, deniedModels)) {
            return false;
        }

        // If allowed list is empty, deny by default
        if (allowedModels == null || allowedModels.isEmpty()) {
            return false;
        }

        // Check if matches any allowed pattern
        return matchesAnyPattern(model, allowedModels);
    }

    private boolean matchesAnyPattern(String model, List<String> patterns) {
        return patterns.stream()
                .anyMatch(pattern -> matchesPattern(model, pattern));
    }

    private boolean matchesPattern(String model, String pattern) {
        if (pattern.equals("*")) {
            return true;
        }

        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return model.startsWith(prefix);
        }

        if (pattern.startsWith("*")) {
            String suffix = pattern.substring(1);
            return model.endsWith(suffix);
        }

        return model.equals(pattern);
    }
}
