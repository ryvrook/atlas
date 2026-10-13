package dev.ryvrook.atlas.tiles;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Lets a repeated failure through the first time and then at most once per interval, counting
 * what was held back in between so the next line can say how often it happened.
 */
public final class LogThrottle {
    private final long intervalMs;
    private final LongSupplier clock;
    private final Map<String, long[]> seen = new ConcurrentHashMap<>();

    public LogThrottle(long intervalMs) {
        this(intervalMs, System::currentTimeMillis);
    }

    public LogThrottle(long intervalMs, LongSupplier clock) {
        this.intervalMs = intervalMs;
        this.clock = clock;
    }

    /**
     * Returns -1 when the line should be held back, otherwise the number of times it was held back
     * since it was last let through.
     */
    public long allow(String key) {
        long now = clock.getAsLong();
        long[] slot = seen.computeIfAbsent(key, k -> new long[]{Long.MIN_VALUE, 0});
        synchronized (slot) {
            if (slot[0] != Long.MIN_VALUE && now - slot[0] < intervalMs) {
                slot[1]++;
                return -1;
            }
            long held = slot[1];
            slot[0] = now;
            slot[1] = 0;
            return held;
        }
    }
}
