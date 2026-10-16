package dev.ryvrook.atlas.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The waits of a plugin stop. The plugin manager calls Atlas's shutdown while it holds the write side of its lock
 * for the whole server stop, so every second Atlas waits here is a second the server waits. Atlas tasks are short,
 * so one still running after {@link #GRACE_MILLIS} is stuck, is interrupted, and is left behind after
 * {@link #INTERRUPT_MILLIS} more.
 */
public final class Stops {
    /** How long a stop lets a running Atlas task finish before it interrupts it. */
    public static final long GRACE_MILLIS = 1_000;
    /** How long a stop waits for an interrupted Atlas task. */
    public static final long INTERRUPT_MILLIS = 500;

    private Stops() {
    }

    /**
     * Stops taking work, lets a running task finish for up to {@code graceMillis}, then interrupts it and waits
     * {@code interruptMillis} more. Returns true when every thread of the pool ended.
     */
    public static boolean executor(ExecutorService ex, long graceMillis, long interruptMillis) {
        if (ex == null) {
            return true;
        }
        ex.shutdown();
        try {
            if (ex.awaitTermination(graceMillis, TimeUnit.MILLISECONDS)) {
                return true;
            }
            ex.shutdownNow();
            return ex.awaitTermination(interruptMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
            return ex.isTerminated();
        }
    }

    /** {@link #executor(ExecutorService, long, long)} with the Atlas defaults. */
    public static boolean executor(ExecutorService ex) {
        return executor(ex, GRACE_MILLIS, INTERRUPT_MILLIS);
    }
}
