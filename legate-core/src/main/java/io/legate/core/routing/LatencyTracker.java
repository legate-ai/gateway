package io.legate.core.routing;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks per-endpoint request latency using an Exponentially Weighted Moving Average (EWMA).
 *
 * <p>EWMA formula: {@code avg = alpha * sample + (1 - alpha) * avg}
 * where {@code alpha} controls how quickly the average responds to changes.
 * A higher alpha (closer to 1) makes the tracker more reactive to recent samples;
 * a lower alpha (closer to 0) gives more weight to historical data.</p>
 *
 * <p>Used by the {@code LEAST_LATENCY} load-balancing strategy to prefer the
 * endpoint with the lowest recent average response time.</p>
 *
 * <p>Thread-safe: per-endpoint state is updated with {@code synchronized} blocks
 * on the per-entry object.</p>
 */
public class LatencyTracker {

    /**
     * Smoothing factor for EWMA. Higher = more reactive to recent samples.
     */
    private static final double ALPHA = 0.3;

    /**
     * Default latency used when no samples are recorded yet (1 second).
     */
    private static final long DEFAULT_LATENCY_MS = 1_000L;

    private final ConcurrentMap<String, LatencyEntry> entries = new ConcurrentHashMap<>();

    /**
     * Records a latency sample for the given endpoint.
     *
     * @param endpoint  the resolved endpoint
     * @param latencyMs observed latency in milliseconds; must be &gt;= 0
     */
    public void record(ResolvedEndpoint endpoint, long latencyMs) {
        if (latencyMs < 0) {
            return;
        }
        entries.computeIfAbsent(endpoint.getKey(), k -> new LatencyEntry())
                .update(latencyMs);
    }

    /**
     * Returns the EWMA latency estimate for the given endpoint in milliseconds.
     * Returns {@link #DEFAULT_LATENCY_MS} if no samples have been recorded.
     *
     * @param endpoint the resolved endpoint
     * @return estimated average latency in milliseconds
     */
    public long getAvgLatencyMs(ResolvedEndpoint endpoint) {
        LatencyEntry entry = entries.get(endpoint.getKey());
        return (entry != null) ? entry.avgMs() : DEFAULT_LATENCY_MS;
    }

    /**
     * Returns {@code true} if any latency data has been recorded for this endpoint.
     */
    public boolean hasSamples(ResolvedEndpoint endpoint) {
        LatencyEntry entry = entries.get(endpoint.getKey());
        return entry != null && entry.sampleCount() > 0;
    }

    /**
     * Clears all recorded latency data.
     */
    public void reset() {
        entries.clear();
    }

    // -------------------------------------------------------------------------

    private static final class LatencyEntry {
        private double ewma = DEFAULT_LATENCY_MS;
        private long samples = 0;

        synchronized void update(long latencyMs) {
            if (samples == 0) {
                ewma = latencyMs;
            } else {
                ewma = ALPHA * latencyMs + (1.0 - ALPHA) * ewma;
            }
            samples++;
        }

        synchronized long avgMs() {
            return Math.max(0L, Math.round(ewma));
        }

        synchronized long sampleCount() {
            return samples;
        }
    }
}
