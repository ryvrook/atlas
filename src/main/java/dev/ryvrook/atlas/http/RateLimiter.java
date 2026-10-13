package dev.ryvrook.atlas.http;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Requests per minute and address, as a token bucket per key. A bucket holds up to one minute of
 * requests and refills continuously, so a browser that loads many tiles at once is not cut off as
 * long as its average stays under the limit. Keys come from LinkService.rateKey, so IPv6 clients
 * share one bucket per /64.
 */
public final class RateLimiter {
    /** Buckets kept before idle ones are pruned on the next request. */
    static final int PRUNE_AT = 20_000;
    private static final long IDLE_MS = 2 * 60_000L;

    private static final class Bucket {
        double tokens;
        long last;
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    /** When the last prune from a request ran, so a map full of busy keys is not walked on every request. */
    private volatile long prunedAt;

    public RateLimiter() {
        this(System::currentTimeMillis);
    }

    RateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Takes one request from the key's bucket. Returns 0 when allowed, otherwise the whole seconds
     * until the next request would be allowed, at least 1. A limit of 0 or less allows everything.
     */
    public long acquire(String key, int perMinute) {
        if (perMinute <= 0) {
            return 0;
        }
        long now = clock.getAsLong();
        double rate = perMinute / 60_000.0;
        long[] wait = new long[1];
        buckets.compute(key, (k, b) -> {
            if (b == null) {
                b = new Bucket();
                b.tokens = perMinute;
                b.last = now;
            } else {
                b.tokens = Math.min(perMinute, b.tokens + Math.max(0, now - b.last) * rate);
                b.last = now;
            }
            if (b.tokens >= 1) {
                b.tokens -= 1;
            } else {
                wait[0] = Math.max(1, (long) Math.ceil((1 - b.tokens) / rate / 1000.0));
            }
            return b;
        });
        if (buckets.size() > PRUNE_AT && now - prunedAt >= 1_000) {
            prunedAt = now;
            prune();
        }
        return wait[0];
    }

    /** Drops buckets that have not been used for two minutes, by then full again anyway. */
    public void prune() {
        long now = clock.getAsLong();
        buckets.entrySet().removeIf(e -> now - e.getValue().last > IDLE_MS);
    }

    int size() {
        return buckets.size();
    }
}
