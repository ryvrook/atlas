package dev.ryvrook.atlas.timeline;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.atlas.tiles.TileKeys;
import dev.ryvrook.atlas.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The timeline on disk, under snapshots in the Atlas data folder.
 *
 * <pre>
 * snapshots/blobs/ab/abcd....png                 tile images by content hash, shared by every day
 * snapshots/worlds/&lt;world&gt;/world.json            the real world name
 * snapshots/worlds/&lt;world&gt;/&lt;date&gt;/tiles.json     tile key to hash, plus changed
 * snapshots/worlds/&lt;world&gt;/&lt;date&gt;/summary.json   counts for the day list
 * snapshots/worlds/&lt;world&gt;/&lt;date&gt;/stats.json     numbers and short lists
 * snapshots/worlds/&lt;world&gt;/&lt;date&gt;/tombstone.json left when a day is pruned
 * snapshots/worlds/&lt;world&gt;/events/&lt;date&gt;.jsonl   one event per line
 * </pre>
 *
 * Worlds live under worlds so that no world name can meet the blobs folder. Dates are server
 * local ISO dates.
 */
public final class TimelineStore {
    static final String TILES = "tiles.json";
    static final String SUMMARY = "summary.json";
    static final String STATS = "stats.json";
    static final String TOMBSTONE = "tombstone.json";
    /** Manifests kept parsed in memory for tile serving. */
    private static final int MANIFEST_CACHE = 6;
    /** Tombstones older than this many days past the retention are forgotten. */
    private static final int TOMBSTONE_DAYS = 400;

    private final Path root;
    private final BlobStore blobs;
    private final Map<String, CachedManifest> manifests = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedManifest> e) {
                    return size() > MANIFEST_CACHE;
                }
            });

    private record CachedManifest(long modified, Manifest manifest) {
    }

    /** What a prune removed. */
    public record PruneResult(int days, long blobs, long bytes, long totalBytes) {
    }

    public TimelineStore(Path root) {
        this.root = root;
        this.blobs = new BlobStore(root.resolve("blobs"));
    }

    public Path root() {
        return root;
    }

    public BlobStore blobs() {
        return blobs;
    }

    public Path worldDir(String world) {
        return root.resolve("worlds").resolve(TileKeys.safe(world));
    }

    public Path dayDir(String world, String date) {
        return worldDir(world).resolve(date);
    }

    public Path eventsFile(String world, String date) {
        return worldDir(world).resolve("events").resolve(date + ".jsonl");
    }

    /** True for a well formed ISO date such as 2026-10-13. */
    public static boolean validDate(String s) {
        if (s == null || s.length() != 10) {
            return false;
        }
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** World names that have a folder, from their world.json. */
    public List<String> worlds() {
        List<String> out = new ArrayList<>();
        Path dir = root.resolve("worlds");
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                String name = readName(p);
                if (name != null) {
                    out.add(name);
                }
            }
        } catch (IOException ignored) {
            // Listed again next time.
        }
        Collections.sort(out);
        return out;
    }

    private static String readName(Path worldDir) {
        try {
            JsonObject o = readObject(worldDir.resolve("world.json"));
            return o == null ? null : Json.str(o, "name");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void ensureWorld(String world) throws IOException {
        Path f = worldDir(world).resolve("world.json");
        if (!Files.isRegularFile(f)) {
            JsonObject o = new JsonObject();
            o.addProperty("name", world);
            Json.writeAtomic(f, Json.GSON.toJson(o));
        }
    }

    /** Every date of the world with a snapshot, a tombstone or events, oldest first. */
    public List<String> dates(String world) {
        TreeSet<String> out = new TreeSet<>();
        Path dir = worldDir(world);
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                String n = p.getFileName().toString();
                if (validDate(n) && Files.isDirectory(p)) {
                    out.add(n);
                }
            }
        } catch (IOException ignored) {
            // Nothing listed.
        }
        Path ev = dir.resolve("events");
        if (Files.isDirectory(ev)) {
            try (DirectoryStream<Path> s = Files.newDirectoryStream(ev, "*.jsonl")) {
                for (Path p : s) {
                    String n = p.getFileName().toString();
                    String d = n.substring(0, n.length() - ".jsonl".length());
                    if (validDate(d)) {
                        out.add(d);
                    }
                }
            } catch (IOException ignored) {
                // Nothing listed.
            }
        }
        return new ArrayList<>(out);
    }

    public boolean hasSnapshot(String world, String date) {
        return Files.isRegularFile(dayDir(world, date).resolve(TILES));
    }

    public boolean hasStats(String world, String date) {
        return Files.isRegularFile(dayDir(world, date).resolve(STATS));
    }

    public boolean tombstone(String world, String date) {
        return Files.isRegularFile(dayDir(world, date).resolve(TOMBSTONE)) && !hasSnapshot(world, date);
    }

    /** Dates with a snapshot, oldest first. */
    public List<String> snapshotDates(String world) {
        List<String> out = new ArrayList<>();
        for (String d : dates(world)) {
            if (hasSnapshot(world, d)) {
                out.add(d);
            }
        }
        return out;
    }

    /** The newest snapshot date before the given date, or null. */
    public String previousDate(String world, String date) {
        String best = null;
        for (String d : snapshotDates(world)) {
            if (d.compareTo(date) < 0) {
                best = d;
            }
        }
        return best;
    }

    public JsonObject summary(String world, String date) {
        try {
            return readObject(dayDir(world, date).resolve(SUMMARY));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public JsonObject stats(String world, String date) {
        try {
            return readObject(dayDir(world, date).resolve(STATS));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The manifest of the day, cached while its file is unchanged, or null. */
    public Manifest manifest(String world, String date) {
        Path f = dayDir(world, date).resolve(TILES);
        try {
            if (!Files.isRegularFile(f)) {
                return null;
            }
            long modified = Files.getLastModifiedTime(f).toMillis();
            String key = world + "|" + date;
            CachedManifest c = manifests.get(key);
            if (c != null && c.modified() == modified) {
                return c.manifest();
            }
            Manifest m = Manifest.read(f);
            if (m != null) {
                manifests.put(key, new CachedManifest(modified, m));
            }
            return m;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Writes the manifest, its summary and the stats of a day, replacing an earlier snapshot of that day. */
    public void write(Manifest m, JsonObject stats) throws IOException {
        ensureWorld(m.world());
        Path day = dayDir(m.world(), m.date());
        Json.writeAtomic(day.resolve(TILES), Json.GSON.toJson(m.toJson()));
        Json.writeAtomic(day.resolve(SUMMARY), Json.GSON.toJson(m.summary()));
        if (stats != null) {
            Json.writeAtomic(day.resolve(STATS), Json.GSON.toJson(stats));
        }
        Files.deleteIfExists(day.resolve(TOMBSTONE));
        manifests.remove(m.world() + "|" + m.date());
    }

    /** Makes sure the world folder names its world, so prune and status can list it. */
    public void touchWorld(String world) throws IOException {
        ensureWorld(world);
    }

    /** Bytes used by the whole timeline on disk. */
    public long diskBytes() {
        long[] total = new long[1];
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (var s = Files.walk(root)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                try {
                    total[0] += Files.size(p);
                } catch (IOException ignored) {
                    // Gone in between.
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // A partial figure is fine for status.
        }
        return total[0];
    }

    // ---- prune -------------------------------------------------------------

    private record Day(String world, String date, Path dir, Set<String> hashes, long bytes) {
    }

    /**
     * Removes days older than retainDays (a day is kept while its date is after today minus
     * retainDays), then the oldest days across every world while the timeline uses more than
     * maxBytes (0 or less means no cap), and then every blob no remaining manifest references. The
     * newest snapshot of each world and today are always kept. A removed snapshot day leaves a
     * tombstone, its events file goes with it. Tombstones far past the retention are forgotten.
     */
    public synchronized PruneResult prune(LocalDate today, int retainDays, long maxBytes) throws IOException {
        LocalDate keepAfter = today.minusDays(Math.max(0, retainDays));
        List<Day> days = new ArrayList<>();
        Map<String, String> newest = new HashMap<>();
        int removed = 0;
        for (String world : worlds()) {
            List<String> snaps = snapshotDates(world);
            if (!snaps.isEmpty()) {
                newest.put(world, snaps.get(snaps.size() - 1));
            }
            for (String date : dates(world)) {
                LocalDate d = LocalDate.parse(date);
                if (tombstone(world, date)) {
                    if (d.isBefore(keepAfter.minusDays(TOMBSTONE_DAYS)) && !Files.exists(eventsFile(world, date))) {
                        deleteTree(dayDir(world, date));
                    }
                    if (!Files.exists(eventsFile(world, date))) {
                        continue;
                    }
                }
                boolean isNewest = date.equals(newest.get(world));
                // Today is never pruned, its events are still being written.
                if (!d.isAfter(keepAfter) && !isNewest && d.isBefore(today)) {
                    if (removeDay(world, date, "retention")) {
                        removed++;
                    }
                }
            }
        }
        // Reference counts over every manifest that is left.
        Map<String, Integer> refs = new HashMap<>();
        Map<String, String> interned = new HashMap<>();
        for (String world : worlds()) {
            for (String date : snapshotDates(world)) {
                Manifest m = Manifest.read(dayDir(world, date).resolve(TILES));
                if (m == null) {
                    continue;
                }
                Set<String> hashes = new HashSet<>();
                for (String h : m.tiles().values()) {
                    hashes.add(interned.computeIfAbsent(h, k -> k));
                }
                for (String h : hashes) {
                    refs.merge(h, 1, Integer::sum);
                }
                days.add(new Day(world, date, dayDir(world, date), hashes, dayBytes(world, date)));
            }
        }
        long[] collected = blobs.collect(refs.keySet());
        Map<String, Long> blobSizes = new HashMap<>();
        blobs.forEach(blobSizes::put);
        long total = 0;
        for (long b : blobSizes.values()) {
            total += b;
        }
        for (Day d : days) {
            total += d.bytes();
        }
        total += eventsOnlyBytes();
        if (maxBytes > 0 && total > maxBytes) {
            days.sort((a, b) -> a.date().equals(b.date()) ? a.world().compareTo(b.world()) : a.date().compareTo(b.date()));
            Set<String> freed = new HashSet<>();
            for (Day d : days) {
                if (total <= maxBytes) {
                    break;
                }
                if (d.date().equals(newest.get(d.world()))) {
                    continue;
                }
                long eventBytes = sizeOf(eventsFile(d.world(), d.date()));
                if (!removeDay(d.world(), d.date(), "disk")) {
                    continue;
                }
                removed++;
                total -= d.bytes() + eventBytes;
                for (String h : d.hashes()) {
                    if (refs.merge(h, -1, Integer::sum) <= 0) {
                        refs.remove(h);
                        Long size = blobSizes.get(h);
                        if (size != null) {
                            total -= size;
                            freed.add(h);
                        }
                    }
                }
            }
            if (!freed.isEmpty()) {
                long[] more = blobs.collect(refs.keySet());
                collected[0] += more[0];
                collected[1] += more[1];
            }
        }
        return new PruneResult(removed, collected[0], collected[1], total);
    }

    private long eventsOnlyBytes() {
        long total = 0;
        for (String world : worlds()) {
            for (String date : dates(world)) {
                total += sizeOf(eventsFile(world, date));
            }
        }
        return total;
    }

    private long dayBytes(String world, String date) {
        Path day = dayDir(world, date);
        return sizeOf(day.resolve(TILES)) + sizeOf(day.resolve(SUMMARY)) + sizeOf(day.resolve(STATS));
    }

    private static long sizeOf(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.size(p) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Removes the files of a day and leaves a tombstone when a snapshot was there. Returns true when
     * anything was removed.
     */
    private boolean removeDay(String world, String date, String reason) throws IOException {
        Path day = dayDir(world, date);
        boolean hadSnapshot = hasSnapshot(world, date);
        boolean any = Files.deleteIfExists(eventsFile(world, date));
        any |= Files.deleteIfExists(day.resolve(TILES));
        any |= Files.deleteIfExists(day.resolve(SUMMARY));
        any |= Files.deleteIfExists(day.resolve(STATS));
        manifests.remove(world + "|" + date);
        if (hadSnapshot) {
            JsonObject t = new JsonObject();
            t.addProperty("date", date);
            t.addProperty("prunedAt", System.currentTimeMillis());
            t.addProperty("reason", reason);
            Json.writeAtomic(day.resolve(TOMBSTONE), Json.GSON.toJson(t));
        } else if (Files.isDirectory(day) && !Files.isRegularFile(day.resolve(TOMBSTONE))) {
            deleteTree(day);
        }
        return any;
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var s = Files.walk(dir)) {
            List<Path> all = new ArrayList<>(s.toList());
            Collections.reverse(all);
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException ignored) {
            // Tried again on the next prune.
        }
    }

    /** Snapshot days held per world, for status. */
    public Map<String, Integer> daysHeld() {
        Map<String, Integer> out = new TreeMap<>();
        for (String w : worlds()) {
            out.put(w, snapshotDates(w).size());
        }
        return out;
    }

    static JsonObject readObject(Path f) throws IOException {
        if (!Files.isRegularFile(f)) {
            return null;
        }
        JsonElement e = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
        return e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}
