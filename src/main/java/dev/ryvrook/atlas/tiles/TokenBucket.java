package dev.ryvrook.atlas.tiles;

import java.util.function.LongSupplier;

/**
 * A token bucket for chunk image requests shared by every tile render. It holds at most one
 * minute of tokens and refills continuously. Requests from browsers may take any token left.
 * Background work only takes a token while the bucket is more than half full, so a busy
 * pre-render never starves people looking at the map. A rate of zero means unlimited.
 */
public final class TokenBucket {
    private final LongSupplier clock;
    private int perMinute;
    private double tokens;
    private long updatedAt;

    public TokenBucket(int perMinute) {
        this(perMinute, System::currentTimeMillis);
    }

    public TokenBucket(int perMinute, LongSupplier clock) {
        this.clock = clock;
        this.perMinute = Math.max(0, perMinute);
        this.tokens = this.perMinute;
        this.updatedAt = clock.getAsLong();
    }

    /** Changes the rate. The tokens held are kept but never exceed the new capacity. */
    public synchronized void setRate(int perMinute) {
        int rate = Math.max(0, perMinute);
        if (rate == this.perMinute) {
            return;
        }
        refill();
        boolean wasUnlimited = this.perMinute == 0;
        this.perMinute = rate;
        tokens = wasUnlimited ? rate : Math.min(tokens, rate);
    }

    public synchronized int rate() {
        return perMinute;
    }

    public synchronized boolean unlimited() {
        return perMinute == 0;
    }

    /** Takes one token. Background takes only succeed while more than half the capacity is left. */
    public synchronized boolean tryTake(boolean background) {
        if (perMinute == 0) {
            return true;
        }
        refill();
        double floor = background ? backgroundFloor() : 0;
        if (tokens - 1 < floor) {
            return false;
        }
        tokens -= 1;
        return true;
    }

    /** True when background work may take a token right now. */
    public synchronized boolean backgroundReady() {
        if (perMinute == 0) {
            return true;
        }
        refill();
        return tokens - 1 >= backgroundFloor();
    }

    /**
     * The tokens background work leaves for browsers, half the capacity rounded down. Rounding down
     * keeps a tiny budget such as one per minute usable by background work at all.
     */
    private double backgroundFloor() {
        return perMinute / 2;
    }

    /** Whole tokens left, or -1 when unlimited. */
    public synchronized int available() {
        if (perMinute == 0) {
            return -1;
        }
        refill();
        return (int) tokens;
    }

    private void refill() {
        long now = clock.getAsLong();
        long elapsed = now - updatedAt;
        updatedAt = now;
        if (elapsed > 0) {
            tokens = Math.min(perMinute, tokens + elapsed * (perMinute / 60_000.0));
        }
    }
}
