package dev.ryvrook.atlas.tiles;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketTest {
    @Test
    void startsFullAndEmptiesForRequests() {
        AtomicLong now = new AtomicLong(1_000);
        TokenBucket b = new TokenBucket(60, now::get);
        for (int i = 0; i < 60; i++) {
            assertTrue(b.tryTake(false), "token " + i);
        }
        assertFalse(b.tryTake(false));
        assertEquals(0, b.available());
    }

    @Test
    void backgroundStopsAtHalf() {
        AtomicLong now = new AtomicLong(0);
        TokenBucket b = new TokenBucket(10, now::get);
        int taken = 0;
        while (b.tryTake(true)) {
            taken++;
        }
        assertEquals(5, taken);
        assertFalse(b.backgroundReady());
        // Requests still get the lower half.
        assertTrue(b.tryTake(false));
        assertEquals(4, b.available());
    }

    @Test
    void refillsOverTimeUpToCapacity() {
        AtomicLong now = new AtomicLong(0);
        TokenBucket b = new TokenBucket(60, now::get);
        while (b.tryTake(false)) {
            // Drain.
        }
        now.addAndGet(10_000);
        assertEquals(10, b.available());
        now.addAndGet(10 * 60_000);
        assertEquals(60, b.available());
    }

    @Test
    void tinyBudgetStillServesBackgroundWork() {
        AtomicLong now = new AtomicLong(0);
        TokenBucket b = new TokenBucket(1, now::get);
        // Half of one token rounds down to nothing kept back, so background work can ever start.
        assertTrue(b.backgroundReady());
        assertTrue(b.tryTake(true));
        assertFalse(b.backgroundReady());
        now.addAndGet(60_000);
        assertTrue(b.backgroundReady());
    }

    @Test
    void zeroIsUnlimited() {
        TokenBucket b = new TokenBucket(0, () -> 0);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(b.tryTake(true));
        }
        assertEquals(-1, b.available());
        assertTrue(b.unlimited());
    }

    @Test
    void rateChangeKeepsTokensWithinCapacity() {
        AtomicLong now = new AtomicLong(0);
        TokenBucket b = new TokenBucket(100, now::get);
        b.setRate(10);
        assertEquals(10, b.available());
        b.setRate(0);
        assertTrue(b.tryTake(false));
        b.setRate(20);
        assertEquals(20, b.available());
    }
}
