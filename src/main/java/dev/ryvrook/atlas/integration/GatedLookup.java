package dev.ryvrook.atlas.integration;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A plugin manager lookup that never waits for the manager. The manager answers {@code getPlugin} and
 * {@code getPlugins} under the read side of its lock and holds the write side for a whole server stop and for
 * every {@code /plugin reload}. An Atlas poll that asked then parked on the lock until the manager was done, and on
 * a stop the manager waits for Atlas's own shutdown. So the read lock is only tried. While the manager holds the
 * write lock, and from the moment the server is shutting down, the last answer for that key is given again, or null
 * when there is none yet. The next poll after the manager let go sees the real state. Safe from any thread.
 */
public final class GatedLookup {
    private final Supplier<ReadWriteLock> lock;
    private final BooleanSupplier shuttingDown;
    private final Map<String, Optional<Object>> last = new ConcurrentHashMap<>();

    /**
     * {@code lock} gives the manager's lock, or null when it is not known, and then every lookup goes through.
     * {@code shuttingDown} is true once the server stops, and then no lookup reaches the manager.
     */
    public GatedLookup(Supplier<ReadWriteLock> lock, BooleanSupplier shuttingDown) {
        this.lock = lock;
        this.shuttingDown = shuttingDown;
    }

    /** The answer of {@code lookup} for {@code key}, or the last one seen when asking now would wait. */
    @SuppressWarnings("unchecked")
    public <T> T find(String key, Supplier<T> lookup) {
        if (stopping()) {
            return (T) last(key);
        }
        ReadWriteLock rw;
        try {
            rw = lock == null ? null : lock.get();
        } catch (RuntimeException | LinkageError e) {
            rw = null;
        }
        if (rw == null) {
            return remember(key, lookup.get());
        }
        if (!rw.readLock().tryLock()) {
            return (T) last(key);
        }
        try {
            return remember(key, lookup.get());
        } finally {
            rw.readLock().unlock();
        }
    }

    /** True when the server is shutting down, so a caller can skip work that only matters while it runs. */
    public boolean stopping() {
        try {
            return shuttingDown != null && shuttingDown.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private Object last(String key) {
        Optional<Object> o = last.get(key);
        return o == null ? null : o.orElse(null);
    }

    private <T> T remember(String key, T found) {
        last.put(key, Optional.ofNullable(found));
        return found;
    }
}
