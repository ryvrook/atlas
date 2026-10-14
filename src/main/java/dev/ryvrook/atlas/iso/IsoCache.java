package dev.ryvrook.atlas.iso;

import dev.ryvrook.atlas.tiles.TileKeys;
import dev.ryvrook.atlas.util.Json;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rendered views in memory, least recently used first out, and on disk under iso/{world}/ with
 * the oldest files removed first once the disk budget is used. The key is the world, the centre
 * chunk, the radius, the direction, the scale and a time bucket, so a view is drawn again at least
 * once per bucket.
 *
 * <p>A chunk saved inside a view never removes the view. The engine saves the chunks around players
 * every few minutes whether or not a block changed, so the view is only marked stale with the chunks
 * saved since it was drawn. A stale view is still served, and IsoService checks the fingerprint kept
 * with it to tell whether the area really changed before it draws the view again. Fingerprints live
 * in memory only, a view read back from disk after a restart has none and is drawn again once stale.
 *
 * <p>A partial view, drawn while chunks were missing or the chunk budget ran dry, stays in memory for
 * PARTIAL_MS only and is never written to disk.
 */
final class IsoCache {
    static final long PARTIAL_MS = 60_000;

    /** A rendered view. */
    record Entry(byte[] png, String etag, long renderedAt, boolean partial) {
    }

    /** The fingerprint of every chunk of a view as drawn, row by row, see IsoRenderer.slot. 0 is not known. */
    record Print(long[] terrain, long[] image) {
    }

    /** What the cache holds about one key, kept for invalidation and the disk budget. */
    private record Known(String folder, IsoView view, long bucket, long diskBytes, long diskAt) {
    }

    private final Path dir;
    private final LinkedHashMap<String, Entry> memory = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<String, Known> known = new HashMap<>();
    private final Map<String, Print> prints = new HashMap<>();
    /** Chunks saved inside a view since it was drawn, packed by chunk, to the time of the save. */
    private final Map<String, Map<Long, Long>> stale = new HashMap<>();
    private long memoryBytes;
    private long memoryMax;
    private long diskBytes;

    IsoCache(Path dir, long memoryMax) {
        this.dir = dir;
        this.memoryMax = memoryMax;
    }

    static String key(String folder, IsoView v, long bucket) {
        return folder + "/" + v.fileName(bucket);
    }

    static String folder(String world) {
        return TileKeys.safe(world);
    }

    static long chunk(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    static int chunkX(long packed) {
        return (int) (packed >> 32);
    }

    static int chunkZ(long packed) {
        return (int) packed;
    }

    /** Removes a key from the index with its fingerprint and stale chunks. */
    private Known forget(String key) {
        prints.remove(key);
        stale.remove(key);
        return known.remove(key);
    }

    Path dir() {
        return dir;
    }

    synchronized void setMemoryMax(long max) {
        memoryMax = max;
        trimMemory();
    }

    /**
     * Lists what an earlier run left on disk. Leftover temporary files and views drawn by an older
     * renderer, see IsoRaster.STYLE, are removed.
     */
    void load() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Known> found = new ArrayList<>();
        try (DirectoryStream<Path> worlds = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path w : worlds) {
                String folder = w.getFileName().toString();
                if (folder.equals("exports")) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(w)) {
                    for (Path f : files) {
                        String name = f.getFileName().toString();
                        try {
                            if (name.endsWith(Json.TMP_SUFFIX)) {
                                Files.deleteIfExists(f);
                                continue;
                            }
                            IsoView.Named n = IsoView.parseName(folder, name);
                            if (n != null && n.style() != IsoRaster.STYLE) {
                                // Drawn by an older renderer, so it is drawn again instead of served.
                                Files.deleteIfExists(f);
                                continue;
                            }
                            if (n != null) {
                                found.add(new Known(folder, n.view(), n.bucket(), Files.size(f), Files.getLastModifiedTime(f).toMillis()));
                            }
                        } catch (IOException e) {
                            // One file that cannot be read or removed does not stop the rest from being listed.
                        }
                    }
                }
            }
        } catch (IOException e) {
            // A partial listing only means some old files are pruned later than they could be.
        }
        synchronized (this) {
            for (Known k : found) {
                known.put(key(k.folder(), k.view(), k.bucket()), k);
                diskBytes += k.diskBytes();
            }
        }
    }

    /** The view from memory, else from disk, else null. */
    Entry get(IsoView v, long bucket, long now) {
        String folder = folder(v.world());
        String key = key(folder, v, bucket);
        Known k;
        synchronized (this) {
            Entry e = memory.get(key);
            if (e != null) {
                if (!e.partial() || now - e.renderedAt() < PARTIAL_MS) {
                    return e;
                }
                removeMemory(key);
                forgetIfMemoryOnly(key);
            }
            k = known.get(key);
            if (k == null || k.diskBytes() <= 0) {
                return null;
            }
        }
        Path f = dir.resolve(folder).resolve(v.fileName(bucket));
        byte[] png;
        try {
            png = Files.readAllBytes(f);
        } catch (IOException e) {
            synchronized (this) {
                Known gone = forget(key);
                if (gone != null) {
                    diskBytes -= gone.diskBytes();
                }
            }
            return null;
        }
        Entry e = new Entry(png, dev.ryvrook.atlas.tiles.TileService.etag(png), k.diskAt(), false);
        synchronized (this) {
            if (known.containsKey(key)) {
                putMemory(key, e);
            }
        }
        return e;
    }

    /** Keeps a rendered view in memory and, when it is complete and disk is allowed, on disk. */
    void put(IsoView v, long bucket, Entry e, boolean toDisk) throws IOException {
        put(v, bucket, e, toDisk, null, Long.MAX_VALUE);
    }

    /**
     * Keeps a rendered view with its fingerprint. Chunks marked stale before since, when the render
     * started, are drawn in it and forgotten, later ones keep the view stale.
     */
    void put(IsoView v, long bucket, Entry e, boolean toDisk, Print print, long since) throws IOException {
        String folder = folder(v.world());
        String key = key(folder, v, bucket);
        long size = 0;
        long at = 0;
        if (toDisk && !e.partial()) {
            Path f = dir.resolve(folder).resolve(v.fileName(bucket));
            Json.writeAtomic(f, e.png(), e.renderedAt());
            size = e.png().length;
            at = e.renderedAt();
        }
        synchronized (this) {
            Known old = known.put(key, new Known(folder, v, bucket, size, at));
            if (old != null) {
                diskBytes -= old.diskBytes();
            }
            diskBytes += size;
            if (print != null) {
                prints.put(key, print);
            } else {
                prints.remove(key);
            }
            Map<Long, Long> marks = stale.get(key);
            if (marks != null) {
                marks.values().removeIf(t -> t < since);
                if (marks.isEmpty()) {
                    stale.remove(key);
                }
            }
            putMemory(key, e);
        }
    }

    /** The chunks saved inside the view since it was drawn, to the time of the save, empty when it is fresh. */
    synchronized Map<Long, Long> staleChunks(IsoView v, long bucket) {
        Map<Long, Long> m = stale.get(key(folder(v.world()), v, bucket));
        return m == null ? Map.of() : new HashMap<>(m);
    }

    synchronized boolean isStale(IsoView v, long bucket) {
        Map<Long, Long> m = stale.get(key(folder(v.world()), v, bucket));
        return m != null && !m.isEmpty();
    }

    /** The fingerprint kept with the view, or null. */
    synchronized Print print(IsoView v, long bucket) {
        return prints.get(key(folder(v.world()), v, bucket));
    }

    /** Forgets the stale marks a check found unchanged, those made at or before checkedAt. */
    synchronized void clearStale(IsoView v, long bucket, Map<Long, Long> checked) {
        String key = key(folder(v.world()), v, bucket);
        Map<Long, Long> m = stale.get(key);
        if (m == null) {
            return;
        }
        for (Map.Entry<Long, Long> c : checked.entrySet()) {
            m.remove(c.getKey(), c.getValue());
        }
        if (m.isEmpty()) {
            stale.remove(key);
        }
    }

    /**
     * Marks every view whose square holds the chunk as stale, at the time at. Nothing is removed.
     * Returns how many views were fresh before.
     */
    synchronized int markStale(String world, int cx, int cz, long at) {
        String folder = folder(world);
        int marked = 0;
        long c = chunk(cx, cz);
        for (Map.Entry<String, Known> e : known.entrySet()) {
            Known k = e.getValue();
            if (k.folder().equals(folder) && k.view().covers(cx, cz)) {
                Map<Long, Long> m = stale.computeIfAbsent(e.getKey(), x -> new HashMap<>());
                if (m.isEmpty()) {
                    marked++;
                }
                m.put(c, at);
            }
        }
        return marked;
    }

    /** Drops every view of a world, in memory and on disk. */
    void dropWorld(String world) {
        String folder = folder(world);
        List<Known> gone = new ArrayList<>();
        synchronized (this) {
            for (Iterator<Map.Entry<String, Known>> it = known.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Known> e = it.next();
                if (e.getValue().folder().equals(folder)) {
                    it.remove();
                    prints.remove(e.getKey());
                    stale.remove(e.getKey());
                    removeMemory(e.getKey());
                    diskBytes -= e.getValue().diskBytes();
                    gone.add(e.getValue());
                }
            }
        }
        for (Known k : gone) {
            delete(k);
        }
    }

    /**
     * Removes files of buckets older than the one before the current, which no request asks for any
     * more, then the oldest files while the disk holds more than maxBytes. Returns files removed.
     */
    int prune(long currentBucket, long maxBytes) {
        List<Known> gone = new ArrayList<>();
        synchronized (this) {
            List<Map.Entry<String, Known>> all = new ArrayList<>(known.entrySet());
            all.sort(Comparator.comparingLong((Map.Entry<String, Known> e) -> e.getValue().diskAt()));
            for (int pass = 0; pass < 2; pass++) {
                for (Map.Entry<String, Known> e : all) {
                    Known k = e.getValue();
                    if (!known.containsKey(e.getKey())) {
                        continue;
                    }
                    boolean drop = pass == 0 ? k.bucket() < currentBucket - 1 : k.diskBytes() > 0 && diskBytes > maxBytes;
                    if (drop) {
                        forget(e.getKey());
                        removeMemory(e.getKey());
                        diskBytes -= k.diskBytes();
                        if (k.diskBytes() > 0) {
                            gone.add(k);
                        }
                    }
                }
            }
        }
        for (Known k : gone) {
            delete(k);
        }
        return gone.size();
    }

    private void delete(Known k) {
        try {
            Files.deleteIfExists(dir.resolve(k.folder()).resolve(k.view().fileName(k.bucket())));
        } catch (NoSuchFileException ignored) {
            // Already gone.
        } catch (IOException ignored) {
            // Tried again by nobody, the next start lists and prunes it.
        }
    }

    private void putMemory(String key, Entry e) {
        Entry old = memory.put(key, e);
        if (old != null) {
            memoryBytes -= old.png().length;
        }
        memoryBytes += e.png().length;
        trimMemory();
    }

    private void removeMemory(String key) {
        Entry old = memory.remove(key);
        if (old != null) {
            memoryBytes -= old.png().length;
        }
    }

    private void trimMemory() {
        Iterator<Map.Entry<String, Entry>> it = memory.entrySet().iterator();
        List<String> evicted = new ArrayList<>();
        // The newest view always stays, so a view larger than the whole budget can still be fetched once drawn.
        while (memoryBytes > memoryMax && memory.size() > 1 && it.hasNext()) {
            Map.Entry<String, Entry> e = it.next();
            memoryBytes -= e.getValue().png().length;
            evicted.add(e.getKey());
            it.remove();
        }
        for (String key : evicted) {
            forgetIfMemoryOnly(key);
        }
    }

    /** A view with no file is gone once it leaves memory, so the index does not grow with views drawn. */
    private void forgetIfMemoryOnly(String key) {
        Known k = known.get(key);
        if (k != null && k.diskBytes() <= 0) {
            forget(key);
        }
    }

    synchronized int memoryEntries() {
        return memory.size();
    }

    synchronized long memoryBytes() {
        return memoryBytes;
    }

    synchronized long memoryMax() {
        return memoryMax;
    }

    synchronized long diskBytes() {
        return diskBytes;
    }

    synchronized int diskFiles() {
        int n = 0;
        for (Known k : known.values()) {
            if (k.diskBytes() > 0) {
                n++;
            }
        }
        return n;
    }

    /** Keys known to the cache, for tests. */
    synchronized List<String> keys() {
        return new ArrayList<>(known.keySet());
    }
}
