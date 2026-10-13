package dev.ryvrook.atlas.http;

import com.sun.net.httpserver.HttpExchange;
import dev.ryvrook.atlas.auth.Viewer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Server sent events. Each client owns a small queue drained by its own handler thread, so a
 * slow browser only drops its own frames. Broadcasts build each payload once per distinct
 * viewer key and share the bytes across every client with that key.
 */
public final class SseHub {
    public static final int MAX_CLIENTS = 500;
    /** How often each stream resolves its viewer again, so permission and faction changes apply live. */
    public static final long IDENTITY_REFRESH_MS = 30_000;

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

    public static final class Client {
        volatile Viewer viewer;
        final String world;
        volatile String key;
        final String address;
        final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(64);
        volatile boolean closed;

        Client(Viewer viewer, String world, String key, String address) {
            this.viewer = viewer;
            this.world = world;
            this.key = key;
            this.address = address;
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

        void offer(byte[] frame) {
            if (closed) {
                return;
            }
            if (!queue.offer(frame)) {
                queue.poll();
                queue.offer(frame);
            }
        }
    }

    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> perAddress = new ConcurrentHashMap<>();

    public int size() {
        return clients.size();
    }

    /** Uuids of the players behind every open stream, for work done only for viewers who are watching. */
    public Set<java.util.UUID> viewerUuids() {
        Set<java.util.UUID> out = new java.util.HashSet<>();
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

    /**
     * Serves one client until it disconnects. Runs on the HTTP handler thread. The address key is the
     * proxy aware client address grouped like the link rate limit, and at most perAddressLimit streams
     * may be open per key next to the global limit of MAX_CLIENTS. A perAddressLimit of zero or less
     * skips the per address limit and keeps only the global one.
     */
    public void serve(HttpExchange ex, Viewer viewer, String world, Identity identity, List<byte[]> initial,
                      String addressKey, int perAddressLimit) throws IOException {
        String key = identity.key(viewer);
        String lastHello = identity.hello(viewer);
        if (clients.size() >= MAX_CLIENTS) {
            ex.getResponseHeaders().set("Retry-After", "30");
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
        try {
            stream(ex, viewer, world, identity, initial, key, lastHello, addressKey);
        } finally {
            if (limited) {
                release(addressKey);
            }
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
                return new java.util.concurrent.atomic.AtomicInteger(1);
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

    private void stream(HttpExchange ex, Viewer viewer, String world, Identity identity, List<byte[]> initial,
                        String key, String lastHello, String addressKey) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, 0);
        Client c = new Client(viewer, world, key, addressKey);
        clients.add(c);
        try (OutputStream out = ex.getResponseBody()) {
            out.write("retry: 3000\n\n".getBytes(StandardCharsets.UTF_8));
            for (byte[] f : initial) {
                out.write(f);
            }
            out.flush();
            long nextIdentity = System.currentTimeMillis() + IDENTITY_REFRESH_MS;
            while (!c.closed) {
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
                        out.write(frame("hello", identity.helloFrame(next)));
                        out.flush();
                    }
                }
                long wait = Math.max(1, Math.min(15_000, nextIdentity - System.currentTimeMillis()));
                byte[] f = c.queue.poll(wait, TimeUnit.MILLISECONDS);
                if (f == null) {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    out.write(f);
                    byte[] more;
                    while ((more = c.queue.poll()) != null) {
                        out.write(more);
                    }
                }
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Browser went away.
        } finally {
            c.closed = true;
            clients.remove(c);
            ex.close();
        }
    }

    /** Sends a payload built once per distinct key among clients accepted by the filter. */
    public void broadcast(String event, Predicate<Client> filter, Function<Client, String> payloadForKey) {
        if (clients.isEmpty()) {
            return;
        }
        Map<String, byte[]> built = new HashMap<>();
        for (Client c : clients) {
            if (c.closed || !filter.test(c)) {
                continue;
            }
            String k = c.shareKey();
            byte[] frame = built.get(k);
            if (frame == null) {
                String json = payloadForKey.apply(c);
                if (json == null) {
                    built.put(k, new byte[0]);
                    continue;
                }
                frame = frame(event, json);
                built.put(k, frame);
            }
            if (frame.length > 0) {
                c.offer(frame);
            }
        }
    }

    /** Sends the same payload to every client accepted by the filter. */
    public void broadcastAll(String event, String json, Predicate<Client> filter) {
        byte[] frame = frame(event, json);
        for (Client c : clients) {
            if (!c.closed && filter.test(c)) {
                c.offer(frame);
            }
        }
    }

    public void closeAll() {
        for (Client c : clients) {
            c.closed = true;
            c.queue.offer(new byte[0]);
        }
    }
}
