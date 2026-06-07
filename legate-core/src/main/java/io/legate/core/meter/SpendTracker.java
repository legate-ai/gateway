package io.legate.core.meter;

import io.legate.core.config.spend.BreachAction;
import io.legate.core.config.spend.SpendControlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    private volatile SpendControlConfig config;
    private final ScheduledExecutorService cleanupScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "legate-spend-cleanup");
                t.setDaemon(true);
                return t;
            });

    public SpendTracker(SpendControlConfig config) {
        this.config = config != null ? config : SpendControlConfig.disabled();
        // Evict spend buckets older than 33 days every 6 hours to prevent unbounded map growth
        cleanupScheduler.scheduleAtFixedRate(this::evictOldBuckets, 6, 6, TimeUnit.HOURS);
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

    /**
     * Details about a spend limit breach: which period triggered it, and the amounts.
     */
    public record BreachDetail(String period, BigDecimal currentSpend, BigDecimal limit) {}

    /**
     * Returns breach details if the key has exceeded any spend limit, or {@code null} if within budget.
     * Checks per-key limits before global limits.
     */
    public BreachDetail checkBudget(String keyId) {
        SpendControlConfig.SpendLimitConfig keyLimit = config.perVirtualKey().get(keyId);
        if (keyLimit != null && !keyLimit.isUnlimited()) {
            if (keyLimit.hasDailyLimit()) {
                BigDecimal daily = getDailySpend(keyId);
                if (daily.compareTo(keyLimit.dailyLimitUsd()) >= 0) {
                    return new BreachDetail("daily", daily, keyLimit.dailyLimitUsd());
                }
            }
            if (keyLimit.hasMonthlyLimit()) {
                BigDecimal monthly = getMonthlySpend(keyId);
                if (monthly.compareTo(keyLimit.monthlyLimitUsd()) >= 0) {
                    return new BreachDetail("monthly", monthly, keyLimit.monthlyLimitUsd());
                }
            }
        }

        SpendControlConfig.SpendLimitConfig globalLimit = config.global();
        if (globalLimit != null && !globalLimit.isUnlimited()) {
            if (globalLimit.hasDailyLimit()) {
                BigDecimal daily = getDailySpend("global");
                if (daily.compareTo(globalLimit.dailyLimitUsd()) >= 0) {
                    return new BreachDetail("daily", daily, globalLimit.dailyLimitUsd());
                }
            }
            if (globalLimit.hasMonthlyLimit()) {
                BigDecimal monthly = getMonthlySpend("global");
                if (monthly.compareTo(globalLimit.monthlyLimitUsd()) >= 0) {
                    return new BreachDetail("monthly", monthly, globalLimit.monthlyLimitUsd());
                }
            }
        }

        return null;
    }

    /**
     * Updates the spend control configuration without restart (hot-reload support).
     */
    public void reload(SpendControlConfig newConfig) {
        this.config = newConfig != null ? newConfig : SpendControlConfig.disabled();
        log.info("SpendTracker configuration reloaded.");
    }

    // -------------------------------------------------------------------------

    /**
     * Removes daily buckets older than 33 days and monthly buckets older than 2 months.
     * Called periodically to prevent unbounded map growth.
     */
    private void evictOldBuckets() {
        LocalDate cutoffDaily = LocalDate.now(ZoneOffset.UTC).minusDays(33);
        LocalDate cutoffMonthly = LocalDate.now(ZoneOffset.UTC).minusMonths(2);

        dailySpend.keySet().removeIf(key -> {
            int sep = key.lastIndexOf(':');
            if (sep < 0) return false;
            try {
                LocalDate date = LocalDate.parse(key.substring(sep + 1));
                return date.isBefore(cutoffDaily);
            } catch (Exception e) {
                return false;
            }
        });

        monthlySpend.keySet().removeIf(key -> {
            int sep = key.lastIndexOf(':');
            if (sep < 0) return false;
            try {
                String ym = key.substring(sep + 1);  // "YYYY-MM"
                LocalDate monthStart = LocalDate.parse(ym + "-01");
                return monthStart.isBefore(cutoffMonthly);
            } catch (Exception e) {
                return false;
            }
        });

        log.debug("SpendTracker eviction complete: {} daily buckets, {} monthly buckets retained.",
                dailySpend.size(), monthlySpend.size());
    }

    private String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private String thisMonth() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return today.getYear() + "-" + String.format("%02d", today.getMonthValue());
    }
}
