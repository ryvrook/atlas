package dev.ryvrook.atlas.timeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ryvrook.atlas.util.Json;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Activity per world in hourly buckets for the stats of a snapshot, which cover the 24 hours before
 * it was taken, or back to the snapshot kept before when that is longer ago, up to MAX_WINDOW_MS.
 * Keeps eight days of buckets and survives restarts through activity.json.
 */
public final class ActivityTracker {
    static final long HOUR_MS = 60L * 60 * 1000;
    public static final long WINDOW_MS = 24 * HOUR_MS;
    /** The longest span the stats of a snapshot cover, the seven days the heat data keeps. */
    public static final long MAX_WINDOW_MS = 7 * WINDOW_MS;
    /** One day more than the longest span, so a window that starts mid hour finds its first bucket. */
    static final int KEEP_HOURS = (int) (MAX_WINDOW_MS / HOUR_MS) + 24;

    static final class Bucket {
        final Set<String> seen = new HashSet<>();
        int peak;
        int kills;
        int deaths;
        int worldEvents;
    }

    /** Totals over a window. */
    public record Window(int playersSeen, int peakOnline, int kills, int deaths, int worldEvents) {
    }

    private final Map<String, TreeMap<Long, Bucket>> worlds = new ConcurrentHashMap<>();
    private final Path file;
    private volatile boolean dirty;

    public ActivityTracker(Path file) {
        this.file = file;
    }

    private Bucket bucket(String world, long at) {
        TreeMap<Long, Bucket> m = worlds.computeIfAbsent(world, k -> new TreeMap<>());
        return m.computeIfAbsent(at / HOUR_MS, k -> new Bucket());
    }

    /** One sample of who is online in a world. */
    public synchronized void online(String world, Collection<String> uuids, long at) {
        Bucket b = bucket(world, at);
        b.seen.addAll(uuids);
        b.peak = Math.max(b.peak, uuids.size());
        dirty = true;
    }

    /** A death in a world. A kill counts when the killer is a player, a death when the victim is one. */
    public synchronized void death(String world, boolean killerPlayer, boolean victimPlayer, long at) {
        if (!killerPlayer && !victimPlayer) {
            return;
        }
        Bucket b = bucket(world, at);
        if (killerPlayer) {
            b.kills++;
        }
        if (victimPlayer) {
            b.deaths++;
        }
        dirty = true;
    }

    public synchronized void worldEvent(String world, long at) {
        bucket(world, at).worldEvents++;
        dirty = true;
    }

    /** Totals of the world for the 24 hours that end at the given time. */
    public Window window(String world, long end) {
        return window(world, end, WINDOW_MS);
    }

    /**
     * Totals of the world for the span that ends at the given time, in whole hours. The hour that holds
     * end counts and the hour that holds end minus span does not, so two windows that meet never count
     * an hour twice.
     */
    public Window window(String world, long end, long spanMs) {
        return between(world, (end - spanMs) / HOUR_MS + 1, end / HOUR_MS);
    }

    /** Totals of the world from the hour that holds from to the hour that holds end, both counted. */
    public Window since(String world, long from, long end) {
        return between(world, from / HOUR_MS, end / HOUR_MS);
    }

    private synchronized Window between(String world, long from, long to) {
        TreeMap<Long, Bucket> m = worlds.get(world);
        if (m == null || to < from) {
            return new Window(0, 0, 0, 0, 0);
        }
        Set<String> seen = new HashSet<>();
        int peak = 0;
        int kills = 0;
        int deaths = 0;
        int events = 0;
        for (Bucket b : m.subMap(from, true, to, true).values()) {
            seen.addAll(b.seen);
            peak = Math.max(peak, b.peak);
            kills += b.kills;
            deaths += b.deaths;
            events += b.worldEvents;
        }
        return new Window(seen.size(), peak, kills, deaths, events);
    }

    /** Drops buckets older than KEEP_HOURS. */
    public synchronized void prune(long now) {
        long oldest = now / HOUR_MS - KEEP_HOURS;
        for (TreeMap<Long, Bucket> m : worlds.values()) {
            m.headMap(oldest).clear();
        }
        worlds.values().removeIf(TreeMap::isEmpty);
    }

    public synchronized void save() throws IOException {
        if (!dirty) {
            return;
        }
        JsonObject root = new JsonObject();
        for (Map.Entry<String, TreeMap<Long, Bucket>> w : worlds.entrySet()) {
            JsonArray rows = new JsonArray();
            for (Map.Entry<Long, Bucket> e : w.getValue().entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("hour", e.getKey());
                JsonArray seen = new JsonArray();
                e.getValue().seen.forEach(seen::add);
                o.add("seen", seen);
                o.addProperty("peak", e.getValue().peak);
                o.addProperty("kills", e.getValue().kills);
                o.addProperty("deaths", e.getValue().deaths);
                o.addProperty("worldEvents", e.getValue().worldEvents);
                rows.add(o);
            }
            root.add(w.getKey(), rows);
        }
        Json.writeAtomic(file, Json.GSON.toJson(root));
        dirty = false;
    }

    public synchronized void load() {
        try {
            JsonObject root = TimelineStore.readObject(file);
            if (root == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> w : root.entrySet()) {
                if (!w.getValue().isJsonArray()) {
                    continue;
                }
                TreeMap<Long, Bucket> m = worlds.computeIfAbsent(w.getKey(), k -> new TreeMap<>());
                for (JsonElement e : w.getValue().getAsJsonArray()) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject o = e.getAsJsonObject();
                    Bucket b = new Bucket();
                    for (JsonElement s : Json.arr(o, "seen")) {
                        if (s.isJsonPrimitive()) {
                            b.seen.add(s.getAsString());
                        }
                    }
                    b.peak = (int) Json.num(o, "peak", 0);
                    b.kills = (int) Json.num(o, "kills", 0);
                    b.deaths = (int) Json.num(o, "deaths", 0);
                    b.worldEvents = (int) Json.num(o, "worldEvents", 0);
                    m.put((long) Json.num(o, "hour", 0), b);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A damaged file only loses a day of counters.
        }
    }
}
