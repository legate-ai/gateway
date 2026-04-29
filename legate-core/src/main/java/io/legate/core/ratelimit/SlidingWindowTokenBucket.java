package io.legate.core.ratelimit;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thread-safe sliding-window token bucket.
 *
 * <p>Tracks token consumption within a rolling window of {@code windowMs} milliseconds.
 * On {@link #tryReserve}, the window is swept to remove expired entries, then the
 * requested tokens are pre-reserved if capacity permits. On {@link #adjust}, the
 * reservation is corrected to the actual token count.</p>
 *
 * <p>This is used in addition to (not instead of) the per-day counter so that a burst
 * of large requests cannot consume the entire daily budget in one minute.</p>
 */
public final class SlidingWindowTokenBucket {

    private record Entry(long timestampMs, long tokens) {}

    private final long limitPerWindow;
    private final long windowMs;
    private final Clock clock;

    private final Deque<Entry> window = new ArrayDeque<>();
    private long windowTotal = 0;

    public SlidingWindowTokenBucket(long limitPerWindow, long windowMs) {
        this(limitPerWindow, windowMs, Clock.systemUTC());
    }

    SlidingWindowTokenBucket(long limitPerWindow, long windowMs, Clock clock) {
        this.limitPerWindow = limitPerWindow;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    /**
     * Attempts to reserve {@code tokens} within the sliding window.
     *
     * @return {@code true} if capacity is available and the reservation was made
     */
    public synchronized boolean tryReserve(long tokens) {
        sweep();
        if (windowTotal + tokens > limitPerWindow) {
            return false;
        }
        window.addLast(new Entry(clock.millis(), tokens));
        windowTotal += tokens;
        return true;
    }

    /**
     * Adjusts the most-recent reservation from {@code reserved} to {@code actual}.
     * If actual > reserved, the extra tokens are added; if actual < reserved, the
     * over-reservation is released.
     */
    public synchronized void adjust(long reserved, long actual) {
        long delta = actual - reserved;
        if (delta == 0) {
            return;
        }
        // Amend the last entry to reflect the actual usage
        if (!window.isEmpty()) {
            Entry last = window.removeLast();
            long corrected = Math.max(0, last.tokens() + delta);
            window.addLast(new Entry(last.timestampMs(), corrected));
            windowTotal = Math.max(0, windowTotal + delta);
        }
    }

    /** Returns the total tokens consumed in the current window. */
    public synchronized long getWindowTotal() {
        sweep();
        return windowTotal;
    }

    private void sweep() {
        long cutoff = clock.millis() - windowMs;
        while (!window.isEmpty() && window.peekFirst().timestampMs() <= cutoff) {
            windowTotal -= window.removeFirst().tokens();
        }
        if (windowTotal < 0) {
            windowTotal = 0;
        }
    }
}
