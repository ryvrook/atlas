package dev.ryvrook.atlas.http;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {
    @Test
    void allowsABurstOfOneMinuteThenRefills() {
        AtomicLong now = new AtomicLong(1_000_000);
        RateLimiter r = new RateLimiter(now::get);
        for (int i = 0; i < 60; i++) {
            assertEquals(0, r.acquire("a", 60), "request " + i);
        }
        long wait = r.acquire("a", 60);
        assertEquals(1, wait);
        assertEquals(0, r.acquire("b", 60));
        now.addAndGet(1_000);
        assertEquals(0, r.acquire("a", 60));
        assertTrue(r.acquire("a", 60) >= 1);
    }

    @Test
    void retryAfterGrowsWithLowLimits() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter r = new RateLimiter(now::get);
        assertEquals(0, r.acquire("a", 2));
        assertEquals(0, r.acquire("a", 2));
        assertEquals(30, r.acquire("a", 2));
    }

    @Test
    void zeroMeansUnlimited() {
        RateLimiter r = new RateLimiter(() -> 0);
        for (int i = 0; i < 10_000; i++) {
            assertEquals(0, r.acquire("a", 0));
        }
        assertEquals(0, r.size());
    }

    @Test
    void idleBucketsArePruned() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter r = new RateLimiter(now::get);
        r.acquire("a", 10);
        r.acquire("b", 10);
        now.set(3 * 60_000);
        r.acquire("c", 10);
        r.prune();
        assertEquals(1, r.size());
    }
}
