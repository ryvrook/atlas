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
 * snapshots/worlds/&lt;world&gt;/&lt;slot&gt;/tiles.json     tile key to hash, plus changed
 * snapshots/worlds/&lt;world&gt;/&lt;slot&gt;/summary.json   counts for the day list
 * snapshots/worlds/&lt;world&gt;/&lt;slot&gt;/stats.json     numbers and short lists
 * snapshots/worlds/&lt;world&gt;/&lt;slot&gt;/tombstone.json left when a day is pruned
 * snapshots/worlds/&lt;world&gt;/events/&lt;date&gt;.jsonl   one event per line, per day
 * </pre>
 *
 * Worlds live under worlds so that no world name can meet the blobs folder. A slot is a server local
 * date and hour such as 2026-10-13T16, see Slots. A folder named by a plain date, as Atlas 1.0 and 1.1
 * wrote them, is a slot of its own, and a plain date given to any method here means that folder when
 * it holds a snapshot and otherwise the last slot held that day. Retention counts days, and a day is pruned with all of its slots.
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

    /**
     * A safety stop for the walk that fills in a slot written before tiles were carried over. The walk
     * normally ends at a complete manifest or the oldest slot held, long before this.
     */
    public static final int CHAIN = 10_000;
    /** Complete tile maps of slots kept in memory. */
    private static final int EFFECTIVE_CACHE = 8;
    /** Bumped by every write and prune, so derived maps and counts know when to be built again. */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    private final Map<String, CachedTiles> effective = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedTiles> e) {
                    return size() > EFFECTIVE_CACHE;
                }
            });
    private final Map<String, CachedCounts> counts = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, java.util.concurrent.CompletableFuture<Map<String, String>>> building =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedTiles(long generation, Map<String, String> tiles) {
    }

    private record CachedCounts(long generation, Counts counts) {
    }

    /** Tiles of a slot as a browser sees them, own and carried, and the tiles whose image changed. */
    public record Counts(int tiles, int carried, int changed) {
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

    /** The folder of a slot. A plain date names its own folder when it holds a snapshot, or else the last slot held that day. */
    public Path dayDir(String world, String slot) {
        return worldDir(world).resolve(resolve(world, slot));
    }

    /** True for a slot such as 2026-10-13T16 or a plain date. */
    public static boolean validSlot(String s) {
        return Slots.valid(s);
    }

    /**
     * The slot a name stands for. A slot with an hour is itself. A plain date is its own folder when that
     * folder holds a snapshot or a tombstone, as on the day a server moved from Atlas 1.1 to several
     * snapshots a day. The day list names that folder as a slot of its own, so it has to stay reachable.
     * Otherwise a plain date is the last slot of that day that holds a snapshot or a tombstone, and the
     * plain date itself when none does.
     */
    public String resolve(String world, String slot) {
        if (slot == null || slot.length() != 10 || !validDate(slot)) {
            return slot;
        }
        Path own = worldDir(world).resolve(slot);
        if (Files.isRegularFile(own.resolve(TILES)) || Files.isRegularFile(own.resolve(TOMBSTONE))) {
            return slot;
        }
        String best = null;
        for (String s : slotsOf(world, slot)) {
            if (s.length() == 13 && (isSnapshotDir(world, s) || Files.isRegularFile(worldDir(world).resolve(s).resolve(TOMBSTONE)))) {
                best = s;
            }
        }
        return best == null ? slot : best;
    }

    /** True when the slot folder of exactly this name holds a snapshot, without resolving a plain date. */
    public boolean slotHasSnapshot(String world, String slot) {
        return isSnapshotDir(world, slot);
    }

    /** True when the slot folder of exactly this name was pruned, without resolving a plain date. */
    public boolean slotPruned(String world, String slot) {
        Path dir = worldDir(world).resolve(slot);
        return Files.isRegularFile(dir.resolve(TOMBSTONE)) && !Files.isRegularFile(dir.resolve(TILES));
    }

    /** The summary of the slot folder of exactly this name, or null. */
    public JsonObject slotSummary(String world, String slot) {
        try {
            return readObject(worldDir(world).resolve(slot).resolve(SUMMARY));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private boolean isSnapshotDir(String world, String folder) {
        return Files.isRegularFile(worldDir(world).resolve(folder).resolve(TILES));
    }

    /** Every slot folder of the world, oldest first, plain dates before the hours of their day. */
    public List<String> slots(String world) {
        TreeSet<String> out = new TreeSet<>();
        Path dir = worldDir(world);
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                String n = p.getFileName().toString();
                if (Slots.valid(n) && Files.isDirectory(p)) {
                    out.add(n);
                }
            }
        } catch (IOException ignored) {
            // Nothing listed.
        }
        return new ArrayList<>(out);
    }

    /** The slot folders of one day, oldest first. */
    public List<String> slotsOf(String world, String date) {
        List<String> out = new ArrayList<>();
        Path dir = worldDir(world);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, date + "*")) {
            for (Path p : s) {
                String n = p.getFileName().toString();
                if (Slots.valid(n) && Slots.date(n).equals(date) && Files.isDirectory(p)) {
                    out.add(n);
                }
            }
        } catch (IOException ignored) {
            // Nothing listed.
        }
        java.util.Collections.sort(out);
        return out;
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

    /** Every date of the world with a slot folder (snapshot or tombstone) or events, oldest first. */
    public List<String> dates(String world) {
        TreeSet<String> out = new TreeSet<>();
        Path dir = worldDir(world);
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        for (String slot : slots(world)) {
            out.add(Slots.date(slot));
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

    /** Slots with a snapshot, oldest first. */
    public List<String> snapshotSlots(String world) {
        List<String> out = new ArrayList<>();
        for (String s : slots(world)) {
            if (isSnapshotDir(world, s)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Dates with at least one snapshot slot, oldest first. */
    public List<String> snapshotDates(String world) {
        TreeSet<String> out = new TreeSet<>();
        for (String s : snapshotSlots(world)) {
            out.add(Slots.date(s));
        }
        return new ArrayList<>(out);
    }

    /** The newest snapshot slot before the given slot, or null. */
    public String previousSlot(String world, String slot) {
        String best = null;
        for (String s : snapshotSlots(world)) {
            if (s.compareTo(slot) < 0) {
                best = s;
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

    /** The manifest of the slot, cached while its file is unchanged, or null. */
    public Manifest manifest(String world, String slot) {
        String id = resolve(world, slot);
        Path f = worldDir(world).resolve(id).resolve(TILES);
        try {
            if (!Files.isRegularFile(f)) {
                return null;
            }
            long modified = Files.getLastModifiedTime(f).toMillis();
            String key = world + "|" + id;
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

    /**
     * Every tile of a slot, its own and the ones it lacks but an earlier slot holds, as tile key to
     * hash. A complete manifest already names them all. For one written before tiles were carried
     * over, the walk goes back slot by slot, see before, until a complete manifest or the oldest slot
     * held, and the newest hash of each key wins. The answer does not depend on what is cached, since
     * a cached slot on the way already holds the same fold of everything before it. CHAIN only stops
     * a walk that could not end. Concurrent callers for the same slot share one walk. Returns an
     * empty map when the slot has no snapshot.
     */
    public Map<String, String> effectiveTiles(String world, String slot) {
        String id = resolve(world, slot);
        Manifest own = manifest(world, id);
        if (own == null) {
            return Map.of();
        }
        if (own.complete()) {
            return own.tiles();
        }
        long gen = generation.get();
        String cacheKey = world + "|" + id;
        CachedTiles c = effective.get(cacheKey);
        if (c != null && c.generation() == gen) {
            return c.tiles();
        }
        java.util.concurrent.CompletableFuture<Map<String, String>> mine = new java.util.concurrent.CompletableFuture<>();
        String buildKey = cacheKey + "|" + gen;
        java.util.concurrent.CompletableFuture<Map<String, String>> running = building.putIfAbsent(buildKey, mine);
        if (running != null) {
            try {
                return running.join();
            } catch (RuntimeException e) {
                return own.tiles();
            }
        }
        try {
            Map<String, String> done = walk(world, id, own, gen);
            effective.put(cacheKey, new CachedTiles(gen, done));
            mine.complete(done);
            return done;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            building.remove(buildKey, mine);
        }
    }

    private Map<String, String> walk(String world, String id, Manifest own, long gen) {
        // Slots from the newest to the oldest one needed, then folded oldest first so newer hashes win.
        List<Manifest> chain = new ArrayList<>();
        chain.add(own);
        Map<String, String> base = Map.of();
        String at = id;
        Manifest m = own;
        for (int steps = 0; steps < CHAIN; steps++) {
            String prev = before(world, at, m);
            if (prev == null) {
                break;
            }
            CachedTiles known = effective.get(world + "|" + prev);
            if (known != null && known.generation() == gen) {
                base = known.tiles();
                break;
            }
            Manifest p = manifest(world, prev);
            if (p == null) {
                break;
            }
            if (p.complete()) {
                base = p.tiles();
                break;
            }
            chain.add(p);
            at = prev;
            m = p;
        }
        Map<String, String> out = new HashMap<>(base);
        for (int i = chain.size() - 1; i >= 0; i--) {
            out.putAll(chain.get(i).tiles());
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * The snapshot slot a slot is compared with and filled in from. That is the previous slot its
     * manifest names while that one still holds a snapshot, else the newest snapshot slot before it,
     * as after retention pruned the one it named. Null for the oldest slot held.
     */
    String before(String world, String slot, Manifest m) {
        String named = m == null ? null : m.previous();
        if (named != null && Slots.valid(named) && named.compareTo(slot) < 0 && isSnapshotDir(world, named)) {
            return named;
        }
        return previousSlot(world, slot);
    }

    /** The slot a slot's changed list is taken against, or null when it is the oldest one held. */
    public String comparedWith(String world, String slot) {
        String id = resolve(world, slot);
        Manifest m = manifest(world, id);
        if (m == null) {
            return null;
        }
        return m.complete() ? m.previous() : before(world, id, m);
    }

    /**
     * The manifest of a slot with every tile of effectiveTiles, or null when the slot has no snapshot.
     * A complete manifest is returned as it is.
     */
    public Manifest effective(String world, String slot) {
        Manifest m = manifest(world, slot);
        if (m == null || m.complete()) {
            return m;
        }
        String id = resolve(world, slot);
        Map<String, String> all = effectiveTiles(world, id);
        return new Manifest(m.world(), m.date(), m.takenAt(), before(world, id, m), m.partial(), m.skipped(),
                m.newBlobs(), all, changed(world, id, m, all), all.size() - m.tiles().size(), 0, true);
    }

    /** The hash a slot shows for a tile, its own or the one an earlier slot held, or null. */
    public String tileHash(String world, String slot, String key) {
        return effectiveTiles(world, slot).get(key);
    }

    /**
     * Keys of a slot whose image differs from the slot before, both taken with every tile they show.
     * A complete manifest keeps its own list.
     */
    public List<String> changed(String world, String slot) {
        String id = resolve(world, slot);
        Manifest m = manifest(world, id);
        if (m == null) {
            return List.of();
        }
        return m.complete() ? m.changed() : changed(world, id, m, effectiveTiles(world, id));
    }

    private List<String> changed(String world, String id, Manifest m, Map<String, String> all) {
        if (m.complete()) {
            return m.changed();
        }
        String prev = before(world, id, m);
        Map<String, String> before = prev == null ? null : effectiveTiles(world, prev);
        return Manifest.diff(before == null || before.isEmpty() ? null : before, all);
    }

    /**
     * The tile counts of the slot folder of exactly this name for the day list. A complete slot keeps
     * them in its summary. An older one is counted from its effective
     * tiles once and kept until the next write or prune. Null when the folder has no snapshot.
     */
    public Counts counts(String world, String slot) {
        JsonObject sum = slotSummary(world, slot);
        if (sum != null && Json.bool(sum, "complete")) {
            return new Counts((int) Json.num(sum, "tiles", 0), (int) Json.num(sum, "carried", 0),
                    (int) Json.num(sum, "changed", 0));
        }
        if (!isSnapshotDir(world, slot)) {
            return null;
        }
        long gen = generation.get();
        String key = world + "|" + slot;
        CachedCounts c = counts.get(key);
        if (c != null && c.generation() == gen) {
            return c.counts();
        }
        Manifest m = manifest(world, slot);
        if (m == null) {
            return null;
        }
        Map<String, String> all = effectiveTiles(world, slot);
        Counts out = new Counts(all.size(), all.size() - m.tiles().size(), changed(world, slot, m, all).size());
        counts.put(key, new CachedCounts(gen, out));
        return out;
    }

    /** Writes the manifest, its summary and the stats of a slot, replacing an earlier snapshot of that slot. */
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
        changedOnDisk();
    }

    private void changedOnDisk() {
        generation.incrementAndGet();
        effective.clear();
        counts.clear();
    }

    /** Makes sure the world folder names its world, so prune and status can list it. */
    public void touchWorld(String world) throws IOException {
        ensureWorld(world);
    }

    /**
     * Bytes used by the whole timeline on disk. A file linked into several day folders, such as an
     * unchanged key place view, is counted once.
     */
    public long diskBytes() {
        long[] total = new long[1];
        if (!Files.isDirectory(root)) {
            return 0;
        }
        Set<Object> seen = new HashSet<>();
        try (var s = Files.walk(root)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                try {
                    java.nio.file.attribute.BasicFileAttributes a = Files.readAttributes(p,
                            java.nio.file.attribute.BasicFileAttributes.class);
                    Object key = a.fileKey();
                    if (key == null || seen.add(key)) {
                        total[0] += a.size();
                    }
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
     * maxBytes (0 or less means no cap), and then every blob no remaining manifest references. A day
     * goes with all of its slots. The day of the newest snapshot of each world and today are always
     * kept. A removed snapshot slot leaves a tombstone, the events file of the day goes with it.
     * Tombstones far past the retention are forgotten.
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
            for (String slot : slots(world)) {
                String date = Slots.date(slot);
                Path dir = worldDir(world).resolve(slot);
                if (Files.isRegularFile(dir.resolve(TOMBSTONE)) && !isSnapshotDir(world, slot)
                        && LocalDate.parse(date).isBefore(keepAfter.minusDays(TOMBSTONE_DAYS))
                        && !Files.exists(eventsFile(world, date))) {
                    deleteTree(dir);
                }
            }
            for (String date : dates(world)) {
                LocalDate d = LocalDate.parse(date);
                boolean isNewest = date.equals(newest.get(world));
                // Today is never pruned, its events are still being written.
                if (!d.isAfter(keepAfter) && !isNewest && d.isBefore(today)) {
                    if (removeDay(world, date, "retention")) {
                        removed++;
                    }
                }
            }
        }
        // Reference counts over every day that is left, each day counting a hash once for all its slots.
        Map<String, Integer> refs = new HashMap<>();
        Map<String, String> interned = new HashMap<>();
        for (String world : worlds()) {
            Map<String, Set<String>> byDate = new TreeMap<>();
            Map<String, Long> bytes = new HashMap<>();
            for (String slot : snapshotSlots(world)) {
                Manifest m = Manifest.read(worldDir(world).resolve(slot).resolve(TILES));
                if (m == null) {
                    continue;
                }
                String date = Slots.date(slot);
                Set<String> hashes = byDate.computeIfAbsent(date, k -> new HashSet<>());
                for (String h : m.tiles().values()) {
                    hashes.add(interned.computeIfAbsent(h, k -> k));
                }
                bytes.merge(date, slotBytes(world, slot), Long::sum);
            }
            for (Map.Entry<String, Set<String>> e : byDate.entrySet()) {
                for (String h : e.getValue()) {
                    refs.merge(h, 1, Integer::sum);
                }
                days.add(new Day(world, e.getKey(), worldDir(world), e.getValue(), bytes.getOrDefault(e.getKey(), 0L)));
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
                if (d.date().equals(newest.get(d.world())) || !LocalDate.parse(d.date()).isBefore(today)) {
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
        changedOnDisk();
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

    private long slotBytes(String world, String slot) {
        Path day = worldDir(world).resolve(slot);
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
     * Removes the files of a day, every slot of it and its events, and leaves a tombstone in each slot
     * that held a snapshot. Returns true when anything was removed.
     */
    private boolean removeDay(String world, String date, String reason) throws IOException {
        boolean any = Files.deleteIfExists(eventsFile(world, date));
        for (String slot : slotsOf(world, date)) {
            Path day = worldDir(world).resolve(slot);
            boolean hadSnapshot = isSnapshotDir(world, slot);
            any |= Files.deleteIfExists(day.resolve(TILES));
            any |= Files.deleteIfExists(day.resolve(SUMMARY));
            any |= Files.deleteIfExists(day.resolve(STATS));
            manifests.remove(world + "|" + slot);
            if (hadSnapshot) {
                JsonObject t = new JsonObject();
                t.addProperty("date", date);
                t.addProperty("slot", slot);
                t.addProperty("prunedAt", System.currentTimeMillis());
                t.addProperty("reason", reason);
                Json.writeAtomic(day.resolve(TOMBSTONE), Json.GSON.toJson(t));
            } else if (Files.isDirectory(day) && !Files.isRegularFile(day.resolve(TOMBSTONE))) {
                deleteTree(day);
            }
        }
        // After the files are gone, so no reader caches a map built from them.
        changedOnDisk();
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

    /** Snapshot slots held per world, for status. */
    public Map<String, Integer> slotsHeld() {
        Map<String, Integer> out = new TreeMap<>();
        for (String w : worlds()) {
            out.put(w, snapshotSlots(w).size());
        }
        return out;
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
