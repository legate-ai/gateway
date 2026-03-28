package io.legate.core.config.spend;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Spend-control configuration for enforcing cost budgets on LLM usage.
 *
 * <p>Costs are estimated after each request using the model pricing table
 * ({@code legate.model-pricing}). Spend is accumulated per virtual key and
 * globally. When a limit is breached, the configured {@link BreachAction} is taken.</p>
 *
 * <h3>Reset schedule</h3>
 * <p>Daily limits reset at midnight UTC (configurable). Monthly limits reset on
 * the first day of each calendar month.</p>
 *
 * <p>YAML example:</p>
 * <pre>{@code
 * legate:
 *   spend-control:
 *     global:
 *       daily-limit-usd: 1000
 *       monthly-limit-usd: 20000
 *       action-on-breach: block
 *     per-virtual-key:
 *       wdn_live_team_a:
 *         daily-limit-usd: 200
 *         action-on-breach: warn
 *       wdn_live_team_b:
 *         monthly-limit-usd: 500
 *         action-on-breach: block
 * }</pre>
 */
public record SpendControlConfig(

    /**
     * Global spend limit applied to all traffic in aggregate.
     * {@code null} means no global budget is enforced.
     */
    SpendLimitConfig global,

    /**
     * Per-virtual-key spend limits. Map key is the virtual key ID.
     * Keys not present here are governed only by the global limit.
     */
    Map<String, SpendLimitConfig> perVirtualKey

) {
    public SpendControlConfig {
        if (perVirtualKey == null) perVirtualKey = Map.of();
    }

    /** Returns a config with no spend controls active. */
    public static SpendControlConfig disabled() {
        return new SpendControlConfig(null, Map.of());
    }

    // -------------------------------------------------------------------------

    /**
     * Budget limit and breach action for one subject (global or a single virtual key).
     *
     * @param dailyLimitUsd   maximum spend per calendar day in USD; {@code null} = unlimited
     * @param monthlyLimitUsd maximum spend per calendar month in USD; {@code null} = unlimited
     * @param actionOnBreach  action taken when either limit is exceeded; default {@link BreachAction#BLOCK}
     */
    public record SpendLimitConfig(
        BigDecimal dailyLimitUsd,
        BigDecimal monthlyLimitUsd,
        BreachAction actionOnBreach
    ) {
        public SpendLimitConfig {
            if (actionOnBreach == null) actionOnBreach = BreachAction.BLOCK;
        }

        /** Returns {@code true} when neither daily nor monthly limit is configured. */
        public boolean isUnlimited() {
            return dailyLimitUsd == null && monthlyLimitUsd == null;
        }

        /** Returns {@code true} when a daily limit is configured. */
        public boolean hasDailyLimit() {
            return dailyLimitUsd != null;
        }

        /** Returns {@code true} when a monthly limit is configured. */
        public boolean hasMonthlyLimit() {
            return monthlyLimitUsd != null;
        }
    }
}
