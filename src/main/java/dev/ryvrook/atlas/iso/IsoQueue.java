package dev.ryvrook.atlas.iso;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * The bookkeeping of the isometric render queue, kept apart from the render thread so it can be
 * tested on its own. Every method holds the lock of the queue.
 *
 * <p>Browser views are counted per address under the key of the rate limiter. One address holds at
 * most isoViewsPerAddress views (never more than the whole queue), at most isoLargeViewsPerAddress
 * of them large and not yet started. Both are read on every offer, so a reload applies at once. A new view
 * from an address at its cap replaces the oldest view of that address that has not started, so a
 * person turning the view around never fills the queue with views nobody looks at any more. The
 * whole queue holds at most queueLimit browser views, and BUSY is only answered once that many are
 * waiting with every address at its own cap. At most largeLimit large browser views are admitted at
 * once, a further one waits as deferred and is admitted when one of them finishes or is replaced.
 *
 * <p>Views are drawn one at a time, the running one first, then browser views before commands and
 * snapshots, each in the order they were asked for.
 */
final class IsoQueue {
    static final int DEFAULT_PER_ADDRESS = 4;
    static final int DEFAULT_LARGE_PER_ADDRESS = 2;

    private static final AtomicLong SEQ = new AtomicLong();

    /** One queued or running render. address is null for commands and snapshots. */
    static class Entry {
        final String key;
        final IsoView view;
        final String address;
        final long seq = SEQ.incrementAndGet();
        private long startedAt;
        private boolean deferred;
        private boolean cancelled;

        Entry(String key, IsoView view, String address) {
            this.key = key;
            this.view = view;
            this.address = address;
        }

        boolean browser() {
            return address != null;
        }

        boolean large() {
            return view.large();
        }

        synchronized long startedAt() {
            return startedAt;
        }

        synchronized boolean deferred() {
            return deferred;
        }

        synchronized boolean cancelled() {
            return cancelled;
        }
    }

    /** How an offer was answered. */
    enum Outcome { QUEUED, BUSY, LIMITED }

    /**
     * The answer to an offer. cancelled lists the views of the same address it replaced, promoted
     * the deferred views that may run now and waitSeconds the seconds of the rate limit when LIMITED.
     */
    record Offer(Outcome outcome, List<Entry> cancelled, List<Entry> promoted, long waitSeconds) {
    }

    /** The running one first, then browser views, then the order they were asked for. */
    static final Comparator<Entry> ORDER = Comparator.<Entry>comparingInt(e -> e.startedAt() > 0 ? 0 : 1)
            .thenComparingInt(e -> e.browser() ? 0 : 1)
            .thenComparingLong(e -> e.seq);

    private final int queueLimit;
    private final int largeLimit;
    private final IntSupplier perAddress;
    private final IntSupplier largePerAddress;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long cancelledTotal;

    IsoQueue(int queueLimit, int largeLimit) {
        this(queueLimit, largeLimit, () -> DEFAULT_PER_ADDRESS, () -> DEFAULT_LARGE_PER_ADDRESS);
    }

    IsoQueue(int queueLimit, int largeLimit, IntSupplier perAddress, IntSupplier largePerAddress) {
        this.queueLimit = queueLimit;
        this.largeLimit = largeLimit;
        this.perAddress = perAddress;
        this.largePerAddress = largePerAddress;
    }

    /** Views one address may hold now, at least 1 and never more than the whole queue. */
    int perAddressCap() {
        return Math.max(1, Math.min(queueLimit, perAddress.getAsInt()));
    }

    /** Large views one address may hold waiting now, at least 1 and never more than its views. */
    int largePerAddressCap() {
        return Math.max(1, Math.min(perAddressCap(), largePerAddress.getAsInt()));
    }

    synchronized Entry get(String key) {
        return entries.get(key);
    }

    /**
     * Offers a browser view. The views it would replace and the global cap are worked out first,
     * then admit is asked whether the address may start a render, and only then is anything
     * changed. admit returns 0 when allowed, otherwise the seconds to wait.
     */
    synchronized Offer offer(Entry e, LongSupplier admit) {
        if (!e.browser()) {
            throw new IllegalArgumentException("only browser views are offered");
        }
        List<Entry> mine = new ArrayList<>();
        for (Entry o : entries.values()) {
            if (e.address.equals(o.address)) {
                mine.add(o);
            }
        }
        mine.sort(Comparator.comparingLong(o -> o.seq));
        int cap = perAddressCap();
        int largeCap = largePerAddressCap();
        List<Entry> cancel = new ArrayList<>();
        if (e.large()) {
            int waitingLarge = 0;
            for (Entry o : mine) {
                if (o.large() && o.startedAt == 0) {
                    waitingLarge++;
                }
            }
            for (Entry o : mine) {
                if (waitingLarge < largeCap) {
                    break;
                }
                if (o.large() && o.startedAt == 0) {
                    cancel.add(o);
                    waitingLarge--;
                }
            }
        }
        for (Entry o : mine) {
            if (mine.size() - cancel.size() < cap) {
                break;
            }
            if (o.startedAt == 0 && !cancel.contains(o)) {
                cancel.add(o);
            }
        }
        if (browserCount() - cancel.size() >= queueLimit || mine.size() - cancel.size() >= cap) {
            return new Offer(Outcome.BUSY, List.of(), List.of(), 0);
        }
        long wait = admit.getAsLong();
        if (wait > 0) {
            return new Offer(Outcome.LIMITED, List.of(), List.of(), wait);
        }
        List<Entry> promoted = new ArrayList<>();
        for (Entry o : cancel) {
            synchronized (o) {
                o.cancelled = true;
            }
            cancelledTotal++;
            remove(o, promoted);
        }
        synchronized (e) {
            e.deferred = e.large() && admittedLarge() >= largeLimit;
        }
        entries.put(e.key, e);
        return new Offer(Outcome.QUEUED, List.copyOf(cancel), promoted, 0);
    }

    /** Adds a command or snapshot render, without any limit. */
    synchronized void addDirect(Entry e) {
        entries.put(e.key, e);
    }

    /** Marks the entry as running. False when it was replaced or removed and must not run. */
    synchronized boolean start(Entry e, long now) {
        if (entries.get(e.key) != e) {
            return false;
        }
        synchronized (e) {
            if (e.cancelled) {
                return false;
            }
            e.startedAt = Math.max(1, now);
        }
        return true;
    }

    /** Removes a finished entry and returns the deferred views that may run now. */
    synchronized List<Entry> finish(Entry e) {
        List<Entry> promoted = new ArrayList<>();
        remove(e, promoted);
        return promoted;
    }

    private void remove(Entry e, List<Entry> promoted) {
        if (!entries.remove(e.key, e)) {
            return;
        }
        if (!e.browser() || !e.large() || e.deferred()) {
            return;
        }
        while (admittedLarge() < largeLimit) {
            Entry next = null;
            for (Entry o : entries.values()) {
                if (o.deferred() && (next == null || o.seq < next.seq)) {
                    next = o;
                }
            }
            if (next == null) {
                return;
            }
            synchronized (next) {
                next.deferred = false;
            }
            promoted.add(next);
        }
    }

    /** Every entry, in the order they are drawn. */
    synchronized List<Entry> all() {
        List<Entry> l = new ArrayList<>(entries.values());
        l.sort(ORDER);
        return l;
    }

    /** The entries drawn before e, the running one included, and e itself. */
    synchronized List<Entry> ahead(Entry e) {
        List<Entry> l = new ArrayList<>();
        for (Entry o : entries.values()) {
            if (o == e || ORDER.compare(o, e) < 0) {
                l.add(o);
            }
        }
        return l;
    }

    /** The entries a browser view asked for now would wait behind. */
    synchronized List<Entry> aheadOfNew() {
        List<Entry> l = new ArrayList<>();
        for (Entry o : entries.values()) {
            if (o.browser() || o.startedAt() > 0) {
                l.add(o);
            }
        }
        return l;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized int browserCount() {
        int n = 0;
        for (Entry o : entries.values()) {
            if (o.browser()) {
                n++;
            }
        }
        return n;
    }

    synchronized int largeCount() {
        int n = 0;
        for (Entry o : entries.values()) {
            if (o.large()) {
                n++;
            }
        }
        return n;
    }

    /** Large browser views admitted, queued or running, the ones the large limit counts. */
    private int admittedLarge() {
        int n = 0;
        for (Entry o : entries.values()) {
            if (o.browser() && o.large() && !o.deferred()) {
                n++;
            }
        }
        return n;
    }

    synchronized int deferredCount() {
        int n = 0;
        for (Entry o : entries.values()) {
            if (o.deferred()) {
                n++;
            }
        }
        return n;
    }

    synchronized int addresses() {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (Entry o : entries.values()) {
            if (o.browser()) {
                s.add(o.address);
            }
        }
        return s.size();
    }

    synchronized int perAddress(String address) {
        int n = 0;
        for (Entry o : entries.values()) {
            if (address.equals(o.address)) {
                n++;
            }
        }
        return n;
    }

    /** Views replaced by a newer view of the same address since the start. */
    synchronized long cancelledTotal() {
        return cancelledTotal;
    }
}
