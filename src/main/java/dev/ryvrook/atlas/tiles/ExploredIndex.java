package dev.ryvrook.atlas.tiles;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import dev.ryvrook.atlas.data.DominionSnapshot;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The chunks of one world that exist on disk or in memory. Tiles are only rendered for these
 * chunks, and a chunk image is only requested when its neighbours exist too, so requesting any
 * tile never makes the server generate terrain.
 *
 * <p>Sources are IChunkLoader.getIndexes (saved chunks), ChunkStore.getChunkIndexes (loaded
 * chunks) and chunk save events. Indices from the engine use ChunkUtil.indexChunk, Atlas keys
 * use DominionSnapshot.chunkKey, and both are converted through x and z.
 */
public final class ExploredIndex {
    public static final int MIN_ZOOM = -2;
    public static final int MAX_ZOOM = 4;

    private final Set<Long> chunks = ConcurrentHashMap.newKeySet();
    @SuppressWarnings("unchecked")
    private final Set<Long>[] tiles = new Set[MAX_ZOOM - MIN_ZOOM + 1];
    private volatile long refreshedAt;
    private volatile boolean fromDisk;
    /** Bounds as [minX, minZ, maxX, maxZ], widened as chunks are added and never scanned again. */
    private final int[] bounds = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

    public ExploredIndex() {
        for (int i = 0; i < tiles.length; i++) {
            tiles[i] = ConcurrentHashMap.newKeySet();
        }
    }

    /** Chunks along one side of a tile at the zoom. Zoom 0 is 16, each level up doubles and each level down halves. */
    public static int chunksPerTile(int zoom) {
        return zoom >= 0 ? 16 << zoom : 16 >> -zoom;
    }

    /**
     * Reads saved and loaded chunk indices. Runs on a background thread. Returns the chunk keys that
     * were not known before, which is empty on the first refresh because nothing was rendered yet.
     */
    public List<Long> refresh(World world) {
        boolean first = refreshedAt == 0;
        List<Long> fresh = new ArrayList<>();
        boolean disk = false;
        try {
            ChunkStore store = world.getChunkStore();
            IChunkLoader loader = store.getLoader();
            if (loader != null) {
                LongList saved = loader.getIndexes();
                for (LongIterator it = saved.iterator(); it.hasNext(); ) {
                    long idx = it.nextLong();
                    track(fresh, ChunkUtil.xOfChunkIndex(idx), ChunkUtil.zOfChunkIndex(idx));
                }
                disk = true;
            }
        } catch (Exception ignored) {
            // Loader without index support. Fall back to loaded chunks and save events.
        }
        fresh.addAll(refreshLoaded(world));
        fromDisk = disk;
        refreshedAt = System.currentTimeMillis();
        return first ? List.of() : fresh;
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

    private void track(List<Long> fresh, int cx, int cz) {
        if (add(cx, cz)) {
            fresh.add(DominionSnapshot.chunkKey(cx, cz));
        }
    }

    /** Adds a chunk and returns true when it was not known before. */
    public boolean add(int cx, int cz) {
        if (chunks.add(DominionSnapshot.chunkKey(cx, cz))) {
            for (int z = MIN_ZOOM; z <= MAX_ZOOM; z++) {
                int span = chunksPerTile(z);
                tiles[z - MIN_ZOOM].add(DominionSnapshot.chunkKey(Math.floorDiv(cx, span), Math.floorDiv(cz, span)));
            }
            synchronized (bounds) {
                bounds[0] = Math.min(bounds[0], cx);
                bounds[1] = Math.min(bounds[1], cz);
                bounds[2] = Math.max(bounds[2], cx);
                bounds[3] = Math.max(bounds[3], cz);
            }
            return true;
        }
        return false;
    }

    public boolean hasChunk(int cx, int cz) {
        return chunks.contains(DominionSnapshot.chunkKey(cx, cz));
    }

    /**
     * True when the chunk and all eight chunks around it exist. The engine's image builder fetches
     * every neighbour with generation allowed to shade slopes at the chunk edge, so a chunk image is
     * only requested once the whole ring exists and nothing new is ever generated for the map.
     */
    public boolean hasRing(int cx, int cz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!chunks.contains(DominionSnapshot.chunkKey(cx + dx, cz + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean hasTile(int zoom, int tx, int tz) {
        return zoom >= MIN_ZOOM && zoom <= MAX_ZOOM && tiles[zoom - MIN_ZOOM].contains(DominionSnapshot.chunkKey(tx, tz));
    }

    public int size() {
        return chunks.size();
    }

    public long refreshedAt() {
        return refreshedAt;
    }

    public boolean fromDisk() {
        return fromDisk;
    }

    /** Bounds of explored chunks as [minX, minZ, maxX, maxZ], or null when empty. Kept up to date on add. */
    public int[] bounds() {
        synchronized (bounds) {
            return bounds[0] == Integer.MAX_VALUE ? null : bounds.clone();
        }
    }
}
