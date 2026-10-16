package dev.ryvrook.atlas.tiles;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * The background render queue. It is bounded, keeps one pending task per key, never runs two
 * tasks with the same key at once, and can be paused, cancelled or emptied for one world.
 * A task whose key is already pending is not queued again, since the pending one will run with
 * the newest state anyway. A task whose key is running right now is queued once more, so a
 * change that arrived during the run is not lost.
 */
public final class RenderQueue {
    /** One queued unit of work. */
    public record Task(String key, String world, Runnable run) {
    }

    /** Counts at one moment. perWorld holds the pending tasks of each world. */
    public record Counts(int queued, int running, long done, boolean paused, Map<String, Integer> perWorld) {
    }

    private final int capacity;
    private final LinkedHashMap<String, Task> pending = new LinkedHashMap<>();
    private final Set<String> runningKeys = new HashSet<>();
    private final Thread[] threads;
    private final BiConsumer<Task, Throwable> onFailure;
    private long done;
    private boolean paused;
    private boolean stopped;

    public RenderQueue(int capacity, int workers, String threadName, BiConsumer<Task, Throwable> onFailure) {
        this.capacity = capacity;
        this.onFailure = onFailure;
        this.threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            Thread t = new Thread(this::work, threadName + "-" + (i + 1));
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            threads[i] = t;
        }
    }

    public void start() {
        for (Thread t : threads) {
            t.start();
        }
    }

    public enum Offer { ADDED, ALREADY_PENDING, FULL, STOPPED }

    public synchronized Offer offer(String key, String world, Runnable run) {
        if (stopped) {
            return Offer.STOPPED;
        }
        if (pending.containsKey(key)) {
            return Offer.ALREADY_PENDING;
        }
        if (pending.size() >= capacity) {
            return Offer.FULL;
        }
        pending.put(key, new Task(key, world, run));
        notifyAll();
        return Offer.ADDED;
    }

    public synchronized boolean isPending(String key) {
        return pending.containsKey(key);
    }

    public synchronized boolean isQueuedOrRunning(String key) {
        return pending.containsKey(key) || runningKeys.contains(key);
    }

    public synchronized boolean idle() {
        return pending.isEmpty() && runningKeys.isEmpty();
    }

    public synchronized void pause() {
        paused = true;
    }

    public synchronized void resume() {
        paused = false;
        notifyAll();
    }

    public synchronized boolean paused() {
        return paused;
    }

    /** Drops every pending task and returns how many there were. Running tasks finish. */
    public synchronized int cancel() {
        int n = pending.size();
        pending.clear();
        return n;
    }

    /** Drops the pending tasks of one world and returns how many there were. */
    public synchronized int purge(String world) {
        int n = 0;
        Iterator<Task> it = pending.values().iterator();
        while (it.hasNext()) {
            if (it.next().world().equals(world)) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    public synchronized int size() {
        return pending.size();
    }

    public synchronized Counts counts() {
        Map<String, Integer> perWorld = new TreeMap<>();
        for (Task t : pending.values()) {
            perWorld.merge(t.world(), 1, Integer::sum);
        }
        return new Counts(pending.size(), runningKeys.size(), done, paused, perWorld);
    }

    /** True once stop was called. Long tasks check it to end early. */
    public synchronized boolean stopping() {
        return stopped;
    }

    /**
     * Stops taking tasks, drops what is pending, and waits up to the given time for running tasks
     * to finish. Workers still busy after that are interrupted and get {@link dev.ryvrook.atlas.util.Stops#INTERRUPT_MILLIS}
     * more before they are left behind.
     */
    public void stop(long waitMs) {
        synchronized (this) {
            stopped = true;
            pending.clear();
            notifyAll();
        }
        long deadline = System.currentTimeMillis() + waitMs;
        for (Thread t : threads) {
            long left = deadline - System.currentTimeMillis();
            if (left > 0 && t.isAlive()) {
                try {
                    t.join(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        boolean interrupted = false;
        for (Thread t : threads) {
            if (t.isAlive()) {
                t.interrupt();
                interrupted = true;
            }
        }
        if (interrupted) {
            long after = System.currentTimeMillis() + dev.ryvrook.atlas.util.Stops.INTERRUPT_MILLIS;
            for (Thread t : threads) {
                long left = after - System.currentTimeMillis();
                if (left > 0 && t.isAlive()) {
                    try {
                        t.join(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private synchronized Task take() throws InterruptedException {
        while (true) {
            if (stopped) {
                return null;
            }
            if (!paused) {
                Iterator<Task> it = pending.values().iterator();
                while (it.hasNext()) {
                    Task t = it.next();
                    if (!runningKeys.contains(t.key())) {
                        it.remove();
                        runningKeys.add(t.key());
                        return t;
                    }
                }
            }
            wait(1_000);
        }
    }

    private synchronized void finished(Task t) {
        runningKeys.remove(t.key());
        done++;
        notifyAll();
    }

    private void work() {
        while (true) {
            Task t;
            try {
                t = take();
            } catch (InterruptedException e) {
                return;
            }
            if (t == null) {
                return;
            }
            try {
                t.run().run();
            } catch (Throwable e) {
                try {
                    onFailure.accept(t, e);
                } catch (RuntimeException ignored) {
                    // A failing logger must not end the worker.
                }
            } finally {
                finished(t);
            }
        }
    }
}
