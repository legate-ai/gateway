package io.legate.core.meter;

import io.legate.core.config.spend.BreachAction;
import io.legate.core.config.spend.SpendControlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory spend tracker that accumulates per-key and global USD spend per day and per month.
 *
 * <p>Daily totals reset at UTC midnight. Monthly totals reset on the first day of each month.</p>
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} with {@link AtomicReference} per bucket.</p>
 *
 * <p>Phase 4 will replace this with a PostgreSQL-backed implementation for distributed deployments.</p>
 */
public class SpendTracker {

    private static final Logger log = LoggerFactory.getLogger(SpendTracker.class);

    /**
     * Spend bucket keyed by "keyId:YYYY-MM-DD" for daily totals.
     */
    private final ConcurrentHashMap<String, AtomicReference<BigDecimal>> dailySpend = new ConcurrentHashMap<>();
    /**
     * Spend bucket keyed by "keyId:YYYY-MM" for monthly totals.
     */
    private final ConcurrentHashMap<String, AtomicReference<BigDecimal>> monthlySpend = new ConcurrentHashMap<>();

    private final SpendControlConfig config;

    public SpendTracker(SpendControlConfig config) {
        this.config = config != null ? config : SpendControlConfig.disabled();
    }

    /**
     * Records spend for a virtual key.
     *
     * @param keyId   the virtual key ID (or {@code "global"} for aggregate tracking)
     * @param costUsd the cost in USD to add; must be non-negative
     */
    public void recordSpend(String keyId, BigDecimal costUsd) {
        if (keyId == null || costUsd == null || costUsd.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        String dayKey = keyId + ":" + today();
        String monthKey = keyId + ":" + thisMonth();

        dailySpend.computeIfAbsent(dayKey, k -> new AtomicReference<>(BigDecimal.ZERO))
                .getAndUpdate(v -> v.add(costUsd));
        monthlySpend.computeIfAbsent(monthKey, k -> new AtomicReference<>(BigDecimal.ZERO))
                .getAndUpdate(v -> v.add(costUsd));

        // Also record in global buckets
        if (!"global".equals(keyId)) {
            recordSpend("global", costUsd);
        }
    }

    /**
     * Returns the total spend for a key today (UTC).
     */
    public BigDecimal getDailySpend(String keyId) {
        AtomicReference<BigDecimal> ref = dailySpend.get(keyId + ":" + today());
        return ref != null ? ref.get() : BigDecimal.ZERO;
    }

    /**
     * Returns the total spend for a key this month (UTC).
     */
    public BigDecimal getMonthlySpend(String keyId) {
        AtomicReference<BigDecimal> ref = monthlySpend.get(keyId + ":" + thisMonth());
        return ref != null ? ref.get() : BigDecimal.ZERO;
    }

    /**
     * Checks whether a virtual key has exceeded its configured spend limits.
     *
     * @param keyId the virtual key ID to check
     * @return {@code true} if the key is over budget (daily OR monthly)
     */
    public boolean isOverBudget(String keyId) {
        // Check per-key limit first
        SpendControlConfig.SpendLimitConfig keyLimit = config.perVirtualKey().get(keyId);
        if (keyLimit != null && !keyLimit.isUnlimited()) {
            if (keyLimit.hasDailyLimit() && getDailySpend(keyId).compareTo(keyLimit.dailyLimitUsd()) >= 0) {
                return true;
            }
            if (keyLimit.hasMonthlyLimit() && getMonthlySpend(keyId).compareTo(keyLimit.monthlyLimitUsd()) >= 0) {
                return true;
            }
        }

        // Check global limit
        SpendControlConfig.SpendLimitConfig globalLimit = config.global();
        if (globalLimit != null && !globalLimit.isUnlimited()) {
            if (globalLimit.hasDailyLimit() && getDailySpend("global").compareTo(globalLimit.dailyLimitUsd()) >= 0) {
                return true;
            }
            if (globalLimit.hasMonthlyLimit() && getMonthlySpend("global").compareTo(globalLimit.monthlyLimitUsd()) >= 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns the configured action for a key's budget breach.
     */
    public BreachAction getBreachAction(String keyId) {
        SpendControlConfig.SpendLimitConfig keyLimit = config.perVirtualKey().get(keyId);
        if (keyLimit != null) {
            return keyLimit.actionOnBreach();
        }
        SpendControlConfig.SpendLimitConfig globalLimit = config.global();
        if (globalLimit != null) {
            return globalLimit.actionOnBreach();
        }
        return BreachAction.BLOCK;
    }

    // -------------------------------------------------------------------------

    private String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private String thisMonth() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return today.getYear() + "-" + String.format("%02d", today.getMonthValue());
    }
}
