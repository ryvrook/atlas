package dev.ryvrook.atlas.auth;

import dev.ryvrook.atlas.api.SessionApi;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The SessionApi object other plugins get from SessionApi.get(). Every call goes to the LinkService, so
 * sessions it takes in are written by the usual save of sessions.json. Changes the LinkService reports
 * are queued and handed to the listeners by one drain task at a time on the executor from
 * {@link #start(Executor)}, the Atlas scheduler, so listeners see them in order and never on the
 * caller's thread.
 */
public final class SessionService extends SessionApi {
    private final LinkService links;
    private final Supplier<Set<UUID>> hidden;
    private final List<Consumer<SessionEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<SessionEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private volatile Executor executor;

    public SessionService(LinkService links, Supplier<Set<UUID>> hidden) {
        this.links = Objects.requireNonNull(links, "links");
        this.hidden = Objects.requireNonNull(hidden, "hidden");
        links.onEvent(this::queue);
    }

    /** Publishes the API. Listeners run on the executor from now on. */
    public void start(Executor scheduler) {
        executor = Objects.requireNonNull(scheduler, "scheduler");
        enable(this);
    }

    /** Withdraws the API and drops every listener and every change not yet delivered. */
    public void stop() {
        disable(this);
        executor = null;
        listeners.clear();
        queue.clear();
    }

    /** True while the API is published. */
    public boolean running() {
        return executor != null && SessionApi.get() == this;
    }

    @Override
    public String issueCode(UUID uuid, String name) {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid must not be null");
        }
        return links.issueCode(uuid, name);
    }

    @Override
    public Linked redeem(String code) {
        LinkService.Linked linked = links.redeem(code);
        return linked == null ? null : new Linked(linked.token(), toApi(linked.session()));
    }

    @Override
    public Session resolve(String tokenHash) {
        LinkService.Session s = links.sessionByHash(tokenHash);
        return s == null ? null : toApi(s);
    }

    @Override
    public boolean revoke(String tokenHash) {
        return links.revokeHash(tokenHash);
    }

    @Override
    public void adopt(Session session, String tokenHash) {
        if (session == null) {
            return;
        }
        links.adopt(tokenHash, session.uuid(), session.name(), session.createdAt(), session.lastSeen());
    }

    @Override
    public Set<UUID> hidden() {
        Set<UUID> now = hidden.get();
        return now == null ? Set.of() : Set.copyOf(now);
    }

    @Override
    public Runnable onChange(Consumer<SessionEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private static Session toApi(LinkService.Session s) {
        return new Session(s.uuid(), s.name(), s.createdAt(), s.lastSeen());
    }

    /** Called by the LinkService on the thread that made the change. Never runs a listener here. */
    private void queue(SessionEvent e) {
        if (executor == null || listeners.isEmpty()) {
            return;
        }
        queue.add(e);
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
            SessionEvent e;
            while ((e = queue.poll()) != null) {
                for (Consumer<SessionEvent> l : listeners) {
                    try {
                        l.accept(e);
                    } catch (Throwable t) {
                        log(t);
                    }
                }
            }
        } finally {
            draining.set(false);
        }
        // A change queued while the loop was ending would otherwise wait for the next one.
        if (!queue.isEmpty()) {
            schedule();
        }
    }

    private static void log(Throwable t) {
        try {
            com.hypixel.hytale.logger.HytaleLogger.get("Atlas").at(java.util.logging.Level.WARNING).withCause(t)
                    .log("A session listener of another plugin failed");
        } catch (RuntimeException | LinkageError ignored) {
            // No logging backend, as in unit tests.
        }
    }
}
