package dev.ryvrook.atlas.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import dev.ryvrook.atlas.auth.Viewer;
import dev.ryvrook.atlas.util.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Server sent events. Each client owns a small queue drained by its own handler thread, so a
 * slow browser only drops its own frames. Broadcasts build each payload once per distinct
 * viewer key and share the bytes across every client with that key.
 *
 * <p>When a client's queue is full the oldest data frame is dropped. Control frames (hello,
 * markers, tiles, worlds, leaderboard and snapshot) tell the browser to fetch something again and
 * are never dropped that way. A client that does not take a write for WRITE_TIMEOUT_MS is closed.
 *
 * <p>Every frame carries an id made of this hub's run id, the client id and a sequence number.
 * Each client keeps its last REPLAY frames for GHOST_MS after its connection ends. A browser that
 * reconnects with a Last-Event-ID that is still covered, and as the same viewer for the same world,
 * gets exactly the frames it missed. Any other reconnect starts over with a fresh hello.
 */
public final class SseHub {
    public static final int MAX_CLIENTS = 500;
    /** How often each stream resolves its viewer again, so permission and faction changes apply live. */
    public static final long IDENTITY_REFRESH_MS = 30_000;
    /** A client whose socket takes no bytes for this long is closed. */
    public static final long WRITE_TIMEOUT_MS = 15_000;
    /** Frames queued per client before data frames are dropped. */
    static final int QUEUE = 64;
    /** Frames queued per client before it is closed, when everything queued is a control frame. */
    static final int QUEUE_HARD = 256;
    /** Frames each client keeps for a resume. */
    static final int REPLAY = 200;
    /** Bytes each client keeps for a resume. */
    static final int REPLAY_BYTES = 256 * 1024;
    /** How long a client's frames are kept after its connection ends. */
    static final long GHOST_MS = 60_000;
    /** Ended clients kept for a resume at most. */
    static final int MAX_GHOSTS = 100;
    /** Replay bytes kept across every client. Past it ended clients are forgotten, oldest first. */
    static final long REPLAY_BUDGET_BYTES = 32L * 1024 * 1024;
    /** Events that tell the browser to fetch something again. Never dropped from a full queue. */
    static final Set<String> CONTROL = Set.of("hello", "markers", "tiles", "worlds", "leaderboard", "snapshot",
            "layers", "timeline");
    /** Events whose payload names a world in a top level world field, checked against the world gate. */
    static final Set<String> WORLD_SCOPED = Set.of("kill", "feed", "worldevent", "markers", "tiles", "layers",
            "timeline");

    /** Resolves the viewer of a stream again and describes it for the hello event. */
    public interface Identity {
        Viewer resolve();

        /** The key under which payloads for this viewer may be shared. */
        String key(Viewer viewer);

        /** The JSON body that identifies this viewer. Compared as text to notice identity changes. */
        String hello(Viewer viewer);

        /** The JSON body of the hello event actually sent, which may add live values to the hello body. */
        default String helloFrame(Viewer viewer) {
            return hello(viewer);
        }
    }

    /** One queued event. n orders every frame of this hub, bytes hold the event and data lines. */
    record Frame(long n, byte[] bytes, boolean control) {
    }

    public static final class Client {
        volatile Viewer viewer;
        final String world;
        volatile String key;
        final String address;
        final long id;
        /** Frames waiting for the writer. Guarded by this. */
        final ArrayDeque<Frame> queue = new ArrayDeque<>();
        /** The last frames offered, for a resume. Guarded by this. */
        final ArrayDeque<Frame> history = new ArrayDeque<>();
        long historyBytes;
        /** Every frame with a sequence number above this one is in history. Guarded by this. */
        long coveredFrom;
        /** Highest sequence number written to the current connection. Guarded by this. */
        long lastSent;
        /** True while no connection is attached. Guarded by this. */
        boolean ghost;
        long ghostSince;
        /** True while no connection is attached, so broadcasts can skip work done only for live streams. */
        public synchronized boolean ended() {
            return ghost;
        }

        /** Set when the client is gone for good, after closeAll or when its ghost expired. */
        volatile boolean closed;
        volatile Thread writer;
        /** Start of the write in progress, or 0. */
        volatile long writingSince;
        /** Set by a resume that takes the client over from a connection that has not noticed it is dead. */
        volatile boolean takenOver;

        Client(Viewer viewer, String world, String key, String address, long id, long baseline) {
            this.viewer = viewer;
            this.world = world;
            this.key = key;
            this.address = address;
            this.id = id;
            this.coveredFrom = baseline;
            this.lastSent = baseline;
        }

        /** The key under which broadcasts share payloads between clients, viewer class plus world. */
        public String shareKey() {
            return key + "|" + (world == null ? "" : world);
        }

        public Viewer viewer() {
            return viewer;
        }

        public String world() {
            return world;
        }

        /**
         * Queues a frame and keeps it for a resume. A full queue loses its oldest data frame. When
         * only control frames are queued the queue may grow to QUEUE_HARD, past which the
         * connection is closed so the browser reconnects and resumes from history.
         */
        synchronized void offer(Frame f) {
            if (closed) {
                return;
            }
            history.addLast(f);
            historyBytes += f.bytes().length;
            while (history.size() > REPLAY || (historyBytes > REPLAY_BYTES && history.size() > 1)) {
                Frame old = history.removeFirst();
                historyBytes -= old.bytes().length;
                coveredFrom = old.n();
            }
            if (ghost) {
                return;
            }
            if (queue.size() >= QUEUE) {
                boolean dropped = false;
                for (Iterator<Frame> it = queue.iterator(); it.hasNext(); ) {
                    if (!it.next().control()) {
                        it.remove();
                        dropped = true;
                        break;
                    }
                }
                if (!dropped && !f.control()) {
                    // Everything queued is a control frame, so the new data frame is the one to drop.
                    return;
                }
                if (!dropped && queue.size() >= QUEUE_HARD) {
                    Thread w = writer;
                    if (w != null) {
                        w.interrupt();
                    }
                    return;
                }
            }
            queue.addLast(f);
            notifyAll();
        }

        /** Waits up to ms for frames and takes every queued frame not yet written, or returns an empty list. */
        synchronized List<Frame> take(long ms) throws InterruptedException {
            if (queue.isEmpty() && ms > 0) {
                wait(ms);
            }
            List<Frame> out = new ArrayList<>(queue.size());
            Frame f;
            while ((f = queue.pollFirst()) != null) {
                if (f.n() > lastSent) {
                    out.add(f);
                }
            }
            return out;
        }

        synchronized void sent(long n) {
            if (n > lastSent) {
                lastSent = n;
            }
        }

        synchronized void wake() {
            notifyAll();
        }
    }

    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final Map<Long, Client> byId = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> perAddress = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger live = new AtomicInteger();
    private final SecureRandom random = new SecureRandom();
    /** Changes with every hub, so ids from before a restart never resume. */
    private final String run = Long.toString(System.currentTimeMillis() & 0xFFFFFFFFL, 36)
            + Integer.toString(new SecureRandom().nextInt(1 << 20), 36);
    private volatile BiPredicate<Viewer, String> worldGate = (v, w) -> true;
    private ScheduledExecutorService watchdog;

    /** Live connections, without the ended ones kept for a resume. */
    public int size() {
        return live.get();
    }

    /**
     * Sets the check that decides whether a viewer may see events of a world. Events listed in
     * WORLD_SCOPED whose payload names a world the viewer may not see are not sent to that viewer.
     */
    public void setWorldGate(BiPredicate<Viewer, String> gate) {
        worldGate = gate == null ? (v, w) -> true : gate;
    }

    /** Uuids of the players behind every open stream, for work done only for viewers who are watching. */
    public Set<UUID> viewerUuids() {
        Set<UUID> out = new java.util.HashSet<>();
        for (Client c : clients) {
            Viewer v = c.viewer;
            if (!c.closed && v != null && v.uuid() != null) {
                out.add(v.uuid());
            }
        }
        return out;
    }

    public static byte[] frame(String event, String json) {
        return ("event: " + event + "\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Open streams from one address key, as used for the per address limit. */
    public int countFor(String addressKey) {
        var n = perAddress.get(addressKey);
        return n == null ? 0 : n.get();
    }

    /** The id of a frame as the browser sees it. */
    String eventId(Client c, long n) {
        return run + "-" + Long.toHexString(c.id) + "-" + n;
    }

    /** The parts of a Last-Event-ID from this hub as {client id, sequence}, or null. */
    long[] parseEventId(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.trim().split("-", -1);
        if (parts.length != 3 || !parts[0].equals(run)) {
            return null;
        }
        try {
            return new long[]{Long.parseUnsignedLong(parts[1], 16), Long.parseLong(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Serves one client until it disconnects. Runs on the HTTP handler thread. The address key is the
     * proxy aware client address grouped like the link rate limit, and at most perAddressLimit streams
     * may be open per key next to the global limit of MAX_CLIENTS. A perAddressLimit of zero or less
     * skips the per address limit and keeps only the global one. The initial frames are built only
     * when the request does not resume an earlier stream.
     */
    public void serve(HttpExchange ex, Viewer viewer, String world, Identity identity, Supplier<List<byte[]>> initial,
                      String addressKey, int perAddressLimit) throws IOException {
        String key = identity.key(viewer);
        if (live.get() >= MAX_CLIENTS) {
            ex.getResponseHeaders().set("Retry-After", "30");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(503, -1);
            ex.close();
            return;
        }
        boolean limited = perAddressLimit > 0;
        if (limited && !acquire(addressKey, perAddressLimit)) {
            ex.getResponseHeaders().set("Retry-After", "30");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(429, -1);
            ex.close();
            return;
        }
        live.incrementAndGet();
        try {
            ensureWatchdog();
            Client resumed = resume(ex.getRequestHeaders().getFirst("Last-Event-ID"), key, world);
            stream(ex, viewer, world, identity, initial, key, addressKey, resumed);
        } finally {
            live.decrementAndGet();
            if (limited) {
                release(addressKey);
            }
        }
    }

    /**
     * The ended client a Last-Event-ID points at, attached to this request, or null when the id is
     * from another run, the client is gone, the viewer or world differ or frames after the id were
     * already dropped from its history. A client whose old connection is still attached is taken over.
     */
    private Client resume(String lastEventId, String key, String world) {
        long[] id = parseEventId(lastEventId);
        if (id == null) {
            return null;
        }
        Client c = byId.get(id[0]);
        if (c == null || c.closed || !key.equals(c.key) || !java.util.Objects.equals(world, c.world)) {
            return null;
        }
        long last = id[1];
        synchronized (c) {
            if (!c.ghost) {
                c.takenOver = true;
                Thread w = c.writer;
                if (w != null) {
                    w.interrupt();
                }
                long until = System.currentTimeMillis() + 2_000;
                while (!c.ghost && !c.closed) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) {
                        return null;
                    }
                    try {
                        c.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
            if (c.closed || last < c.coveredFrom || last > sequence.get()) {
                return null;
            }
            c.ghost = false;
            c.takenOver = false;
            c.queue.clear();
            for (Frame f : c.history) {
                if (f.n() > last) {
                    c.queue.addLast(f);
                }
            }
            c.lastSent = last;
            return c;
        }
    }

    /**
     * Takes one stream slot for the address key, or returns false when the key is at its limit. The
     * check and the increment happen inside one compute so a concurrent release cannot drop the entry
     * between them.
     */
    private boolean acquire(String addressKey, int limit) {
        boolean[] ok = new boolean[1];
        perAddress.compute(addressKey, (k, n) -> {
            int current = n == null ? 0 : n.get();
            if (current >= limit) {
                return n;
            }
            ok[0] = true;
            if (n == null) {
                return new AtomicInteger(1);
            }
            n.incrementAndGet();
            return n;
        });
        return ok[0];
    }

    /** Gives back one stream slot. The entry is removed inside the same compute once it reaches zero. */
    private void release(String addressKey) {
        perAddress.computeIfPresent(addressKey, (k, n) -> n.decrementAndGet() <= 0 ? null : n);
    }

    private void stream(HttpExchange ex, Viewer viewer, String world, Identity identity, Supplier<List<byte[]>> initial,
                        String key, String addressKey, Client resumed) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        Client c;
        List<byte[]> first = List.of();
        if (resumed != null) {
            c = resumed;
        } else {
            first = initial.get();
            long cid;
            do {
                cid = random.nextLong() & Long.MAX_VALUE;
            } while (cid == 0 || byId.containsKey(cid));
            c = new Client(viewer, world, key, addressKey, cid, sequence.get());
            byId.put(cid, c);
            clients.add(c);
        }
        c.writer = Thread.currentThread();
        String lastHello = identity.hello(c.viewer);
        try {
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            // A random part per client spreads the browsers' own reconnects after a restart.
            int retryMs = 3000 + random.nextInt(2001);
            write(c, out, ("retry: " + retryMs + "\n\n").getBytes(StandardCharsets.UTF_8));
            if (!first.isEmpty()) {
                for (int i = 0; i < first.size(); i++) {
                    if (i == first.size() - 1) {
                        long baseline;
                        synchronized (c) {
                            baseline = c.lastSent;
                        }
                        write(c, out, ("id: " + eventId(c, baseline) + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                    write(c, out, first.get(i));
                }
            }
            flush(c, out);
            long nextIdentity = System.currentTimeMillis() + IDENTITY_REFRESH_MS;
            while (!c.closed && !c.takenOver) {
                long now = System.currentTimeMillis();
                if (now >= nextIdentity) {
                    nextIdentity = now + IDENTITY_REFRESH_MS;
                    Viewer next = identity.resolve();
                    String hello = identity.hello(next);
                    if (!hello.equals(lastHello)) {
                        // Layers, faction or admin access changed. Broadcasts pick up the new key at once.
                        lastHello = hello;
                        c.viewer = next;
                        c.key = identity.key(next);
                        Frame f = new Frame(sequence.incrementAndGet(), frame("hello", identity.helloFrame(next)), true);
                        c.offer(f);
                    }
                }
                long wait = Math.max(1, Math.min(15_000, nextIdentity - System.currentTimeMillis()));
                List<Frame> frames = c.take(wait);
                if (frames.isEmpty()) {
                    if (c.closed || c.takenOver) {
                        break;
                    }
                    write(c, out, ": ping\n\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    for (Frame f : frames) {
                        write(c, out, ("id: " + eventId(c, f.n()) + "\n").getBytes(StandardCharsets.UTF_8));
                        write(c, out, f.bytes());
                        c.sent(f.n());
                    }
                }
                flush(c, out);
            }
        } catch (InterruptedException e) {
            // Closed by the write timeout, a full queue or a resume elsewhere.
        } catch (IOException ignored) {
            // Browser went away.
        } finally {
            Thread.interrupted();
            c.writer = null;
            c.writingSince = 0;
            synchronized (c) {
                if (c.closed) {
                    clients.remove(c);
                    byId.remove(c.id, c);
                } else {
                    c.ghost = true;
                    c.ghostSince = System.currentTimeMillis();
                    c.queue.clear();
                }
                c.notifyAll();
            }
            try {
                ex.close();
            } catch (RuntimeException ignored) {
                // Already closed.
            }
        }
    }

    private static void write(Client c, OutputStream out, byte[] bytes) throws IOException {
        c.writingSince = System.currentTimeMillis();
        try {
            out.write(bytes);
        } finally {
            c.writingSince = 0;
        }
    }

    private static void flush(Client c, OutputStream out) throws IOException {
        c.writingSince = System.currentTimeMillis();
        try {
            out.flush();
        } finally {
            c.writingSince = 0;
        }
    }

    private synchronized void ensureWatchdog() {
        if (watchdog != null) {
            return;
        }
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "Atlas-sse-watchdog");
            t.setDaemon(true);
            return t;
        });
        ex.setRemoveOnCancelPolicy(true);
        ex.scheduleWithFixedDelay(this::sweep, 1, 1, TimeUnit.SECONDS);
        watchdog = ex;
    }

    /**
     * Interrupts writers stuck in a write for longer than WRITE_TIMEOUT_MS, which closes their socket,
     * and forgets ended clients after GHOST_MS, beyond MAX_GHOSTS, or while the replay bytes of every
     * client together exceed REPLAY_BUDGET_BYTES, oldest first.
     */
    void sweep() {
        long now = System.currentTimeMillis();
        List<Client> ghosts = new ArrayList<>();
        long totalBytes = 0;
        for (Client c : clients) {
            long since = c.writingSince;
            Thread w = c.writer;
            if (since != 0 && w != null && now - since > WRITE_TIMEOUT_MS) {
                w.interrupt();
            }
            synchronized (c) {
                if (c.ghost && now - c.ghostSince > GHOST_MS) {
                    forget(c);
                    continue;
                }
                if (c.ghost) {
                    ghosts.add(c);
                }
                totalBytes += c.historyBytes;
            }
        }
        ghosts.sort(java.util.Comparator.comparingLong(c -> c.ghostSince));
        int excess = ghosts.size() - MAX_GHOSTS;
        for (Client c : ghosts) {
            if (excess <= 0 && totalBytes <= REPLAY_BUDGET_BYTES) {
                break;
            }
            synchronized (c) {
                if (c.ghost && !c.closed) {
                    totalBytes -= c.historyBytes;
                    forget(c);
                }
            }
            excess--;
        }
    }

    /** Drops a client for good. Called with the client's lock held. */
    private void forget(Client c) {
        c.closed = true;
        c.history.clear();
        c.historyBytes = 0;
        clients.remove(c);
        byId.remove(c.id, c);
    }

    /** The world a world scoped payload names, or null. */
    private static String worldOf(String event, String json) {
        if (!WORLD_SCOPED.contains(event) || json == null) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? Json.str(e.getAsJsonObject(), "world") : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Sends a payload built once per distinct key among clients accepted by the filter. */
    public void broadcast(String event, Predicate<Client> filter, Function<Client, String> payloadForKey) {
        if (clients.isEmpty()) {
            return;
        }
        boolean control = CONTROL.contains(event);
        BiPredicate<Viewer, String> gate = worldGate;
        Map<String, Frame> built = new HashMap<>();
        Map<String, String> worlds = new HashMap<>();
        for (Client c : clients) {
            if (c.closed || !filter.test(c)) {
                continue;
            }
            String k = c.shareKey();
            Frame frame = built.get(k);
            if (frame == null && !built.containsKey(k)) {
                String json = payloadForKey.apply(c);
                if (json != null) {
                    frame = new Frame(sequence.incrementAndGet(), frame(event, json), control);
                    worlds.put(k, worldOf(event, json));
                }
                built.put(k, frame);
            }
            if (frame == null) {
                continue;
            }
            String w = worlds.get(k);
            if (w != null && !gate.test(c.viewer, w)) {
                continue;
            }
            c.offer(frame);
        }
    }

    /** Sends the same payload to every client accepted by the filter. */
    public void broadcastAll(String event, String json, Predicate<Client> filter) {
        if (clients.isEmpty()) {
            return;
        }
        String w = worldOf(event, json);
        BiPredicate<Viewer, String> gate = worldGate;
        Frame frame = new Frame(sequence.incrementAndGet(), frame(event, json), CONTROL.contains(event));
        for (Client c : clients) {
            if (!c.closed && filter.test(c) && (w == null || gate.test(c.viewer, w))) {
                c.offer(frame);
            }
        }
    }

    /**
     * Sends the same payload to every open stream whose viewer may see the world. A null world skips
     * the world gate. Events in CONTROL (layers and timeline among them) are never dropped from a
     * full queue. The payload must already be safe for every viewer, so it should only tell the
     * browser what to fetch again, and the fetch applies the viewer's own tier.
     */
    public void broadcast(String event, String world, String json) {
        if (clients.isEmpty() || event == null || json == null) {
            return;
        }
        BiPredicate<Viewer, String> gate = worldGate;
        Frame frame = new Frame(sequence.incrementAndGet(), frame(event, json), CONTROL.contains(event));
        for (Client c : clients) {
            if (!c.closed && (world == null || gate.test(c.viewer, world))) {
                c.offer(frame);
            }
        }
    }

    /**
     * Ends every stream and forgets every client, for a stop or a move to another address. Writers
     * stuck in a write are interrupted here, which closes their socket, so the watchdog can stop at
     * once. A later stream starts a new watchdog.
     */
    public void closeAll() {
        for (Client c : clients) {
            synchronized (c) {
                c.closed = true;
                Thread w = c.writer;
                if (w == null) {
                    forget(c);
                } else if (c.writingSince != 0) {
                    w.interrupt();
                }
                c.notifyAll();
            }
        }
        synchronized (this) {
            if (watchdog != null) {
                watchdog.shutdownNow();
                watchdog = null;
            }
        }
    }
}
