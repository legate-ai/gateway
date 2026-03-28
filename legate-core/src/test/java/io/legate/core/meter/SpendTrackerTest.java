package io.legate.core.meter;

import io.legate.core.config.spend.BreachAction;
import io.legate.core.config.spend.SpendControlConfig;
import io.legate.core.config.spend.SpendControlConfig.SpendLimitConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpendTrackerTest {

    private SpendTracker trackerWith(BigDecimal dailyLimit, BigDecimal monthlyLimit) {
        var limitConfig = new SpendLimitConfig(dailyLimit, monthlyLimit, BreachAction.BLOCK);
        var config = new SpendControlConfig(limitConfig, Map.of());
        return new SpendTracker(config);
    }

    private SpendTracker trackerWithKeyLimit(String keyId, BigDecimal dailyLimit) {
        var keyLimitConfig = new SpendLimitConfig(dailyLimit, null, BreachAction.BLOCK);
        var config = new SpendControlConfig(null, Map.of(keyId, keyLimitConfig));
        return new SpendTracker(config);
    }

    // ── Basic recording ────────────────────────────────────────────────────────

    @Test
    void getDailySpend_initiallyZero() {
        var tracker = new SpendTracker(SpendControlConfig.disabled());
        assertThat(tracker.getDailySpend("key1")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void recordSpend_accumulatesDailyTotal() {
        var tracker = new SpendTracker(SpendControlConfig.disabled());

        tracker.recordSpend("key1", new BigDecimal("0.10"));
        tracker.recordSpend("key1", new BigDecimal("0.25"));

        assertThat(tracker.getDailySpend("key1")).isEqualByComparingTo(new BigDecimal("0.35"));
    }

    @Test
    void recordSpend_accumulatesMonthlyTotal() {
        var tracker = new SpendTracker(SpendControlConfig.disabled());

        tracker.recordSpend("key1", new BigDecimal("1.00"));
        tracker.recordSpend("key1", new BigDecimal("2.50"));

        assertThat(tracker.getMonthlySpend("key1")).isEqualByComparingTo(new BigDecimal("3.50"));
    }

    @Test
    void recordSpend_alsoTracksGlobal() {
        var tracker = new SpendTracker(SpendControlConfig.disabled());

        tracker.recordSpend("key1", new BigDecimal("1.00"));
        tracker.recordSpend("key2", new BigDecimal("2.00"));

        assertThat(tracker.getDailySpend("global")).isEqualByComparingTo(new BigDecimal("3.00"));
        assertThat(tracker.getMonthlySpend("global")).isEqualByComparingTo(new BigDecimal("3.00"));
    }

    // ── Key isolation ─────────────────────────────────────────────────────────

    @Test
    void differentKeys_haveIndependentBudgets() {
        var tracker = new SpendTracker(SpendControlConfig.disabled());

        tracker.recordSpend("key1", new BigDecimal("1.00"));
        tracker.recordSpend("key2", new BigDecimal("5.00"));

        assertThat(tracker.getDailySpend("key1")).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(tracker.getDailySpend("key2")).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    // ── Null / invalid inputs ─────────────────────────────────────────────────

    @Test
    void recordSpend_withNullKeyId_isNoOp() {
        var tracker = new SpendTracker(SpendControlConfig.disabled());
        tracker.recordSpend(null, new BigDecimal("1.00")); // should not throw
        assertThat(tracker.getDailySpend("null")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void recordSpend_withNullCost_isNoOp() {
        var tracker = new SpendTracker(SpendControlConfig.disabled());
        tracker.recordSpend("key1", null); // should not throw
        assertThat(tracker.getDailySpend("key1")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void recordSpend_withZeroCost_isNoOp() {
        var tracker = new SpendTracker(SpendControlConfig.disabled());
        tracker.recordSpend("key1", BigDecimal.ZERO);
        assertThat(tracker.getDailySpend("key1")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void recordSpend_withNegativeCost_isNoOp() {
        var tracker = new SpendTracker(SpendControlConfig.disabled());
        tracker.recordSpend("key1", new BigDecimal("-1.00"));
        assertThat(tracker.getDailySpend("key1")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── isOverBudget ──────────────────────────────────────────────────────────

    @Test
    void isOverBudget_falseWhenUnderDailyLimit() {
        var tracker = trackerWith(new BigDecimal("10.00"), null);

        tracker.recordSpend("key1", new BigDecimal("5.00"));

        assertThat(tracker.isOverBudget("key1")).isFalse();
    }

    @Test
    void isOverBudget_trueWhenDailyLimitReached() {
        var tracker = trackerWith(new BigDecimal("10.00"), null);

        tracker.recordSpend("key1", new BigDecimal("10.00")); // exactly at limit

        assertThat(tracker.isOverBudget("key1")).isTrue();
    }

    @Test
    void isOverBudget_trueWhenDailyLimitExceeded() {
        var tracker = trackerWith(new BigDecimal("10.00"), null);

        tracker.recordSpend("key1", new BigDecimal("15.00")); // over limit

        assertThat(tracker.isOverBudget("key1")).isTrue();
    }

    @Test
    void isOverBudget_falseWhenNoLimitConfigured() {
        var tracker = new SpendTracker(SpendControlConfig.disabled());

        tracker.recordSpend("key1", new BigDecimal("999999.00"));

        assertThat(tracker.isOverBudget("key1")).isFalse();
    }

    @Test
    void isOverBudget_usesPerKeyLimitIfConfigured() {
        var tracker = trackerWithKeyLimit("key1", new BigDecimal("5.00"));

        tracker.recordSpend("key1", new BigDecimal("5.00")); // exactly at per-key limit

        assertThat(tracker.isOverBudget("key1")).isTrue();
    }

    @Test
    void isOverBudget_monthlyLimit() {
        var tracker = trackerWith(null, new BigDecimal("100.00"));

        tracker.recordSpend("key1", new BigDecimal("100.00")); // exactly at monthly limit

        assertThat(tracker.isOverBudget("key1")).isTrue();
    }

    // ── getBreachAction ───────────────────────────────────────────────────────

    @Test
    void getBreachAction_returnsConfiguredAction() {
        var limitConfig = new SpendLimitConfig(new BigDecimal("10.00"), null, BreachAction.WARN);
        var config = new SpendControlConfig(limitConfig, Map.of());
        var tracker = new SpendTracker(config);

        assertThat(tracker.getBreachAction("anyKey")).isEqualTo(BreachAction.WARN);
    }

    @Test
    void getBreachAction_defaultsToBlock() {
        var tracker = new SpendTracker(SpendControlConfig.disabled());
        assertThat(tracker.getBreachAction("unknownKey")).isEqualTo(BreachAction.BLOCK);
    }

    // ── Thread safety ─────────────────────────────────────────────────────────

    @Test
    void concurrentRecordSpend_isThreadSafe() throws InterruptedException {
        var tracker = new SpendTracker(SpendControlConfig.disabled());
        int threads = 50;
        var latch = new java.util.concurrent.CountDownLatch(threads);

        try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                exec.submit(() -> {
                    tracker.recordSpend("shared-key", new BigDecimal("1.00"));
                    latch.countDown();
                });
            }
            latch.await();
        }

        // Should have recorded exactly 50 * $1.00 = $50.00
        assertThat(tracker.getDailySpend("shared-key")).isEqualByComparingTo(new BigDecimal("50.00"));
    }
}
