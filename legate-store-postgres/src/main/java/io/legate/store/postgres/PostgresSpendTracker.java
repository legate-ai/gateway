package io.legate.store.postgres;

import io.legate.core.config.spend.SpendControlConfig;
import io.legate.core.meter.SpendTracker;
import io.legate.store.postgres.repository.SpendTrackingR2dbcRepository;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.r2dbc.core.DatabaseClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * PostgreSQL-backed spend tracker that extends the in-memory {@link SpendTracker}.
 *
 * <h3>Architecture</h3>
 * <p>Spend amounts are accumulated in the parent class's in-memory maps for
 * fast, lock-free reads. A background Virtual Thread periodically (every 10 seconds)
 * aggregates pending spend entries and persists them with an atomic
 * {@code INSERT … ON CONFLICT … DO UPDATE} upsert, which adds to the DB total
 * without overwriting concurrent writes from other instances.</p>
 *
 * <h3>Startup</h3>
 * <p>On startup, today's spend is loaded from the DB into the in-memory maps via
 * a blocking reactive call executed before the Virtual Thread flush loop starts.
 * The blocking call is safe here because startup runs on a platform thread.</p>
 *
 * <h3>Composite key safety</h3>
 * <p>Aggregation uses a typed {@link SpendKey} record as the map key, eliminating
 * the fragile string-delimiter encoding previously used.</p>
 */
public class PostgresSpendTracker extends SpendTracker implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PostgresSpendTracker.class);
    private static final Duration FLUSH_INTERVAL = Duration.ofSeconds(10);
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(5);

    // ── SQL ───────────────────────────────────────────────────────────────────

    private static final String UPSERT_SQL = """
            INSERT INTO spend_tracking (virtual_key_id, date, daily_spend_usd, updated_at)
            VALUES (:keyId, :date::date, :spend, NOW())
            ON CONFLICT (virtual_key_id, date) DO UPDATE
                SET daily_spend_usd = spend_tracking.daily_spend_usd + EXCLUDED.daily_spend_usd,
                    updated_at      = NOW()
            """;

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final SpendTrackingR2dbcRepository repository;
    private final DatabaseClient db;

    // ── State ─────────────────────────────────────────────────────────────────

    /**
     * Typed composite key for aggregation — eliminates delimiter-based encoding.
     */
    private record SpendKey(String keyId, LocalDate date) {
    }

    private final ConcurrentLinkedQueue<SpendEntry> pendingFlush = new ConcurrentLinkedQueue<>();

    private volatile boolean running;
    private Thread flushThread;

    private record SpendEntry(String keyId, BigDecimal amount, LocalDate date) {
    }

    public PostgresSpendTracker(
            SpendControlConfig config,
            SpendTrackingR2dbcRepository spendTrackingR2dbcRepository,
            DatabaseClient databaseClient
    ) {
        super(config);
        this.repository = Validate.notNull(spendTrackingR2dbcRepository, "repository must not be null");
        this.db = Validate.notNull(databaseClient, "databaseClient must not be null");
    }

    @Override
    public void afterPropertiesSet() {
        loadTodaysSpend();
        running = true;
        flushThread = Thread.ofVirtual()
                .name("legate-spend-flush")
                .start(this::flushLoop);
        log.info("PostgresSpendTracker started");
    }

    @Override
    public void destroy() {
        running = false;
        if (flushThread != null) {
            flushThread.interrupt();
        }
        flushToDb(); // final drain
        log.info("PostgresSpendTracker stopped");
    }

    // ── SpendTracker ──────────────────────────────────────────────────────────

    @Override
    public void recordSpend(String keyId, BigDecimal amount) {
        super.recordSpend(keyId, amount); // update in-memory accumulators
        if (keyId != null && amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            pendingFlush.offer(new SpendEntry(keyId, amount, LocalDate.now(ZoneOffset.UTC)));
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Loads today's spend from the database into the in-memory accumulators.
     * Runs once at startup on a platform thread — blocking is safe here.
     */
    private void loadTodaysSpend() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        try {
            repository.findByDate(today)
                    .collectList()
                    .block(BLOCK_TIMEOUT)
                    .forEach(entity -> {
                        if (entity.virtualKeyId() != null
                                && entity.dailySpendUsd() != null
                                && entity.dailySpendUsd().compareTo(BigDecimal.ZERO) > 0) {
                            super.recordSpend(entity.virtualKeyId(), entity.dailySpendUsd());
                            log.debug("Loaded spend for key '{}': ${}", entity.virtualKeyId(), entity.dailySpendUsd());
                        }
                    });
            log.info("Loaded today's spend data from PostgreSQL");
        } catch (Exception e) {
            log.warn("Could not load today's spend from PostgreSQL — starting from zero: {}", e.getMessage());
        }
    }

    private void flushLoop() {
        while (running) {
            try {
                Thread.sleep(FLUSH_INTERVAL);
                flushToDb();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Spend flush loop encountered an error", e);
            }
        }
    }

    private void flushToDb() {
        if (pendingFlush.isEmpty()) {
            return;
        }

        // Aggregate entries by typed (keyId, date) key — safe with any keyId content
        Map<SpendKey, BigDecimal> aggregated = new HashMap<>();
        SpendEntry entry;
        int count = 0;
        while ((entry = pendingFlush.poll()) != null) {
            SpendKey key = new SpendKey(entry.keyId(), entry.date());
            aggregated.merge(key, entry.amount(), BigDecimal::add);
            count++;
        }

        for (Map.Entry<SpendKey, BigDecimal> e : aggregated.entrySet()) {
            upsertSpend(e.getKey().keyId(), e.getKey().date().toString(), e.getValue());
        }

        if (count > 0) {
            log.debug("Flushed {} spend entries ({} unique keys) to PostgreSQL", count, aggregated.size());
        }
    }

    private void upsertSpend(String keyId, String dateStr, BigDecimal amount) {
        try {
            db.sql(UPSERT_SQL)
                    .bind("keyId", keyId)
                    .bind("date", dateStr)
                    .bind("spend", amount)
                    .fetch()
                    .rowsUpdated()
                    .block(BLOCK_TIMEOUT);
        } catch (Exception e) {
            log.warn("Failed to flush spend for key '{}' on '{}' — re-queuing: {}", keyId, dateStr, e.getMessage());
            // Re-queue so it retries on the next cycle; use today's date since amount is still valid
            pendingFlush.offer(new SpendEntry(keyId, amount, LocalDate.parse(dateStr)));
        }
    }
}
