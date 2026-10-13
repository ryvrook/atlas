package dev.ryvrook.atlas.tiles;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import dev.ryvrook.atlas.data.DominionSnapshot;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/**
 * Renders map tiles from the world map chunk images.
 *
 * <p>Zoom 0 tiles are 256 by 256 pixels covering 16 by 16 chunks, so each 32 block chunk becomes
 * 16 by 16 pixels (half a pixel per block). Zoom 1 to 4 each halve the previous level. Zoom -1 and
 * -2 are native levels drawn straight from the chunk images at 1 and 2 pixels per block, covering
 * 8 by 8 and 4 by 4 chunks. Tile x and y are chunk x and z divided by the chunks per tile of the zoom.
 *
 * <p>Zoom -2, -1 and 0 are base levels composed from chunk images. Zoom 1 to 4 are built from four
 * tiles of the level below. Only one render runs per tile at a time, a second request for the same
 * tile waits for the first one.
 *
 * <p>Chunk images come from WorldMapManager. getImageIfInMemory and getImageAsync only touch
 * concurrent maps and return futures, so they are called from HTTP and worker threads. If a
 * call ever throws because a version requires the world thread, the service switches that world
 * to calling through world.execute instead.
 *
 * <p>The engine builds a chunk image from the chunk and its eight neighbours, fetched with
 * generation allowed. An image is therefore only requested for a chunk whose whole ring is
 * already explored. Chunks on the outer edge of the explored area are left blank, and when a
 * chunk becomes explored its explored neighbours are marked fresh so their tiles are drawn again.
 */
public final class TileService {
    public static final int TILE = 256;
    public static final int CHUNK_PX = 16;
    public static final int CHUNKS_PER_TILE = TILE / CHUNK_PX;
    public static final int MIN_ZOOM = ExploredIndex.MIN_ZOOM;
    public static final int MAX_ZOOM = ExploredIndex.MAX_ZOOM;
    /** Background completions of one partial base tile before it is served as it is for a while. */
    private static final int MAX_COMPLETION_ROUNDS = 3;
    /** How long a partial tile that ran out of completion rounds is served before it is tried again. */
    private static final long GIVE_UP_TTL_MS = 10 * 60 * 1000L;
    public static final int MAX_CHUNK_REQUESTS = 64;
    public static final long REQUEST_TIMEOUT_MS = 4_000;
    public static final long REFRESH_MS = 5 * 60 * 1000L;
    private static final long PARTIAL_TTL_MS = 15_000;
    /** Budget of the decoded chunk pixel cache in ints across all sizes (48 MB). */
    private static final long PIXEL_CACHE_INTS = 12L * 1024 * 1024;
    private static final int CHANGE_LOG = 5_000;
    /** Tile renders allowed at once across all HTTP requests. Cached tiles skip this limit. */
    private static final int MAX_CONCURRENT_RENDERS = 4;
    private static final int MAX_WORLD_NAME = 64;

    public record Result(int status, byte[] png, boolean partial) {
        static final Result NOT_FOUND = new Result(404, null, false);
        static final Result BUSY = new Result(503, null, false);
    }

    /** Pixels per chunk side for a base zoom, 64 at zoom -2, 32 at -1 and 16 at 0. */
    public static int chunkPixels(int zoom) {
        return TILE / ExploredIndex.chunksPerTile(zoom);
    }

    /** One entry in the change log served by /api/changes. */
    public record Change(long at, int zoom, int x, int y) {
    }

    private static final class Budget {
        int requests;
        final long deadline;

        Budget(int requests, long timeoutMs) {
            this.requests = requests;
            this.deadline = System.currentTimeMillis() + timeoutMs;
        }
    }

    private final class WorldTiles {
        final String name;
        final ExploredIndex explored = new ExploredIndex();
        final Set<Long> dirtyChunks = ConcurrentHashMap.newKeySet();
        /** Chunks that became explored after tiles may already have been rendered without them. */
        final Map<Long, Long> freshChunks = new ConcurrentHashMap<>();
        final Deque<Change> changes = new ArrayDeque<>();
        volatile long lastRefresh = System.currentTimeMillis();
        volatile boolean refreshing;
        volatile boolean useWorldThread;

        WorldTiles(String name) {
            this.name = name;
        }
    }

    private final Path dir;
    private final ByteLru memory;
    private final Map<String, WorldTiles> worlds = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, int[]> pixels = new LinkedHashMap<>(1024, 0.75f, true);
    private long pixelInts;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    /** Tiles being rendered right now, so a second request for the same tile waits for the first. */
    private final Map<String, CompletableFuture<Rendered>> inflight = new ConcurrentHashMap<>();
    /** Background completion rounds used per base tile since it was last invalidated or finished. */
    private final Map<String, Integer> rounds = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong tmpCounter = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.Semaphore renders = new java.util.concurrent.Semaphore(MAX_CONCURRENT_RENDERS);
    private final ExecutorService workers;
    private volatile BiConsumer<String, List<int[]>> invalidationListener = (w, t) -> { };

    public TileService(Path dataDir, int cacheMb) {
        this.dir = dataDir.resolve("tiles");
        this.memory = new ByteLru(cacheMb * 1024L * 1024L);
        this.workers = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Atlas-tiles");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        ImageIO.setUseCache(false);
    }

    public void setCacheMb(int mb) {
        memory.setMaxBytes(mb * 1024L * 1024L);
    }

    public void onInvalidate(BiConsumer<String, List<int[]>> listener) {
        this.invalidationListener = listener;
    }

    /**
     * Stops the workers. Tiles that still wait for a refresh are removed from disk so the next start
     * renders them again instead of serving a picture that misses the latest changes.
     */
    public void shutdown() {
        workers.shutdownNow();
        for (WorldTiles wt : worlds.values()) {
            Set<Long> pending = new HashSet<>(wt.dirtyChunks);
            pending.addAll(wt.freshChunks.keySet());
            Set<Long> baseTiles = new HashSet<>();
            for (long k : pending) {
                baseTiles.add(DominionSnapshot.chunkKey(Math.floorDiv((int) (k >> 32), CHUNKS_PER_TILE),
                        Math.floorDiv((int) k, CHUNKS_PER_TILE)));
            }
            for (long t : baseTiles) {
                for (int[] r : related((int) (t >> 32), (int) t)) {
                    deleteDisk(file(wt.name, r[0], r[1], r[2]));
                }
            }
        }
    }

    private WorldTiles tiles(String world) {
        return worlds.computeIfAbsent(world, WorldTiles::new);
    }

    public ExploredIndex explored(String world) {
        return tiles(world).explored;
    }

    /**
     * Forgets a world that the engine removed, such as a portal or instance world. Drops its explored
     * index, its tiles in memory and its tile folder on disk. Runs on the scheduler thread.
     */
    public void dropWorld(String world) {
        worlds.remove(world);
        String prefix = world + "|";
        memory.removeIf(k -> k.startsWith(prefix));
        synchronized (pixels) {
            var it = pixels.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getKey().startsWith(prefix)) {
                    pixelInts -= e.getValue().length;
                    it.remove();
                }
            }
        }
        rounds.keySet().removeIf(k -> k.startsWith(prefix));
        deleteTree(dir.resolve(safe(world)));
    }

    /** Names of the worlds that have a tile folder on disk, as folder names. */
    public List<String> diskWorlds() {
        List<String> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(p -> out.add(p.getFileName().toString()));
        } catch (IOException ignored) {
            // No tiles yet.
        }
        return out;
    }

    /** Deletes the tile folder of a world that is not loaded, by world name. */
    public void deleteDiskWorld(String world) {
        if (!worlds.containsKey(world)) {
            deleteTree(dir.resolve(safe(world)));
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            List<Path> all = new ArrayList<>(walk.toList());
            java.util.Collections.reverse(all);
            for (Path p : all) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Removed on the next attempt.
                }
            }
        } catch (IOException ignored) {
            // Folder vanished while walking.
        }
    }

    /** Reads saved chunk indices for every world. Runs on a worker. */
    public void refreshExplored() {
        Universe u = Universe.get();
        if (u == null) {
            return;
        }
        for (World w : u.getWorlds().values()) {
            WorldTiles wt = tiles(w.getName());
            workers.execute(() -> markFresh(wt, wt.explored.refresh(w)));
        }
    }

    /** Adds chunks that are loaded in memory to the explored index. Cheap, runs every half minute. */
    public void refreshLoaded() {
        Universe u = Universe.get();
        if (u == null) {
            return;
        }
        for (World w : u.getWorlds().values()) {
            WorldTiles wt = tiles(w.getName());
            if (wt.explored.refreshedAt() == 0) {
                continue;
            }
            workers.execute(() -> markFresh(wt, wt.explored.refreshLoaded(w)));
        }
    }

    private static void markFresh(WorldTiles wt, List<Long> keys) {
        long now = System.currentTimeMillis();
        for (long k : keys) {
            markFresh(wt, (int) (k >> 32), (int) k, now);
        }
    }

    /**
     * Marks a newly explored chunk fresh together with its explored neighbours. Those neighbours
     * may have been skipped as frontier chunks while this one was missing, so their tiles need
     * another pass now that the ring around them is complete.
     */
    private static void markFresh(WorldTiles wt, int cx, int cz, long now) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = cx + dx;
                int nz = cz + dz;
                if ((dx == 0 && dz == 0) || wt.explored.hasChunk(nx, nz)) {
                    wt.freshChunks.putIfAbsent(DominionSnapshot.chunkKey(nx, nz), now);
                }
            }
        }
    }

    /** Called from the chunk save system on a world thread. Cheap. */
    public void chunkSaved(String world, int cx, int cz) {
        WorldTiles wt = tiles(world);
        long key = DominionSnapshot.chunkKey(cx, cz);
        if (wt.explored.add(cx, cz)) {
            markFresh(wt, cx, cz, System.currentTimeMillis());
        } else {
            wt.dirtyChunks.add(key);
        }
    }

    // ---- requests ----------------------------------------------------------

    public Result get(String worldName, int zoom, int tx, int ty) {
        if (zoom < MIN_ZOOM || zoom > MAX_ZOOM || worldName == null || worldName.isEmpty()
                || worldName.length() > MAX_WORLD_NAME) {
            return Result.NOT_FOUND;
        }
        Universe u = Universe.get();
        World world = u == null ? null : u.getWorld(worldName);
        if (world == null) {
            // Unknown or unloaded world. Serve what an earlier run left on disk and never
            // create state for names that come straight from the request.
            Path file = file(worldName, zoom, tx, ty);
            if (Files.isRegularFile(file)) {
                try {
                    return new Result(200, Files.readAllBytes(file), false);
                } catch (IOException e) {
                    return Result.NOT_FOUND;
                }
            }
            return Result.NOT_FOUND;
        }
        WorldTiles wt = tiles(world.getName());
        if (wt.explored.refreshedAt() == 0) {
            synchronized (wt) {
                if (wt.explored.refreshedAt() == 0) {
                    wt.explored.refresh(world);
                }
            }
        }
        if (!wt.explored.hasTile(zoom, tx, ty)) {
            return Result.NOT_FOUND;
        }
        String key = key(wt.name, zoom, tx, ty);
        ByteLru.Entry cached = memory.get(key);
        if (cached != null && !cached.partial()) {
            return new Result(200, cached.data(), false);
        }
        CompletableFuture<Rendered> mine = new CompletableFuture<>();
        CompletableFuture<Rendered> running = inflight.putIfAbsent(key, mine);
        if (running != null) {
            // Another request renders this tile right now. Wait for its result instead of rendering again.
            Rendered r = await(running, REQUEST_TIMEOUT_MS * 2);
            return toResult(r);
        }
        Rendered r = null;
        try {
            boolean acquired = false;
            try {
                acquired = renders.tryAcquire(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!acquired) {
                r = Rendered.BUSY;
                return Result.BUSY;
            }
            try {
                Budget budget = new Budget(MAX_CHUNK_REQUESTS, REQUEST_TIMEOUT_MS);
                r = render(world, wt, zoom, tx, ty, budget);
            } finally {
                renders.release();
            }
            return toResult(r);
        } finally {
            mine.complete(r);
            inflight.remove(key, mine);
        }
    }

    private static Result toResult(Rendered r) {
        if (r == null) {
            return Result.NOT_FOUND;
        }
        if (r == Rendered.BUSY) {
            return Result.BUSY;
        }
        return new Result(200, r.png, r.partial);
    }

    private static Rendered await(CompletableFuture<Rendered> f, long ms) {
        try {
            return f.get(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Rendered.BUSY;
        } catch (TimeoutException | java.util.concurrent.ExecutionException e) {
            return Rendered.BUSY;
        }
    }

    /** Renders a child tile for a parent, sharing a render that another request already runs. */
    private Rendered renderShared(World world, WorldTiles wt, int zoom, int tx, int ty, Budget budget) {
        String key = key(wt.name, zoom, tx, ty);
        CompletableFuture<Rendered> mine = new CompletableFuture<>();
        CompletableFuture<Rendered> running = inflight.putIfAbsent(key, mine);
        if (running != null) {
            return await(running, Math.max(1, budget.deadline - System.currentTimeMillis()) + REQUEST_TIMEOUT_MS);
        }
        Rendered r = null;
        try {
            r = render(world, wt, zoom, tx, ty, budget);
            return r;
        } finally {
            mine.complete(r);
            inflight.remove(key, mine);
        }
    }

    private record Rendered(byte[] png, int[] argb, boolean partial) {
        /** Marks a render that could not run because the render slots stayed full. */
        static final Rendered BUSY = new Rendered(null, null, true);
    }

    private Rendered render(World world, WorldTiles wt, int zoom, int tx, int ty, Budget budget) {
        String key = key(wt.name, zoom, tx, ty);
        ByteLru.Entry cached = memory.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && (!cached.partial() || now - cached.createdAt() < partialTtl(key))) {
            return new Rendered(cached.data(), null, cached.partial());
        }
        Path file = file(wt.name, zoom, tx, ty);
        if (Files.isRegularFile(file)) {
            try {
                byte[] data = Files.readAllBytes(file);
                // Keep the time the tile was drawn, not the time it was read, for the freshness check.
                memory.put(key, new ByteLru.Entry(data, false, Files.getLastModifiedTime(file).toMillis()));
                return new Rendered(data, null, false);
            } catch (IOException ignored) {
                // Fall through and render again.
            }
        }
        if (world == null || !wt.explored.hasTile(zoom, tx, ty)) {
            return null;
        }
        int[] argb;
        boolean partial;
        if (zoom <= 0) {
            int[] missing = new int[1];
            argb = renderBase(world, wt, zoom, tx, ty, budget, missing);
            partial = missing[0] > 0;
            if (partial) {
                if (zoom == 0) {
                    queueCompletion(world, wt, tx, ty);
                } else {
                    // Lower levels finish on the next request. Count the attempt so a chunk whose
                    // image never arrives does not make every request render the tile again.
                    rounds.merge(key, 1, Integer::sum);
                }
            } else {
                rounds.remove(key);
            }
        } else {
            argb = new int[TILE * TILE];
            partial = false;
            boolean any = false;
            int half = TILE / 2;
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    int cx = tx * 2 + dx;
                    int cy = ty * 2 + dy;
                    if (!wt.explored.hasTile(zoom - 1, cx, cy)) {
                        continue;
                    }
                    Rendered child = renderShared(world, wt, zoom - 1, cx, cy, budget);
                    if (child == null) {
                        continue;
                    }
                    if (child == Rendered.BUSY) {
                        partial = true;
                        continue;
                    }
                    any = true;
                    partial |= child.partial;
                    int[] src = child.argb != null ? child.argb : decodePng(child.png);
                    if (src == null) {
                        partial = true;
                        continue;
                    }
                    int[] small = MapImageDecoder.half(src, TILE);
                    for (int y = 0; y < half; y++) {
                        System.arraycopy(small, y * half, argb, (dy * half + y) * TILE + dx * half, half);
                    }
                }
            }
            if (!any) {
                return null;
            }
        }
        byte[] png = encodePng(argb);
        if (png == null) {
            return null;
        }
        // The start of the render is stored, since chunks found while it ran may be missing from it.
        memory.put(key, new ByteLru.Entry(png, partial, now));
        if (!partial) {
            writeDisk(file, png, now);
        }
        return new Rendered(png, argb, partial);
    }

    /** How long a partial tile in memory is served before it is rendered again. */
    private long partialTtl(String key) {
        Integer used = rounds.get(key);
        return used != null && used >= MAX_COMPLETION_ROUNDS ? GIVE_UP_TTL_MS : PARTIAL_TTL_MS;
    }

    /**
     * Composes chunk images into one base tile at zoom -2, -1 or 0. Missing chunks are counted, not
     * waited on forever. Frontier chunks, whose ring of neighbours is incomplete, are skipped without counting.
     */
    private int[] renderBase(World world, WorldTiles wt, int zoom, int tx, int ty, Budget budget, int[] missing) {
        int[] out = new int[TILE * TILE];
        int cpt = ExploredIndex.chunksPerTile(zoom);
        int cpx = TILE / cpt;
        int baseX = tx * cpt;
        int baseZ = ty * cpt;
        WorldMapManager wmm = world.getWorldMapManager();
        boolean canRequest = wmm != null;
        try {
            canRequest = canRequest && wmm.isWorldMapEnabled();
        } catch (RuntimeException e) {
            canRequest = false;
        }
        if (System.currentTimeMillis() >= budget.deadline) {
            // Out of time for this request. Compose from cached chunks and let the queue finish the rest.
            canRequest = false;
        }
        List<int[]> waiting = new ArrayList<>();
        List<CompletableFuture<MapImage>> futures = new ArrayList<>();
        for (int dz = 0; dz < cpt; dz++) {
            for (int dx = 0; dx < cpt; dx++) {
                int cx = baseX + dx;
                int cz = baseZ + dz;
                if (!wt.explored.hasChunk(cx, cz)) {
                    continue;
                }
                int[] px = cachedPixels(wt.name, cpx, cx, cz);
                if (px == null && wmm != null) {
                    MapImage img = null;
                    try {
                        img = wmm.getImageIfInMemory(cx, cz);
                    } catch (RuntimeException ignored) {
                        // Treated as not in memory.
                    }
                    if (img != null) {
                        px = toChunkPixels(wt.name, cpx, cx, cz, img);
                    }
                }
                if (px != null) {
                    blit(out, px, cpx, dx, dz);
                    continue;
                }
                if (!wt.explored.hasRing(cx, cz)) {
                    // Frontier chunk. Building its image would make the engine generate the
                    // missing neighbours, so it stays blank until the ring around it exists.
                    // It is not counted as missing, since nothing can finish it right now.
                    continue;
                }
                if (!canRequest || budget.requests <= 0) {
                    missing[0]++;
                    continue;
                }
                budget.requests--;
                CompletableFuture<MapImage> f = requestImage(world, wt, wmm, cx, cz);
                if (f == null) {
                    missing[0]++;
                    continue;
                }
                futures.add(f);
                waiting.add(new int[]{cx, cz, dx, dz});
            }
        }
        if (!futures.isEmpty()) {
            long remaining = Math.max(1, budget.deadline - System.currentTimeMillis());
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(remaining, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | java.util.concurrent.ExecutionException ignored) {
                // Use whatever finished in time.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (int i = 0; i < futures.size(); i++) {
                CompletableFuture<MapImage> f = futures.get(i);
                int[] w = waiting.get(i);
                if (!f.isDone() || f.isCompletedExceptionally()) {
                    missing[0]++;
                    continue;
                }
                MapImage img = f.getNow(null);
                if (img == null) {
                    // The chunk exists but has no picture yet. Count it so the tile is finished later.
                    missing[0]++;
                    continue;
                }
                int[] px = toChunkPixels(wt.name, cpx, w[0], w[1], img);
                if (px != null) {
                    blit(out, px, cpx, w[2], w[3]);
                }
            }
        }
        return out;
    }

    private CompletableFuture<MapImage> requestImage(World world, WorldTiles wt, WorldMapManager wmm, int cx, int cz) {
        if (!wt.useWorldThread) {
            try {
                return wmm.getImageAsync(cx, cz);
            } catch (RuntimeException e) {
                wt.useWorldThread = true;
            }
        }
        CompletableFuture<MapImage> out = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    wmm.getImageAsync(cx, cz).whenComplete((img, err) -> {
                        if (err != null) {
                            out.completeExceptionally(err);
                        } else {
                            out.complete(img);
                        }
                    });
                } catch (RuntimeException e) {
                    out.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) {
            return null;
        }
        return out;
    }

    /**
     * Renders a zoom 0 tile in the background until every chunk is in. Each tile gets a bounded
     * number of rounds. After that it is served as it is, marked partial, until it is invalidated
     * or the give up time passes. Returns false when it was already queued or out of rounds.
     */
    private boolean queueCompletion(World world, WorldTiles wt, int tx, int ty) {
        String key = key(wt.name, 0, tx, ty);
        Integer used = rounds.get(key);
        if (used != null && used >= MAX_COMPLETION_ROUNDS) {
            return false;
        }
        if (queued.size() > 2_000 || !queued.add(key)) {
            return false;
        }
        rounds.merge(key, 1, Integer::sum);
        try {
            workers.execute(() -> {
                try {
                    for (int attempt = 0; attempt < 4; attempt++) {
                        long started = System.currentTimeMillis();
                        Budget b = new Budget(CHUNKS_PER_TILE * CHUNKS_PER_TILE, 20_000);
                        int[] missing = new int[1];
                        int[] argb = renderBase(world, wt, 0, tx, ty, b, missing);
                        if (missing[0] == 0) {
                            byte[] png = encodePng(argb);
                            if (png != null) {
                                memory.put(key, new ByteLru.Entry(png, false, started));
                                writeDisk(file(wt.name, 0, tx, ty), png, started);
                                rounds.remove(key);
                                invalidateRelated(wt, tx, ty, true);
                            }
                            return;
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Next request queues it again while rounds remain.
                } finally {
                    queued.remove(key);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            queued.remove(key);
            return false;
        }
        return true;
    }

    // ---- dirty tiles -------------------------------------------------------

    /**
     * Re-renders tiles whose chunks were saved, at most once per five minutes per world. Tiles that
     * gained newly explored chunks are refreshed on every call, since they miss whole chunks.
     */
    public void refreshDirty(boolean force) {
        Universe u = Universe.get();
        if (u == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (World world : u.getWorlds().values()) {
            WorldTiles wt = tiles(world.getName());
            if (wt.refreshing) {
                continue;
            }
            boolean dirtyDue = !wt.dirtyChunks.isEmpty() && (force || now - wt.lastRefresh >= REFRESH_MS);
            if (!dirtyDue && wt.freshChunks.isEmpty()) {
                continue;
            }
            Map<Long, Long> fresh = new HashMap<>(wt.freshChunks);
            fresh.forEach(wt.freshChunks::remove);
            Map<Long, Long> dirty = new HashMap<>();
            if (dirtyDue) {
                wt.lastRefresh = now;
                for (long k : new ArrayList<>(wt.dirtyChunks)) {
                    wt.dirtyChunks.remove(k);
                    dirty.put(k, Long.MAX_VALUE);
                }
            }
            wt.refreshing = true;
            workers.execute(() -> {
                try {
                    applyDirty(world, wt, dirty, true);
                    applyDirty(world, wt, fresh, false);
                } finally {
                    wt.refreshing = false;
                }
            });
        }
    }

    /**
     * Invalidates the tiles that hold the given chunks and renders them again in the background.
     * Each chunk maps to the time it changed. Saved chunks use the largest long so their tiles are
     * always redrawn. Newly explored chunks carry the time they were found, and a tile rendered
     * completely after that time already shows them and is left alone.
     */
    private void applyDirty(World world, WorldTiles wt, Map<Long, Long> chunkTimes, boolean clearEngineImages) {
        if (chunkTimes.isEmpty()) {
            return;
        }
        LongOpenHashSet engineIdx = new LongOpenHashSet();
        Map<Long, Long> baseTiles = new HashMap<>();
        for (Map.Entry<Long, Long> e : chunkTimes.entrySet()) {
            long k = e.getKey();
            int cx = (int) (k >> 32);
            int cz = (int) k;
            engineIdx.add(ChunkUtil.indexChunk(cx, cz));
            if (clearEngineImages) {
                synchronized (pixels) {
                    for (int z = MIN_ZOOM; z <= 0; z++) {
                        int[] old = pixels.remove(pixelKey(wt.name, chunkPixels(z), cx, cz));
                        if (old != null) {
                            pixelInts -= old.length;
                        }
                    }
                }
            }
            baseTiles.merge(DominionSnapshot.chunkKey(Math.floorDiv(cx, CHUNKS_PER_TILE), Math.floorDiv(cz, CHUNKS_PER_TILE)),
                    e.getValue(), Math::max);
        }
        if (clearEngineImages) {
            try {
                // Drop the engine's cached chunk images so the next request rebuilds them from blocks.
                world.getWorldMapManager().clearImagesInChunks(engineIdx);
            } catch (RuntimeException ignored) {
                // Older or newer builds may not allow this. Tiles then refresh when the engine evicts images.
            }
        }
        for (Map.Entry<Long, Long> bt : baseTiles.entrySet()) {
            long t = bt.getKey();
            int tx = (int) (t >> 32);
            int ty = (int) t;
            String key = key(wt.name, 0, tx, ty);
            if (renderedSince(wt, tx, ty, bt.getValue())) {
                continue;
            }
            memory.remove(key);
            deleteDisk(file(wt.name, 0, tx, ty));
            for (int[] r : related(tx, ty)) {
                rounds.remove(key(wt.name, r[0], r[1], r[2]));
            }
            invalidateRelated(wt, tx, ty, false);
            if (!queueCompletion(world, wt, tx, ty)) {
                // The tile is already queued and may finish with the old pixels, so try again next round.
                for (Map.Entry<Long, Long> e : chunkTimes.entrySet()) {
                    long k = e.getKey();
                    if (Math.floorDiv((int) (k >> 32), CHUNKS_PER_TILE) == tx && Math.floorDiv((int) k, CHUNKS_PER_TILE) == ty) {
                        if (clearEngineImages) {
                            wt.dirtyChunks.add(k);
                        } else {
                            wt.freshChunks.merge(k, e.getValue(), Math::max);
                        }
                    }
                }
            }
        }
    }

    /**
     * True when a complete base tile was rendered at or after the given time and no zoom -1 or -2 tile
     * under it is older or partial. The native levels render from chunk images on their own, so a fresh
     * zoom 0 tile says nothing about them.
     */
    private boolean renderedSince(WorldTiles wt, int tx, int ty, long since) {
        if (since == Long.MAX_VALUE) {
            return false;
        }
        for (int[] r : related(tx, ty)) {
            if (r[0] > 0) {
                continue;
            }
            Boolean fresh = renderedAtOrAfter(wt, r[0], r[1], r[2], since);
            if (fresh == null ? r[0] == 0 : !fresh) {
                // A missing zoom 0 tile needs a render. Missing lower tiles render fresh when asked for.
                return false;
            }
        }
        return true;
    }

    /** Whether one tile is complete and rendered at or after the time, or null when it is not cached at all. */
    private Boolean renderedAtOrAfter(WorldTiles wt, int zoom, int x, int y, long since) {
        ByteLru.Entry cached = memory.get(key(wt.name, zoom, x, y));
        if (cached != null) {
            return !cached.partial() && cached.createdAt() >= since;
        }
        try {
            Path f = file(wt.name, zoom, x, y);
            if (!Files.isRegularFile(f)) {
                return null;
            }
            return Files.getLastModifiedTime(f).toMillis() >= since;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Every tile that shows part of a zoom 0 tile, as [zoom, x, y]. That is the tile itself, its
     * four zoom -1 and sixteen zoom -2 tiles, and one parent at each zoom from 1 to 4.
     */
    static List<int[]> related(int tx, int ty) {
        List<int[]> out = new ArrayList<>();
        out.add(new int[]{0, tx, ty});
        for (int z = -1; z >= MIN_ZOOM; z--) {
            int n = 1 << -z;
            for (int dy = 0; dy < n; dy++) {
                for (int dx = 0; dx < n; dx++) {
                    out.add(new int[]{z, tx * n + dx, ty * n + dy});
                }
            }
        }
        for (int z = 1; z <= MAX_ZOOM; z++) {
            out.add(new int[]{z, Math.floorDiv(tx, 1 << z), Math.floorDiv(ty, 1 << z)});
        }
        return out;
    }

    /** Drops the cached lower and upper levels of a zoom 0 tile and tells listeners which tiles changed. */
    private void invalidateRelated(WorldTiles wt, int tx, int ty, boolean notify) {
        List<int[]> changed = new ArrayList<>();
        for (int[] r : related(tx, ty)) {
            if (r[0] != 0) {
                memory.remove(key(wt.name, r[0], r[1], r[2]));
                deleteDisk(file(wt.name, r[0], r[1], r[2]));
            }
            if (r[0] == 0 || wt.explored.hasTile(r[0], r[1], r[2])) {
                changed.add(r);
            }
        }
        if (!notify) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (wt.changes) {
            for (int[] c : changed) {
                wt.changes.addLast(new Change(now, c[0], c[1], c[2]));
            }
            while (wt.changes.size() > CHANGE_LOG) {
                wt.changes.removeFirst();
            }
        }
        invalidationListener.accept(wt.name, changed);
    }

    public List<Change> changesSince(String world, long since) {
        WorldTiles wt = worlds.get(world);
        List<Change> out = new ArrayList<>();
        if (wt == null) {
            return out;
        }
        synchronized (wt.changes) {
            for (Change c : wt.changes) {
                if (c.at() > since) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    // ---- helpers -----------------------------------------------------------

    private static String pixelKey(String world, int size, int cx, int cz) {
        return world + "|" + size + "|" + cx + "|" + cz;
    }

    private int[] cachedPixels(String world, int size, int cx, int cz) {
        synchronized (pixels) {
            return pixels.get(pixelKey(world, size, cx, cz));
        }
    }

    /** Decodes a chunk image and box filters it to size by size pixels, then caches the result. */
    private int[] toChunkPixels(String world, int size, int cx, int cz, MapImage img) {
        int[] raw = MapImageDecoder.decode(img);
        if (raw == null) {
            return null;
        }
        int[] px = MapImageDecoder.scale(raw, img.width, img.height, size);
        synchronized (pixels) {
            int[] old = pixels.put(pixelKey(world, size, cx, cz), px);
            pixelInts += px.length - (old == null ? 0 : old.length);
            var it = pixels.entrySet().iterator();
            while (pixelInts > PIXEL_CACHE_INTS && it.hasNext()) {
                pixelInts -= it.next().getValue().length;
                it.remove();
            }
        }
        return px;
    }

    private static void blit(int[] tile, int[] chunk, int size, int dx, int dz) {
        for (int y = 0; y < size; y++) {
            System.arraycopy(chunk, y * size, tile, (dz * size + y) * TILE + dx * size, size);
        }
    }

    private static byte[] encodePng(int[] argb) {
        try {
            BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, TILE, TILE, argb, 0, TILE);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16 * 1024);
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int[] decodePng(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null || img.getWidth() != TILE || img.getHeight() != TILE) {
                return null;
            }
            return img.getRGB(0, 0, TILE, TILE, null, 0, TILE);
        } catch (IOException e) {
            return null;
        }
    }

    private static String key(String world, int z, int x, int y) {
        return world + "|" + z + "|" + x + "|" + y;
    }

    private Path file(String world, int z, int x, int y) {
        return dir.resolve(safe(world)).resolve(Integer.toString(z)).resolve(x + "_" + y + ".png");
    }

    static String safe(String world) {
        String s = world.replaceAll("[^A-Za-z0-9_.-]", "_");
        // Dots alone would climb out of the tile directory.
        return s.isEmpty() || s.chars().allMatch(c -> c == '.') ? "_" + s.length() : s;
    }

    /**
     * Writes a tile and dates it to the start of its render, which the freshness check relies on.
     * Each write uses its own temporary name, so two writers never share a file.
     */
    private void writeDisk(Path file, byte[] png, long renderedAt) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + "." + tmpCounter.incrementAndGet() + ".tmp");
            Files.write(tmp, png);
            Files.setLastModifiedTime(tmp, java.nio.file.attribute.FileTime.fromMillis(renderedAt));
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException ignored) {
            // Memory cache still serves it.
        }
    }

    private static void deleteDisk(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Overwritten on the next render.
        }
    }

    public long cacheBytes() {
        return memory.bytes();
    }

    public int cacheEntries() {
        return memory.size();
    }

    public long cacheMaxBytes() {
        return memory.maxBytes();
    }

    public int queuedTiles() {
        return queued.size();
    }

    public int exploredChunks(String world) {
        WorldTiles wt = worlds.get(world);
        return wt == null ? 0 : wt.explored.size();
    }
}
