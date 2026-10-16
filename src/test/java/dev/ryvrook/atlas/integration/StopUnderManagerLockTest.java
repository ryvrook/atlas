package dev.ryvrook.atlas.integration;

import dev.ryvrook.atlas.util.Stops;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin manager holds the write side of its lock for a whole server stop and calls Atlas's shutdown under it.
 * A scheduler poll that looked a plugin up then parked on the read side until the stop was over.
 */
class StopUnderManagerLockTest {
    private static final Object TRACKER = new Object();

    /** A lookup that takes the read lock like PluginManager.getPlugin does. */
    private static Supplier<Object> engineLike(ReentrantReadWriteLock rw) {
        return () -> {
            rw.readLock().lock();
            try {
                return TRACKER;
            } finally {
                rw.readLock().unlock();
            }
        };
    }

    /** Holds the write lock on another thread, as the engine's stop does, until the returned latch counts down. */
    private static CountDownLatch holdWrite(ReentrantReadWriteLock rw) throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            rw.writeLock().lock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                rw.writeLock().unlock();
            }
        }, "manager-stop");
        t.setDaemon(true);
        t.start();
        assertTrue(held.await(5, TimeUnit.SECONDS));
        return release;
    }

    @Test
    void aLookupUnderTheWriteLockAnswersTheLastStateAtOnce() throws Exception {
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        GatedLookup lookup = new GatedLookup(() -> rw, () -> false);
        assertSame(TRACKER, lookup.find("ryvrook:trackerapi", engineLike(rw)));
        CountDownLatch release = holdWrite(rw);
        try {
            long t0 = System.nanoTime();
            assertSame(TRACKER, lookup.find("ryvrook:trackerapi", engineLike(rw)), "the last answer, so no link drops");
            assertNull(lookup.find("ryvrook:dominion", engineLike(rw)), "nothing known yet is null");
            assertTrue(System.nanoTime() - t0 < TimeUnit.MILLISECONDS.toNanos(200), "never waits for the manager");
        } finally {
            release.countDown();
        }
    }

    @Test
    void theOwnerOfTheWriteLockStillLooksUp() {
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        GatedLookup lookup = new GatedLookup(() -> rw, () -> false);
        rw.writeLock().lock();
        try {
            assertSame(TRACKER, lookup.find("ryvrook:trackerapi", engineLike(rw)));
        } finally {
            rw.writeLock().unlock();
        }
    }

    @Test
    void aStoppingServerIsNeverAskedAndAMissingLockIsNotGated() {
        AtomicBoolean stopping = new AtomicBoolean();
        GatedLookup lookup = new GatedLookup(() -> null, stopping::get);
        assertSame(TRACKER, lookup.find("ryvrook:trackerapi", () -> TRACKER), "no lock known, the lookup goes through");
        stopping.set(true);
        assertSame(TRACKER, lookup.find("ryvrook:trackerapi", () -> {
            throw new AssertionError("asked the manager during a stop");
        }));
    }

    @Test
    void stopIsBoundedEvenWithAStuckSchedulerTask() throws Exception {
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(2);
        ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        CountDownLatch release = holdWrite(rw);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Thread> stuck = new AtomicReference<>();
        // An ungated engine lookup, the old behaviour, parks on the read lock and ignores the interrupt.
        ex.execute(() -> {
            stuck.set(Thread.currentThread());
            entered.countDown();
            engineLike(rw).get();
        });
        ex.scheduleWithFixedDelay(() -> { }, 5, 5, TimeUnit.SECONDS);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            long t0 = System.nanoTime();
            assertFalse(Stops.executor(ex), "the stuck task is left behind");
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(ms < 2_000, "stop took " + ms + " ms");
        } finally {
            release.countDown();
        }
        stuck.get().join(5_000);
        assertEquals(Thread.State.TERMINATED, stuck.get().getState());
    }
}
