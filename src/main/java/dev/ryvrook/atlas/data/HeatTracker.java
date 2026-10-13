package dev.ryvrook.atlas.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.atlas.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Block break and place counts per chunk per ten minute bucket, kept for seven days. Counting
 * happens on world threads, queries and persistence on other threads, so every map is concurrent.
 */
public final class HeatTracker {
    public static final long BUCKET_MS = 10 * 60 * 1000L;
    public static final long RETENTION_MS = 7 * 24 * 60 * 60 * 1000L;

    /** world, bucket index, chunk key, [breaks, places]. */
    private final Map<String, Map<Long, Map<Long, AtomicIntegerArray>>> data = new ConcurrentHashMap<>();
    private final Path file;
    private volatile boolean dirty;

    public HeatTracker(Path dataDir) {
        this.file = dataDir.resolve("heat.json");
    }

    public void record(String world, int blockX, int blockZ, boolean place) {
        int cx = Math.floorDiv(blockX, 32);
        int cz = Math.floorDiv(blockZ, 32);
        long bucket = System.currentTimeMillis() / BUCKET_MS;
        data.computeIfAbsent(world, w -> new ConcurrentHashMap<>())
                .computeIfAbsent(bucket, b -> new ConcurrentHashMap<>())
                .computeIfAbsent(DominionSnapshot.chunkKey(cx, cz), k -> new AtomicIntegerArray(2))
                .incrementAndGet(place ? 1 : 0);
        dirty = true;
    }

    /** Sums activity per chunk since the given time. Values are [cx, cz, breaks, places]. */
    public JsonArray query(String world, long sinceMs) {
        Map<Long, int[]> sum = new HashMap<>();
        Map<Long, Map<Long, AtomicIntegerArray>> buckets = data.get(world);
        JsonArray out = new JsonArray();
        if (buckets == null) {
            return out;
        }
        long firstBucket = sinceMs / BUCKET_MS;
        for (Map.Entry<Long, Map<Long, AtomicIntegerArray>> b : buckets.entrySet()) {
            if (b.getKey() < firstBucket) {
                continue;
            }
            for (Map.Entry<Long, AtomicIntegerArray> c : b.getValue().entrySet()) {
                int[] s = sum.computeIfAbsent(c.getKey(), k -> new int[2]);
                s[0] += c.getValue().get(0);
                s[1] += c.getValue().get(1);
            }
        }
        for (Map.Entry<Long, int[]> e : sum.entrySet()) {
            JsonArray row = new JsonArray();
            row.add((int) (e.getKey() >> 32));
            row.add((int) (long) e.getKey());
            row.add(e.getValue()[0]);
            row.add(e.getValue()[1]);
            out.add(row);
        }
        return out;
    }

    /** Totals across every world since the given time, as [breaks, places]. */
    public long[] totals(long sinceMs) {
        long[] t = new long[2];
        long firstBucket = sinceMs / BUCKET_MS;
        for (Map<Long, Map<Long, AtomicIntegerArray>> buckets : data.values()) {
            for (Map.Entry<Long, Map<Long, AtomicIntegerArray>> b : buckets.entrySet()) {
                if (b.getKey() < firstBucket) {
                    continue;
                }
                for (AtomicIntegerArray a : b.getValue().values()) {
                    t[0] += a.get(0);
                    t[1] += a.get(1);
                }
            }
        }
        return t;
    }

    /** Drops buckets older than the retention and worlds left without any bucket. */
    public void prune() {
        long oldest = (System.currentTimeMillis() - RETENTION_MS) / BUCKET_MS;
        for (Map<Long, Map<Long, AtomicIntegerArray>> buckets : data.values()) {
            if (buckets.keySet().removeIf(b -> b < oldest)) {
                dirty = true;
            }
        }
        if (data.values().removeIf(Map::isEmpty)) {
            dirty = true;
        }
    }

    /** Forgets every count of a removed world. */
    public void dropWorld(String world) {
        if (data.remove(world) != null) {
            dirty = true;
        }
    }

    /** The worlds with recorded activity. */
    public java.util.Set<String> worlds() {
        return java.util.Set.copyOf(data.keySet());
    }

    public void load() {
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> w : root.getAsJsonObject().entrySet()) {
                if (!w.getValue().isJsonObject()) {
                    continue;
                }
                Map<Long, Map<Long, AtomicIntegerArray>> buckets = data.computeIfAbsent(w.getKey(), k -> new ConcurrentHashMap<>());
                for (Map.Entry<String, JsonElement> b : w.getValue().getAsJsonObject().entrySet()) {
                    long bucket = Long.parseLong(b.getKey());
                    Map<Long, AtomicIntegerArray> chunks = buckets.computeIfAbsent(bucket, k -> new ConcurrentHashMap<>());
                    for (JsonElement row : b.getValue().getAsJsonArray()) {
                        JsonArray r = row.getAsJsonArray();
                        AtomicIntegerArray a = new AtomicIntegerArray(2);
                        a.set(0, r.get(2).getAsInt());
                        a.set(1, r.get(3).getAsInt());
                        chunks.put(DominionSnapshot.chunkKey(r.get(0).getAsInt(), r.get(1).getAsInt()), a);
                    }
                }
            }
            prune();
            // What was just read is what the file holds.
            dirty = false;
        } catch (Exception e) {
            data.clear();
        }
    }

    public synchronized void save() throws java.io.IOException {
        if (!dirty) {
            return;
        }
        // Cleared before the copy so counts made while writing mark the next save.
        dirty = false;
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Map<Long, Map<Long, AtomicIntegerArray>>> w : data.entrySet()) {
            JsonObject wo = new JsonObject();
            for (Map.Entry<Long, Map<Long, AtomicIntegerArray>> b : w.getValue().entrySet()) {
                JsonArray rows = new JsonArray();
                for (Map.Entry<Long, AtomicIntegerArray> c : b.getValue().entrySet()) {
                    JsonArray r = new JsonArray();
                    r.add((int) (c.getKey() >> 32));
                    r.add((int) (long) c.getKey());
                    r.add(c.getValue().get(0));
                    r.add(c.getValue().get(1));
                    rows.add(r);
                }
                wo.add(Long.toString(b.getKey()), rows);
            }
            root.add(w.getKey(), wo);
        }
        try {
            Json.writeAtomic(file, Json.GSON.toJson(root));
        } catch (java.io.IOException | RuntimeException e) {
            // Only a successful write counts. The changes stay pending for the next save.
            dirty = true;
            throw e;
        }
    }
}
