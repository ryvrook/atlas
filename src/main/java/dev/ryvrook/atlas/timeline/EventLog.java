package dev.ryvrook.atlas.timeline;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.atlas.util.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The append only event log of the timeline, one file per world and day. Callers anywhere use the
 * static record methods, which only put the event on a bounded queue. The queue is written out
 * every few seconds on the Atlas scheduler. A day holds at most maxPerDay events, after which one
 * line says that more were dropped.
 *
 * <p>Names are stored as they are. The hidden player rule is applied when the log is served, so a
 * player who is shown again later shows up in old events too.
 */
public final class EventLog {
    public static final int MAX_PER_DAY = 5_000;
    static final int QUEUE = 20_000;
    static final int MAX_TEXT = 300;
    static final int MAX_WHO = 64;
    public static final String DROPPED = "dropped";
    /** The kind of the line the timeline writes for each snapshot it keeps. */
    public static final String SNAPSHOT = "snapshot";
    private static final Pattern KIND = Pattern.compile("[a-z0-9][a-z0-9._-]{0,39}");

    private static volatile EventLog current;

    /** One event as queued and as stored. x and z are whole blocks or null. */
    public record Line(String world, long at, String kind, Integer x, Integer z, String who, String uuid, String text) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("at", at);
            o.addProperty("kind", kind);
            o.addProperty("world", world);
            o.addProperty("x", x);
            o.addProperty("z", z);
            o.addProperty("who", who);
            o.addProperty("uuid", uuid);
            o.addProperty("text", text);
            return o;
        }

        static Line fromJson(JsonObject o) {
            Double x = o.has("x") && !o.get("x").isJsonNull() ? Json.num(o, "x", 0) : null;
            Double z = o.has("z") && !o.get("z").isJsonNull() ? Json.num(o, "z", 0) : null;
            String text = Json.str(o, "text");
            return new Line(Json.str(o, "world"), (long) Json.num(o, "at", 0), Json.str(o, "kind"),
                    x == null ? null : (int) Math.round(x), z == null ? null : (int) Math.round(z),
                    Json.str(o, "who"), Json.str(o, "uuid"), text == null ? "" : text);
        }
    }

    private final TimelineStore store;
    private final ZoneId zone;
    private final int maxPerDay;
    private final BooleanSupplier writing;
    private final ArrayBlockingQueue<Line> queue = new ArrayBlockingQueue<>(QUEUE);
    private final AtomicLong queueDrops = new AtomicLong();
    /** Lines written per world and date, filled from the file on first use. Touched by flush only. */
    private final Map<String, Integer> counts = new HashMap<>();
    /** Listeners told about every recorded event, before the writing switch is checked. */
    private volatile Consumer<Line> listener = l -> { };
    private final Map<String, Integer> cachedCounts = new ConcurrentHashMap<>();

    /**
     * @param writing true while events should reach the disk, from timeline.enabled and timeline.events
     */
    public EventLog(TimelineStore store, ZoneId zone, int maxPerDay, BooleanSupplier writing) {
        this.store = store;
        this.zone = zone;
        this.maxPerDay = maxPerDay;
        this.writing = writing;
    }

    /** Makes this log the target of the static record methods, or clears it with null. */
    public static void install(EventLog log) {
        current = log;
    }

    public static EventLog current() {
        return current;
    }

    /**
     * Records an event of a world. Safe from any thread and never blocks. A no op until the plugin
     * started the timeline or while the event log is switched off.
     *
     * @param world the world the event happened in
     * @param at epoch milliseconds
     * @param kind a short lowercase kind such as join, leave, death, kill, worldevent.start, feats.unlock
     * @param x block x, or null when the event has no place
     * @param z block z, or null when the event has no place
     * @param who the player or party the event is about, by its real name, or null
     * @param text one short sentence
     */
    public static void record(String world, long at, String kind, Double x, Double z, String who, String text) {
        record(world, at, kind, x, z, who, null, text);
    }

    /** Same as the other record, with the uuid of the player named in who so the hidden rule survives renames. */
    public static void record(String world, long at, String kind, Double x, Double z, String who, UUID whoUuid,
                              String text) {
        EventLog log = current;
        if (log != null) {
            log.add(world, at, kind, x, z, who, whoUuid, text);
        }
    }

    public void setListener(Consumer<Line> l) {
        listener = l == null ? x -> { } : l;
    }

    /** Builds a line from raw input, or null when it cannot be stored. */
    static Line line(String world, long at, String kind, Double x, Double z, String who, UUID uuid, String text) {
        if (world == null || world.isBlank() || at <= 0) {
            return null;
        }
        String k = kind == null ? "other" : kind.trim().toLowerCase(Locale.ROOT);
        if (!KIND.matcher(k).matches()) {
            k = "other";
        }
        boolean placed = x != null && z != null && Double.isFinite(x) && Double.isFinite(z);
        return new Line(world, at, k,
                placed ? (int) Math.round(x) : null, placed ? (int) Math.round(z) : null,
                clip(who, MAX_WHO), uuid == null ? null : uuid.toString(), text == null ? "" : clip(text, MAX_TEXT));
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    void add(String world, long at, String kind, Double x, Double z, String who, UUID uuid, String text) {
        Line l = line(world, at, kind, x, z, who, uuid, text);
        if (l == null) {
            return;
        }
        try {
            listener.accept(l);
        } catch (RuntimeException ignored) {
            // Counters only.
        }
        if (!writing.getAsBoolean()) {
            return;
        }
        if (!queue.offer(l)) {
            queueDrops.incrementAndGet();
        }
    }

    /** Events waiting to be written. */
    public int queued() {
        return queue.size();
    }

    /** Events lost because the queue was full. */
    public long queueDrops() {
        return queueDrops.get();
    }

    public String dateOf(long at) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(at), zone).toString();
    }

    /**
     * Writes every queued event to its day file. Runs on the Atlas scheduler every five seconds and at
     * stop. Days past maxPerDay get one dropped line and nothing after it. Returns the lines written.
     */
    public synchronized int flush() throws IOException {
        List<Line> batch = new ArrayList<>(queue.size());
        queue.drainTo(batch);
        if (batch.isEmpty()) {
            return 0;
        }
        Map<String, List<Line>> byFile = new LinkedHashMap<>();
        for (Line l : batch) {
            byFile.computeIfAbsent(l.world() + "\n" + dateOf(l.at()), k -> new ArrayList<>()).add(l);
        }
        int written = 0;
        IOException failure = null;
        for (Map.Entry<String, List<Line>> e : byFile.entrySet()) {
            String[] wd = e.getKey().split("\n", 2);
            try {
                written += append(wd[0], wd[1], e.getValue());
            } catch (IOException ex) {
                failure = ex;
            }
        }
        if (failure != null) {
            throw failure;
        }
        return written;
    }

    private int append(String world, String date, List<Line> lines) throws IOException {
        Path f = store.eventsFile(world, date);
        String key = world + "\n" + date;
        Integer count = counts.get(key);
        if (count == null) {
            count = countLines(f);
        }
        StringBuilder sb = new StringBuilder();
        int written = 0;
        for (Line l : lines) {
            if (count < maxPerDay) {
                sb.append(Json.GSON.toJson(l.toJson())).append('\n');
                count++;
                written++;
            } else if (count == maxPerDay) {
                Line drop = new Line(world, l.at(), DROPPED, null, null, null, null,
                        "More events happened this day and were not kept.");
                sb.append(Json.GSON.toJson(drop.toJson())).append('\n');
                count++;
            }
        }
        if (!sb.isEmpty()) {
            store.touchWorld(world);
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                w.write(sb.toString());
            } catch (IOException e) {
                // The count is read back from the file next time, so a failed write never uses up the day.
                counts.remove(key);
                throw e;
            }
        }
        counts.put(key, count);
        if (counts.size() > 256) {
            // Old days are rarely written again. A forgotten count is read back from the file.
            counts.keySet().removeIf(k -> !k.equals(key));
        }
        cachedCounts.remove(world + "|" + date);
        return written;
    }

    static int countLines(Path f) throws IOException {
        if (!Files.isRegularFile(f)) {
            return 0;
        }
        int n = 0;
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            while (r.readLine() != null) {
                n++;
            }
        }
        return n;
    }

    /** Lines in the day file, the dropped line included. Cached until the next write to that file. */
    public int count(String world, String date) {
        return cachedCounts.computeIfAbsent(world + "|" + date, k -> {
            try {
                return countLines(store.eventsFile(world, date));
            } catch (IOException e) {
                return 0;
            }
        });
    }

    /**
     * Stored events of the world with at after the first time and before the second, both exclusive,
     * read from the day files of every date in between. The snapshot lines are not counted, since
     * they are not something that happened in the world. The dropped line is, since it stands for the
     * events of a full day that were not kept, at the time of the first of them. Events still on the
     * queue are not seen, so a caller flushes first.
     */
    public int countBetween(String world, long after, long before) {
        if (after <= 0 || before <= after) {
            return 0;
        }
        LocalDate first = LocalDate.parse(dateOf(after));
        LocalDate last = LocalDate.parse(dateOf(before));
        int n = 0;
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            for (Line l : read(world, d.toString())) {
                if (l.at() > after && l.at() < before && !SNAPSHOT.equals(l.kind())) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Every stored event of the world on that day, oldest first as written. Damaged lines are skipped. */
    public List<Line> read(String world, String date) {
        List<Line> out = new ArrayList<>();
        Path f = store.eventsFile(world, date);
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String s;
            while ((s = r.readLine()) != null) {
                if (s.isBlank()) {
                    continue;
                }
                try {
                    JsonElement e = JsonParser.parseString(s);
                    if (e.isJsonObject()) {
                        Line l = Line.fromJson(e.getAsJsonObject());
                        if (l.kind() != null && l.at() > 0) {
                            out.add(l);
                        }
                    }
                } catch (RuntimeException ignored) {
                    // A torn last line after a crash.
                }
            }
        } catch (IOException ignored) {
            // Served as empty.
        }
        return out;
    }
}
