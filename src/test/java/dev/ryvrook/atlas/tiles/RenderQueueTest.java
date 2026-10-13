package dev.ryvrook.atlas.tiles;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderQueueTest {
    @Test
    void dedupesBoundsPausesAndPurges() throws Exception {
        RenderQueue q = new RenderQueue(3, 1, "test", (t, e) -> { });
        q.pause();
        q.start();
        AtomicInteger runs = new AtomicInteger();
        assertEquals(RenderQueue.Offer.ADDED, q.offer("a", "w1", runs::incrementAndGet));
        assertEquals(RenderQueue.Offer.ALREADY_PENDING, q.offer("a", "w1", runs::incrementAndGet));
        assertEquals(RenderQueue.Offer.ADDED, q.offer("b", "w2", runs::incrementAndGet));
        assertEquals(RenderQueue.Offer.ADDED, q.offer("c", "w2", runs::incrementAndGet));
        assertEquals(RenderQueue.Offer.FULL, q.offer("d", "w1", runs::incrementAndGet));
        RenderQueue.Counts c = q.counts();
        assertEquals(3, c.queued());
        assertTrue(c.paused());
        assertEquals(2, c.perWorld().get("w2"));
        Thread.sleep(200);
        assertEquals(0, runs.get());
        assertEquals(2, q.purge("w2"));
        CountDownLatch done = new CountDownLatch(1);
        q.offer("e", "w1", done::countDown);
        q.resume();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        q.stop(2_000);
        assertEquals(1, runs.get());
        assertEquals(RenderQueue.Offer.STOPPED, q.offer("f", "w1", runs::incrementAndGet));
    }

    @Test
    void cancelDropsPendingAndFailuresDoNotKillWorkers() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        RenderQueue q = new RenderQueue(10, 1, "test", (t, e) -> failures.incrementAndGet());
        q.pause();
        q.start();
        q.offer("x", "w", () -> { });
        q.offer("y", "w", () -> { });
        assertEquals(2, q.cancel());
        q.resume();
        q.offer("boom", "w", () -> {
            throw new IllegalStateException("test");
        });
        CountDownLatch after = new CountDownLatch(1);
        q.offer("after", "w", after::countDown);
        assertTrue(after.await(5, TimeUnit.SECONDS));
        assertEquals(1, failures.get());
        q.stop(2_000);
    }
}
