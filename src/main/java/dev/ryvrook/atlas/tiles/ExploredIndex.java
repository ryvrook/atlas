package dev.ryvrook.atlas.tiles;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The chunks of one world that exist on disk or in memory. Tiles are only rendered for these
 * chunks, and a chunk image is only requested when its neighbours exist too, so requesting any
 * tile never makes the server generate terrain.
 *
 * <p>Sources are IChunkLoader.getIndexes (saved chunks), ChunkStore.getChunkIndexes (loaded
 * chunks) and chunk save events. Indices from the engine use ChunkUtil.indexChunk, Atlas keys
 * use TileKeys.key, and both are converted through x and z. The full refresh also forgets chunks
 * that are neither saved nor loaded any more, such as chunks a server owner deleted.
 */
public final class ExploredIndex {
    public static final int MIN_ZOOM = TileKeys.MIN_ZOOM;
    public static final int MAX_ZOOM = TileKeys.MAX_ZOOM;

    /** What one full refresh changed. */
    public record Delta(List<Long> added, List<Long> removed) {
        static final Delta EMPTY = new Delta(List.of(), List.of());
    }

    /** Chunk key to the sequence number it was added with. */
    private final Map<Long, Long> chunks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final Object lock = new Object();
    private volatile Set<Long>[] tiles = emptyTiles();
    private volatile long refreshedAt;
    /** Bounds as [minX, minZ, maxX, maxZ], widened as chunks are added and rebuilt when chunks are dropped. */
    private final int[] bounds = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

    @SuppressWarnings("unchecked")
    private static Set<Long>[] emptyTiles() {
        Set<Long>[] t = new Set[MAX_ZOOM - MIN_ZOOM + 1];
        for (int i = 0; i < t.length; i++) {
            t[i] = ConcurrentHashMap.newKeySet();
        }
        return t;
    }

    /** Chunks along one side of a tile at the zoom. Zoom 0 is 16, each level up doubles and each level down halves. */
    public static int chunksPerTile(int zoom) {
        return TileKeys.chunksPerTile(zoom);
    }

    /**
     * Reads saved and loaded chunk indices. Runs on a background thread. Returns the chunks that
     * were not known before, which is empty on the first refresh because nothing was rendered yet,
     * and the chunks that are gone. Chunks are only dropped when the saved index could be read,
     * since loaded chunks alone are not the whole world.
     */
    public Delta refresh(World world) {
        boolean first = refreshedAt == 0;
        long startSeq = seq.get();
        List<Long> fresh = new ArrayList<>();
        LongOpenHashSet present = new LongOpenHashSet();
        boolean disk = false;
        try {
            ChunkStore store = world.getChunkStore();
            IChunkLoader loader = store.getLoader();
            if (loader != null) {
                LongList saved = loader.getIndexes();
                for (LongIterator it = saved.iterator(); it.hasNext(); ) {
                    long idx = it.nextLong();
                    int cx = ChunkUtil.xOfChunkIndex(idx);
                    int cz = ChunkUtil.zOfChunkIndex(idx);
                    present.add(TileKeys.key(cx, cz));
                    track(fresh, cx, cz);
                }
                disk = true;
            }
        } catch (Exception ignored) {
            // Loader without index support. Fall back to loaded chunks and save events.
        }
        boolean loadedComplete = true;
        try {
            for (LongIterator it = world.getChunkStore().getChunkIndexes().iterator(); it.hasNext(); ) {
                long idx = it.nextLong();
                int cx = ChunkUtil.xOfChunkIndex(idx);
                int cz = ChunkUtil.zOfChunkIndex(idx);
                present.add(TileKeys.key(cx, cz));
                track(fresh, cx, cz);
            }
        } catch (Exception e) {
            // Concurrent modification while the world loads chunks. Nothing is dropped this time.
            loadedComplete = false;
        }
        List<Long> removed = disk && loadedComplete && !first ? retainOnly(present, startSeq) : List.of();
        refreshedAt = System.currentTimeMillis();
        return first ? Delta.EMPTY : new Delta(fresh, removed);
    }

    /**
     * Adds chunks that are loaded in memory. This is cheap, so it runs every half minute and picks
     * up chunks that were generated but not saved yet. Returns the chunk keys that were new.
     */
    public List<Long> refreshLoaded(World world) {
        List<Long> fresh = new ArrayList<>();
        try {
            for (LongIterator it = world.getChunkStore().getChunkIndexes().iterator(); it.hasNext(); ) {
                long idx = it.nextLong();
                track(fresh, ChunkUtil.xOfChunkIndex(idx), ChunkUtil.zOfChunkIndex(idx));
            }
        } catch (Exception ignored) {
            // Concurrent modification while the world loads chunks. The next refresh catches up.
        }
        return fresh;
    }

    /**
     * Drops every chunk that is not in the present set and was known before the refresh started.
     * Chunks added while the refresh ran came from save events and are kept. Returns the dropped keys.
     */
    public List<Long> retainOnly(Set<Long> present, long knownBefore) {
        List<Long> removed = new ArrayList<>();
        for (Map.Entry<Long, Long> e : chunks.entrySet()) {
            if (e.getValue() <= knownBefore && !present.contains(e.getKey())) {
                removed.add(e.getKey());
            }
        }
        if (removed.isEmpty()) {
            return removed;
        }
        synchronized (lock) {
            for (long k : removed) {
                chunks.remove(k);
            }
            rebuild();
        }
        return removed;
    }

    /** The sequence number of the newest chunk added, for retainOnly. */
    public long sequence() {
        return seq.get();
    }

    private void rebuild() {
        Set<Long>[] next = emptyTiles();
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (long k : chunks.keySet()) {
            int cx = TileKeys.x(k);
            int cz = TileKeys.z(k);
            for (int z = MIN_ZOOM; z <= MAX_ZOOM; z++) {
                next[z - MIN_ZOOM].add(TileKeys.tileOf(z, cx, cz));
            }
            b[0] = Math.min(b[0], cx);
            b[1] = Math.min(b[1], cz);
            b[2] = Math.max(b[2], cx);
            b[3] = Math.max(b[3], cz);
        }
        tiles = next;
        synchronized (bounds) {
            System.arraycopy(b, 0, bounds, 0, 4);
        }
    }

    private void track(List<Long> fresh, int cx, int cz) {
        if (add(cx, cz)) {
            fresh.add(TileKeys.key(cx, cz));
        }
    }

    /** Adds a chunk and returns true when it was not known before. */
    public boolean add(int cx, int cz) {
        long key = TileKeys.key(cx, cz);
        if (chunks.containsKey(key)) {
            return false;
        }
        synchronized (lock) {
            if (chunks.putIfAbsent(key, seq.incrementAndGet()) != null) {
                return false;
            }
            Set<Long>[] t = tiles;
            for (int z = MIN_ZOOM; z <= MAX_ZOOM; z++) {
                t[z - MIN_ZOOM].add(TileKeys.tileOf(z, cx, cz));
            }
        }
        synchronized (bounds) {
            bounds[0] = Math.min(bounds[0], cx);
            bounds[1] = Math.min(bounds[1], cz);
            bounds[2] = Math.max(bounds[2], cx);
            bounds[3] = Math.max(bounds[3], cz);
        }
        return true;
    }

    public boolean hasChunk(int cx, int cz) {
        return chunks.containsKey(TileKeys.key(cx, cz));
    }

    /**
     * True when the chunk and all eight chunks around it exist. The engine's image builder fetches
     * every neighbour with generation allowed to shade slopes at the chunk edge, so a chunk image is
     * only requested once the whole ring exists and nothing new is ever generated for the map.
     */
    public boolean hasRing(int cx, int cz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!chunks.containsKey(TileKeys.key(cx + dx, cz + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean hasTile(int zoom, int tx, int tz) {
        return zoom >= MIN_ZOOM && zoom <= MAX_ZOOM && tiles[zoom - MIN_ZOOM].contains(TileKeys.key(tx, tz));
    }

    /** A copy of the tile keys at the zoom. */
    public List<Long> tiles(int zoom) {
        if (zoom < MIN_ZOOM || zoom > MAX_ZOOM) {
            return List.of();
        }
        return new ArrayList<>(tiles[zoom - MIN_ZOOM]);
    }

    /** A copy of every chunk key. */
    public List<Long> chunks() {
        return new ArrayList<>(chunks.keySet());
    }

    public int size() {
        return chunks.size();
    }

    public long refreshedAt() {
        return refreshedAt;
    }

    /** Bounds of explored chunks as [minX, minZ, maxX, maxZ], or null when empty. Kept up to date on add. */
    public int[] bounds() {
        synchronized (bounds) {
            return bounds[0] == Integer.MAX_VALUE ? null : bounds.clone();
        }
    }
}
