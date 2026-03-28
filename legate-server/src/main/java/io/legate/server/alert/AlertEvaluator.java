package io.legate.server.alert;

import io.legate.core.config.LegateConfig;
import io.legate.core.config.alert.AlertConfig;
import io.legate.core.event.CompletionEvent;
import io.legate.core.event.EventBus;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Evaluates alert rules against a sliding window of completion events.
 *
 * <h3>Design</h3>
 * <p>Alert metric computation is delegated to {@link MetricEvaluator} strategy
 * beans, one per metric name. All evaluators are discovered via Spring's
 * dependency injection and stored in a {@code Map<String, MetricEvaluator>}
 * keyed by {@link MetricEvaluator#metricName()}. Adding a new alertable metric
 * requires only a new {@code @Component} implementing {@link MetricEvaluator}
 * — this class does not need to change (Open/Closed Principle).</p>
 *
 * <h3>Daily cost reset</h3>
 * <p>The daily cost accumulator ({@code dailyCostUsd}) resets to zero at UTC
 * midnight by comparing the current date to the last recorded date on every
 * {@link CompletionEvent}. This ensures {@code daily_cost_usd} alert conditions
 * reflect actual calendar-day spend rather than an ever-growing total.</p>
 *
 * <h3>Deduplication</h3>
 * <p>Each alert fires at most once per configured evaluation window to prevent
 * alert storms during sustained incidents.</p>
 */
@Component
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    private static final Duration MAX_WINDOW  = Duration.ofMinutes(10);
    private static final int      MAX_EVENTS  = 100_000;
    private static final int      CONDITION_PARTS = 3;

    private final LegateConfig              legateConfig;
    private final EventBus                  eventBus;
    private final WebhookDispatcher         webhookDispatcher;
    private final Map<String, MetricEvaluator> evaluators;

    /** Sliding window of recent completion events. */
    private final ConcurrentLinkedDeque<CompletionEvent> windowEvents = new ConcurrentLinkedDeque<>();

    /** Running daily cost accumulator — resets at UTC midnight. */
    private final AtomicReference<BigDecimal> dailyCostUsd = new AtomicReference<>(BigDecimal.ZERO);

    /** Last UTC calendar date for which cost was accumulated — used for midnight reset detection. */
    private volatile LocalDate lastCostDate = LocalDate.now(ZoneOffset.UTC);

    /** Deduplication map: alert name → timestamp when it last fired. */
    private final Map<String, Instant> lastFiredByAlert = new ConcurrentHashMap<>();

    public AlertEvaluator(
        LegateConfig legateConfig,
        EventBus eventBus,
        WebhookDispatcher webhookDispatcher,
        List<MetricEvaluator> metricEvaluators
    ) {
        this.legateConfig      = legateConfig;
        this.eventBus          = eventBus;
        this.webhookDispatcher = webhookDispatcher;
        this.evaluators        = metricEvaluators.stream()
            .collect(Collectors.toUnmodifiableMap(MetricEvaluator::metricName, Function.identity()));
    }

    @PostConstruct
    public void init() {
        eventBus.subscribe(CompletionEvent.class, this::onCompletion);
        log.info("AlertEvaluator started — {} rule(s), {} metric evaluator(s): {}",
            legateConfig.alerts() != null ? legateConfig.alerts().size() : 0,
            evaluators.size(),
            evaluators.keySet());
    }

    // ── Scheduled evaluation ──────────────────────────────────────────────────

    @Scheduled(fixedDelay = 30_000)
    public void evaluate() {
        List<AlertConfig> alerts = legateConfig.alerts();
        if (alerts == null || alerts.isEmpty()) {
            return;
        }

        pruneExpiredEvents();

        for (AlertConfig alert : alerts) {
            try {
                evaluateAlert(alert);
            } catch (Exception e) {
                log.warn("Error evaluating alert '{}': {}", alert.name(), e.getMessage());
            }
        }
    }

    // ── Event handler ─────────────────────────────────────────────────────────

    private void onCompletion(CompletionEvent event) {
        resetDailyCostIfNewDay();

        if (event.estimatedCostUsd() != null) {
            dailyCostUsd.updateAndGet(current -> current.add(event.estimatedCostUsd()));
        }

        windowEvents.addLast(event);
        if (windowEvents.size() > MAX_EVENTS) {
            windowEvents.pollFirst();
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void resetDailyCostIfNewDay() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(lastCostDate)) {
            dailyCostUsd.set(BigDecimal.ZERO);
            lastCostDate = today;
            log.debug("AlertEvaluator: daily cost accumulator reset for {}", today);
        }
    }

    private void pruneExpiredEvents() {
        Instant cutoff = Instant.now().minus(MAX_WINDOW);
        while (!windowEvents.isEmpty() && windowEvents.peekFirst().timestamp().isBefore(cutoff)) {
            windowEvents.pollFirst();
        }
    }

    private void evaluateAlert(AlertConfig alert) {
        if (StringUtils.isBlank(alert.name()) || StringUtils.isBlank(alert.condition())) {
            return;
        }

        String[] parts = alert.condition().trim().split("\\s+", CONDITION_PARTS);
        if (parts.length != CONDITION_PARTS) {
            log.warn("Invalid alert condition syntax for '{}': '{}' (expected: <metric> <op> <threshold>)",
                alert.name(), alert.condition());
            return;
        }

        String metricName = parts[0];
        String operator   = parts[1];
        double threshold;
        try {
            threshold = Double.parseDouble(parts[2]);
        } catch (NumberFormatException e) {
            log.warn("Non-numeric threshold in alert '{}': '{}'", alert.name(), parts[2]);
            return;
        }

        MetricEvaluator evaluator = evaluators.get(metricName);
        if (evaluator == null) {
            log.warn("No MetricEvaluator registered for metric '{}' in alert '{}'", metricName, alert.name());
            return;
        }

        Duration window = alert.window() != null ? alert.window() : Duration.ofMinutes(5);
        Instant  windowStart = Instant.now().minus(window);
        List<CompletionEvent> inWindow = windowEvents.stream()
            .filter(e -> e.timestamp().isAfter(windowStart))
            .toList();

        double current  = evaluator.compute(inWindow, window, dailyCostUsd.get());
        boolean breached = applyOperator(operator, current, threshold);

        if (breached && shouldFire(alert.name(), window)) {
            lastFiredByAlert.put(alert.name(), Instant.now());
            log.warn("Alert '{}' breached — metric={} operator={} current={} threshold={}",
                alert.name(), metricName, operator, current, threshold);
            webhookDispatcher.dispatch(alert, metricName, current);
        }
    }

    private boolean shouldFire(String alertName, Duration window) {
        Instant lastFired = lastFiredByAlert.get(alertName);
        return lastFired == null || Instant.now().isAfter(lastFired.plus(window));
    }

    private boolean applyOperator(String operator, double current, double threshold) {
        return switch (operator) {
            case ">"  -> current > threshold;
            case ">=" -> current >= threshold;
            case "<"  -> current < threshold;
            case "<=" -> current <= threshold;
            case "==" -> Double.compare(current, threshold) == 0;
            default   -> {
                log.warn("Unknown alert operator '{}' — condition will never fire", operator);
                yield false;
            }
        };
    }
}
