package dev.ryvrook.atlas.markers;

import dev.ryvrook.atlas.api.MarkerEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The marker listeners of other plugins and the queue that feeds them. Events are queued on the thread that
 * noticed the change and handed to the listeners by one drain task at a time on the executor from
 * {@link #start(Executor)}, the Atlas scheduler, so listeners see them in order and never on a world thread.
 * A listener that throws is logged at most once a minute and never stops Atlas or the other listeners.
 */
final class MarkerEvents {
    static final long FAILURE_LOG_MS = 60_000;

    private final List<Consumer<MarkerEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<MarkerEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private volatile Executor executor;
    private volatile long lastFailureLog;

    /** Listeners run on the executor from now on. */
    void start(Executor scheduler) {
        executor = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Drops every listener and every event not yet delivered. */
    void stop() {
        executor = null;
        listeners.clear();
        queue.clear();
    }

    Runnable add(Consumer<MarkerEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Queues an event for the listeners. Returns at once, nothing runs on the caller's thread. */
    void fire(MarkerEvent event) {
        Objects.requireNonNull(event, "event");
        if (executor == null || listeners.isEmpty()) {
            return;
        }
        queue.add(event);
        schedule();
    }

    private void schedule() {
        Executor ex = executor;
        if (ex == null || !draining.compareAndSet(false, true)) {
            return;
        }
        try {
            ex.execute(this::drain);
        } catch (RejectedExecutionException stopping) {
            draining.set(false);
            queue.clear();
        }
    }

    private void drain() {
        try {
            MarkerEvent e;
            while ((e = queue.poll()) != null) {
                for (Consumer<MarkerEvent> l : listeners) {
                    try {
                        l.accept(e);
                    } catch (Throwable t) {
                        failed(t);
                    }
                }
            }
        } finally {
            draining.set(false);
        }
        // An event queued while the loop was ending would otherwise wait for the next one.
        if (!queue.isEmpty()) {
            schedule();
        }
    }

    /** Logs a listener failure, at most once a minute so a broken plugin does not fill the log. */
    private void failed(Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLog < FAILURE_LOG_MS) {
            return;
        }
        lastFailureLog = now;
        try {
            com.hypixel.hytale.logger.HytaleLogger.get("Atlas").at(Level.WARNING).withCause(t)
                    .log("A marker listener of another plugin failed, further failures are logged once a minute");
        } catch (RuntimeException | LinkageError ignored) {
            // No logging backend, as in unit tests.
        }
    }
}
