package dev.ryvrook.atlas.tiles;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.util.Json;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Renders map tiles from the world map chunk images.
 *
 * <p>Zoom 0 tiles are 256 by 256 pixels covering 16 by 16 chunks, so each 32 block chunk becomes
 * 16 by 16 pixels (half a pixel per block). Zoom 1 to 4 each halve the previous level. Zoom -1 and
 * -2 are native levels drawn straight from the chunk images at 1 and 2 pixels per block, covering
 * 8 by 8 and 4 by 4 chunks. Tile x and z are chunk x and z divided by the chunks per tile of the zoom.
 *
 * <p>Zoom -2, -1 and 0 are base levels composed from chunk images. Zoom 1 to 4 are built from four
 * tiles of the level below. Only one render runs per tile at a time, a second request for the same
 * tile waits for the first one.
 *
 * <p>Chunk images come from WorldMapManager. getImageIfInMemory and getImageAsync only touch
 * concurrent maps and return futures, so they are called from HTTP and worker threads. If a
 * call ever throws because a version requires the world thread, the service switches that world
 * to calling through world.execute instead. Every image request takes a token from one shared
 * budget of chunkImagesPerMinute. Browsers may use any token left, background work only the upper
 * half, and a request that finds the budget empty gets the cached or a partial tile at once.
 *
 * <p>The engine builds a chunk image from the chunk and its eight neighbours, fetched with
 * generation allowed. An image is therefore only requested for a chunk whose whole ring is
 * already explored. Chunks on the outer edge of the explored area are left blank, and when a
 * chunk becomes explored its explored neighbours are marked fresh so their tiles are drawn again.
 *
 * <p>Each world's tile folder carries a world.json stamp with the world uuid and seed. A folder
 * whose stamp names another world is wiped before any of its tiles is served.
 */
public final class TileService {
    public static final int TILE = TileKeys.TILE;
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
    /** Pending background tasks at most. Further tasks are dropped and picked up on a later round. */
    private static final int QUEUE_CAPACITY = 8_192;
    private static final int WORKERS = 2;
    private static final long SHUTDOWN_WAIT_MS = 5_000;
    /** No tile request for this long counts as idle for the pre-render. */
    private static final long IDLE_MS = 30_000;
    /** Tiles checked for a disk copy per idle tick while looking for one to pre-render. */
    private static final int IDLE_SCAN_PER_TICK = 2_000;
    /** Old tiles queued for the age check per world on each full refresh. */
    private static final int AGE_CHECKS_PER_PASS = 256;
    private static final long FAILURE_LOG_INTERVAL_MS = 10 * 60 * 1000L;
    /** Temporary files older than this at start are leftovers of an earlier run. */
    private static final long ORPHAN_AGE_MS = 60_000;
    public static final int MAX_RADIUS_CHUNKS = 256;
    static final String STAMP_FILE = "world.json";
    private static final int STAMP_FORMAT = 1;

    /** A tile answer. etag is the strong tag of png, null when there is no png. */
    public record Result(int status, byte[] png, boolean partial, String etag) {
        static final Result NOT_FOUND = new Result(404, null, false, null);
        static final Result BUSY = new Result(503, null, false, null);
    }

    /** Background render counts for health and the render status command. perWorld holds queued tasks per world. */
    public record RenderStatus(int queued, int running, long done, boolean paused, Map<String, Integer> perWorld) {
    }

    /** Pixels per chunk side for a base zoom, 64 at zoom -2, 32 at -1 and 16 at 0. */
    public static int chunkPixels(int zoom) {
        return TileKeys.chunkPixels(zoom);
    }

    /** One entry in the change log served by /api/changes. */
    public record Change(long at, int zoom, int x, int y) {
    }

    private static final class Budget {
        int requests;
        final long deadline;
        final boolean background;
        /** Set when a chunk image was skipped because the shared budget was empty. */
        boolean limited;

        Budget(int requests, long timeoutMs, boolean background) {
            this.requests = requests;
            this.deadline = System.currentTimeMillis() + timeoutMs;
            this.background = background;
        }
    }

    private static final class WorldTiles {
        final String name;
        final ExploredIndex explored = new ExploredIndex();
        final Set<Long> dirtyChunks = ConcurrentHashMap.newKeySet();
        /** Chunks that became explored after tiles may already have been rendered without them. */
        final Map<Long, Long> freshChunks = new ConcurrentHashMap<>();
        final Deque<Change> changes = new ArrayDeque<>();
        volatile long lastRefresh = System.currentTimeMillis();
        volatile boolean refreshing;
        volatile boolean useWorldThread;
        volatile boolean stamped;
        /** Pre-render candidates as [zoom, x, z], nearest the origin first, and the scan position. */
        List<int[]> idleList = List.of();
        int idleCursor;
        int idleBuiltFor = -1;

        WorldTiles(String name) {
            this.name = name;
        }
    }

    private final Path dataDir;
    private final Path dir;
    private final ByteLru memory;
    private final Map<String, WorldTiles> worlds = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, int[]> pixels = new LinkedHashMap<>(1024, 0.75f, true);
    private long pixelInts;
    /** Tiles being rendered right now, so a second request for the same tile waits for the first. */
    private final Map<String, CompletableFuture<Rendered>> inflight = new ConcurrentHashMap<>();
    /** Background completion rounds used per base tile since it was last invalidated or finished. */
    private final Map<String, Integer> rounds = new ConcurrentHashMap<>();
    private final Semaphore renders = new Semaphore(MAX_CONCURRENT_RENDERS);
    private final RenderQueue queue;
    /** One thread for index refreshes, dirty bookkeeping, the idle tick and start up cleanup. */
    private final ScheduledThreadPoolExecutor control;
    private final TokenBucket bucket = new TokenBucket(1200);
    private final LogThrottle throttle = new LogThrottle(FAILURE_LOG_INTERVAL_MS);
    private final AtomicLong diskFailures = new AtomicLong();
    private volatile long lastDiskFailureAt;
    private volatile long lastTileRequest = System.currentTimeMillis();
    private volatile HytaleLogger logger;
    private volatile Supplier<AtlasConfig> config = () -> null;
    private volatile BiConsumer<String, List<int[]>> invalidationListener = (w, t) -> { };
    private int idleWorld;

    public TileService(Path dataDir, int cacheMb) {
        this.dataDir = dataDir;
        this.dir = dataDir.resolve("tiles");
        this.memory = new ByteLru(cacheMb * 1024L * 1024L);
        ImageIO.setUseCache(false);
        this.queue = new RenderQueue(QUEUE_CAPACITY, WORKERS, "Atlas-tiles", (task, err) ->
                fail("task", "Atlas could not finish a map tile task for world %s because %s", task.world(), describe(err)));
        this.queue.start();
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "Atlas-tiles-control");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        ex.setRemoveOnCancelPolicy(true);
        ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.control = ex;
        control.schedule(guard("cleanup", this::removeOrphans), 2, TimeUnit.SECONDS);
        control.scheduleWithFixedDelay(guard("pre-render", this::idleTick), 15, 5, TimeUnit.SECONDS);
    }

    /** Hands the service its logger and a view of the live settings. */
    public void attach(HytaleLogger logger, Supplier<AtlasConfig> config) {
        this.logger = logger;
        this.config = config == null ? () -> null : config;
    }

    public void setCacheMb(int mb) {
        memory.setMaxBytes(mb * 1024L * 1024L);
    }

    public void onInvalidate(BiConsumer<String, List<int[]>> listener) {
        this.invalidationListener = listener;
    }

    // ---- settings and logging ----------------------------------------------

    private AtlasConfig cfg() {
        try {
            return config.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void syncBudget() {
        AtlasConfig c = cfg();
        bucket.setRate(c == null ? 1200 : c.chunkImagesPerMinute());
    }

    private int tileMaxAgeHours() {
        AtlasConfig c = cfg();
        return c == null ? 24 : c.tileMaxAgeHours();
    }

    private boolean preRenderIdle() {
        AtlasConfig c = cfg();
        return c == null || c.preRenderIdle();
    }

    private void log(Level level, String format, Object... args) {
        HytaleLogger l = logger;
        if (l == null) {
            return;
        }
        try {
            l.at(level).logVarargs(format, args);
        } catch (RuntimeException ignored) {
            // Logging must never break rendering.
        }
    }

    /** Logs a failure the first time and then at most once per ten minutes per kind. */
    private void fail(String kind, String format, Object... args) {
        long held = throttle.allow(kind);
        if (held < 0) {
            return;
        }
        String suffix = held > 0 ? " (" + held + " more like this since the last report)" : "";
        log(Level.WARNING, format + suffix, args);
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : " " + m);
    }

    private Runnable guard(String what, Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable e) {
                fail("control " + what, "Atlas tile %s work failed because %s", what, describe(e));
            }
        };
    }

    private void runControl(String what, Runnable r) {
        try {
            control.execute(guard(what, r));
        } catch (RejectedExecutionException ignored) {
            // Shutting down.
        }
    }

    // ---- lifecycle ---------------------------------------------------------

    /**
     * Stops the workers, waiting up to five seconds for running renders. Tiles that still wait for
     * a refresh are removed from disk so the next start renders them again instead of serving a
     * picture that misses the latest changes.
     */
    public void shutdown() {
        control.shutdownNow();
        queue.stop(SHUTDOWN_WAIT_MS);
        for (WorldTiles wt : worlds.values()) {
            Set<Long> pending = new HashSet<>(wt.dirtyChunks);
            pending.addAll(wt.freshChunks.keySet());
            Set<Long> baseTiles = new HashSet<>();
            for (long k : pending) {
                baseTiles.add(TileKeys.baseTileOfChunk(k));
            }
            for (long t : baseTiles) {
                for (int[] r : related(TileKeys.x(t), TileKeys.z(t))) {
                    deleteTile(wt.name, r[0], r[1], r[2]);
                }
            }
        }
    }

    /** Removes temporary files that an earlier run left behind in the tile folders and the data folder. */
    private void removeOrphans() {
        long cutoff = System.currentTimeMillis() - ORPHAN_AGE_MS;
        int removed = 0;
        if (Files.isDirectory(dir)) {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.filter(p -> p.getFileName().toString().endsWith(Json.TMP_SUFFIX)).toList()) {
                    removed += deleteIfOlder(p, cutoff);
                }
            } catch (IOException | RuntimeException ignored) {
                // Tried again on the next start.
            }
        }
        if (Files.isDirectory(dataDir)) {
            try (var list = Files.list(dataDir)) {
                for (Path p : list.filter(p -> p.getFileName().toString().endsWith(Json.TMP_SUFFIX)).toList()) {
                    removed += deleteIfOlder(p, cutoff);
                }
            } catch (IOException | RuntimeException ignored) {
                // Tried again on the next start.
            }
        }
        if (removed > 0) {
            log(Level.INFO, "Atlas removed %s unfinished temporary files left by an earlier run", removed);
        }
    }

    private static int deleteIfOlder(Path p, long cutoff) {
        try {
            if (Files.isRegularFile(p) && Files.getLastModifiedTime(p).toMillis() < cutoff) {
                Files.deleteIfExists(p);
                return 1;
            }
        } catch (IOException ignored) {
            // Left for the next start.
        }
        return 0;
    }

    // ---- worlds ------------------------------------------------------------

    private WorldTiles tiles(String world) {
        return worlds.computeIfAbsent(world, WorldTiles::new);
    }

    /** The state of a loaded world, with its tile folder checked against the world stamp. */
    private WorldTiles worldTiles(World world) {
        WorldTiles wt = tiles(world.getName());
        if (!wt.stamped) {
            ensureStamp(world, wt);
        }
        return wt;
    }

    public ExploredIndex explored(String world) {
        return tiles(world).explored;
    }

    /**
     * Compares the world.json stamp in the tile folder with the world uuid and seed. A folder
     * without a stamp is adopted, since it comes from an earlier Atlas that wrote none. A stamp
     * that names another world means the world was replaced under the same name, and the folder
     * is wiped before any tile from it is served.
     */
    private void ensureStamp(World world, WorldTiles wt) {
        synchronized (wt) {
            if (wt.stamped) {
                return;
            }
            String uuid;
            String seed;
            try {
                WorldConfig c = world.getWorldConfig();
                if (c == null || c.getUuid() == null) {
                    // Not known yet. The stamp is checked again on the next use, and nothing is wiped on a guess.
                    return;
                }
                uuid = c.getUuid().toString();
                seed = Long.toString(c.getSeed());
            } catch (RuntimeException e) {
                return;
            }
            Path folder = dir.resolve(TileKeys.safe(wt.name));
            Path stamp = folder.resolve(STAMP_FILE);
            boolean write = true;
            if (Files.isRegularFile(stamp)) {
                try {
                    JsonObject o = JsonParser.parseString(Files.readString(stamp, StandardCharsets.UTF_8)).getAsJsonObject();
                    String oldUuid = Json.str(o, "uuid");
                    String oldSeed = Json.str(o, "seed");
                    if (uuid.equals(oldUuid) && seed.equals(oldSeed)) {
                        write = false;
                    } else {
                        clearWorld(wt.name);
                        deleteTree(folder);
                        log(Level.INFO, "Atlas cleared the saved tiles of world %s because the world was replaced under the same name",
                                wt.name);
                    }
                } catch (IOException | RuntimeException e) {
                    // A damaged stamp says nothing about the tiles. Write a fresh one.
                }
            }
            if (write) {
                writeStamp(wt.name, uuid, seed);
            }
            wt.stamped = true;
        }
    }

    private void writeStamp(String world, String uuid, String seed) {
        JsonObject o = new JsonObject();
        o.addProperty("format", STAMP_FORMAT);
        o.addProperty("world", world);
        o.addProperty("uuid", uuid);
        o.addProperty("seed", seed);
        try {
            Json.writeAtomic(dir.resolve(TileKeys.safe(world)).resolve(STAMP_FILE), Json.PRETTY.toJson(o));
        } catch (IOException e) {
            noteDiskFailure(e);
        }
    }

    /** Drops every cached tile, chunk picture and completion count of a world, in memory only. */
    private void clearWorld(String world) {
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
    }

    /**
     * Forgets a world that the engine removed, such as a portal or instance world. Drops its explored
     * index, its tiles in memory and its tile folder on disk. Runs on the scheduler thread.
     */
    public void dropWorld(String world) {
        worlds.remove(world);
        queue.purge(world);
        clearWorld(world);
        deleteTree(dir.resolve(TileKeys.safe(world)));
    }

    /** Drops the tiles of a world in memory and its queued renders, for a world switched off in atlas.json. */
    public void forgetMemory(String world) {
        queue.purge(world);
        clearWorld(world);
    }

    /** Deletes the tile folder of a world that is not loaded, by world name. */
    public void deleteDiskWorld(String world) {
        if (!worlds.containsKey(world)) {
            deleteTree(dir.resolve(TileKeys.safe(world)));
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            List<Path> all = new ArrayList<>(walk.toList());
            Collections.reverse(all);
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

    // ---- admin control -----------------------------------------------------

    /**
     * Deletes every saved and cached tile of a world and drops its queued renders. The explored
     * index stays, so tiles are drawn again on request or by the idle pre-render. Returns false
     * when the world is neither loaded nor has a tile folder.
     */
    public boolean purge(String world) {
        Universe u = Universe.get();
        World w = u == null ? null : u.getWorld(world);
        String name = w != null ? w.getName() : world;
        Path folder = dir.resolve(TileKeys.safe(name));
        if (w == null && !Files.isDirectory(folder)) {
            return false;
        }
        queue.purge(name);
        clearWorld(name);
        deleteTree(folder);
        WorldTiles wt = worlds.get(name);
        if (wt != null) {
            wt.dirtyChunks.clear();
            wt.freshChunks.clear();
            synchronized (wt) {
                wt.idleBuiltFor = -1;
                wt.stamped = false;
            }
        }
        if (w != null) {
            worldTiles(w);
        }
        log(Level.INFO, "Atlas purged the tiles of world %s", name);
        return true;
    }

    /**
     * Marks every explored chunk of a loaded world dirty and starts the refresh at once, so each
     * tile is drawn again from fresh chunk images. Returns the number of chunks, or -1 when the
     * world is not loaded.
     */
    public int rerender(String world) {
        Universe u = Universe.get();
        World w = u == null ? null : u.getWorld(world);
        if (w == null) {
            return -1;
        }
        WorldTiles wt = worldTiles(w);
        ensureExplored(w, wt);
        List<Long> all = wt.explored.chunks();
        wt.dirtyChunks.addAll(all);
        runControl("rerender", () -> refreshWorld(w, wt, true, System.currentTimeMillis()));
        log(Level.INFO, "Atlas queued a full redraw of world %s with %s chunks", wt.name, all.size());
        return all.size();
    }

    /**
     * Redraws the explored chunks within a radius of chunks around block x and z. Returns the
     * number of explored chunks marked, or -1 when the world is not loaded.
     */
    public int renderRadius(String world, int blockX, int blockZ, int radiusChunks) {
        Universe u = Universe.get();
        World w = u == null ? null : u.getWorld(world);
        if (w == null) {
            return -1;
        }
        int r = Math.max(0, Math.min(MAX_RADIUS_CHUNKS, radiusChunks));
        WorldTiles wt = worldTiles(w);
        ensureExplored(w, wt);
        int n = 0;
        for (long k : TileKeys.chunksInRadius(blockX, blockZ, r)) {
            if (wt.explored.hasChunk(TileKeys.x(k), TileKeys.z(k))) {
                wt.dirtyChunks.add(k);
                n++;
            }
        }
        if (n > 0) {
            runControl("render radius", () -> refreshWorld(w, wt, true, System.currentTimeMillis()));
        }
        return n;
    }

    public void pause() {
        queue.pause();
    }

    public void resume() {
        queue.resume();
    }

    public boolean paused() {
        return queue.paused();
    }

    /** Drops every queued background render and returns how many there were. */
    public int cancel() {
        return queue.cancel();
    }

    /** Drops the queued background renders of one world and returns how many there were. */
    public int purgeQueue(String world) {
        return queue.purge(world);
    }

    public RenderStatus renderStatus() {
        RenderQueue.Counts c = queue.counts();
        return new RenderStatus(c.queued(), c.running(), c.done(), c.paused(), c.perWorld());
    }

    /** Chunk image tokens left in the shared budget, or -1 when it is unlimited. */
    public int budgetLeft() {
        syncBudget();
        return bucket.available();
    }

    public int budgetPerMinute() {
        syncBudget();
        return bucket.rate();
    }

    /** The shared chunk image budget, synced with chunkImagesPerMinute, for other renderers that read chunks. */
    public TokenBucket budget() {
        syncBudget();
        return bucket;
    }

    public long lastTileRequestAt() {
        return lastTileRequest;
    }

    /** Tile writes that failed since start. */
    public long diskWriteFailures() {
        return diskFailures.get();
    }

    /** When the last tile write failed, or 0. */
    public long lastDiskFailureAt() {
        return lastDiskFailureAt;
    }

    /** Explored chunk counts of the worlds Atlas tracks, by name. */
    public Map<String, Integer> exploredCounts() {
        Map<String, Integer> out = new java.util.TreeMap<>();
        worlds.forEach((k, v) -> out.put(k, v.explored.size()));
        return out;
    }

    // ---- explored index ----------------------------------------------------

    private void ensureExplored(World world, WorldTiles wt) {
        if (wt.explored.refreshedAt() == 0) {
            synchronized (wt.explored) {
                if (wt.explored.refreshedAt() == 0) {
                    wt.explored.refresh(world);
                }
            }
        }
    }

    /**
     * Reads saved chunk indices for every world, forgets chunks that are gone and queues the age
     * check for old tiles. Runs every ten minutes.
     */
    public void refreshExplored() {
        Universe u = Universe.get();
        if (u == null) {
            return;
        }
        for (World w : u.getWorlds().values()) {
            runControl("explored", () -> {
                WorldTiles wt = worldTiles(w);
                ExploredIndex.Delta d;
                synchronized (wt.explored) {
                    d = wt.explored.refresh(w);
                }
                markFresh(wt, d.added());
                if (!d.removed().isEmpty()) {
                    log(Level.INFO, "Atlas forgot %s chunks of world %s that no longer exist and redraws their tiles",
                            d.removed().size(), wt.name);
                    Map<Long, Long> gone = new HashMap<>();
                    for (long k : d.removed()) {
                        gone.put(k, Long.MAX_VALUE);
                    }
                    applyDirty(w, wt, gone, true);
                }
                queueAgeChecks(w, wt);
            });
        }
    }

    /** Adds chunks that are loaded in memory to the explored index. Cheap, runs every half minute. */
    public void refreshLoaded() {
        Universe u = Universe.get();
        if (u == null) {
            return;
        }
        for (World w : u.getWorlds().values()) {
            WorldTiles wt = worldTiles(w);
            if (wt.explored.refreshedAt() == 0) {
                continue;
            }
            runControl("loaded", () -> markFresh(wt, wt.explored.refreshLoaded(w)));
        }
    }

    private static void markFresh(WorldTiles wt, List<Long> keys) {
        long now = System.currentTimeMillis();
        for (long k : keys) {
            markFresh(wt, TileKeys.x(k), TileKeys.z(k), now);
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
                    wt.freshChunks.putIfAbsent(TileKeys.key(nx, nz), now);
                }
            }
        }
    }

    /** Called from the chunk save system on a world thread. Cheap. */
    public void chunkSaved(String world, int cx, int cz) {
        WorldTiles wt = tiles(world);
        long key = TileKeys.key(cx, cz);
        if (wt.explored.add(cx, cz)) {
            markFresh(wt, cx, cz, System.currentTimeMillis());
        } else {
            wt.dirtyChunks.add(key);
        }
    }

    // ---- requests ----------------------------------------------------------

    /**
     * False for tiles of a world disabled in atlas.json and for tiles that lie wholly outside the
     * world's renderLimit circle. Such tiles are neither rendered nor served.
     */
    public boolean allowed(String world, int zoom, int tx, int tz) {
        AtlasConfig c = cfg();
        if (c == null) {
            return true;
        }
        if (!c.worldEnabled(world)) {
            return false;
        }
        AtlasConfig.RenderLimit limit = c.world(world).renderLimit;
        if (limit == null) {
            return true;
        }
        long span = (long) TileKeys.chunksPerTile(zoom) * 32;
        double minX = tx * span;
        double minZ = tz * span;
        return limit.touches(minX, minZ, minX + span - 1, minZ + span - 1);
    }

    /** True when the world is not disabled in atlas.json. */
    private boolean worldOn(String world) {
        AtlasConfig c = cfg();
        return c == null || c.worldEnabled(world);
    }

    /** The folder that holds the world's tiles on disk, whether or not it exists. */
    public Path diskDir(String world) {
        return dir.resolve(TileKeys.safe(world));
    }

    public Result get(String worldName, int zoom, int tx, int tz) {
        if (zoom < MIN_ZOOM || zoom > MAX_ZOOM || worldName == null || worldName.isEmpty()
                || worldName.length() > MAX_WORLD_NAME || !allowed(worldName, zoom, tx, tz)) {
            return Result.NOT_FOUND;
        }
        lastTileRequest = System.currentTimeMillis();
        Universe u = Universe.get();
        World world = u == null ? null : u.getWorld(worldName);
        if (world == null) {
            // Unknown or unloaded world. Serve what an earlier run left on disk and never
            // create state for names that come straight from the request.
            Path file = file(worldName, zoom, tx, tz);
            if (Files.isRegularFile(file)) {
                try {
                    byte[] data = Files.readAllBytes(file);
                    return new Result(200, data, false, etag(data));
                } catch (IOException e) {
                    return Result.NOT_FOUND;
                }
            }
            return Result.NOT_FOUND;
        }
        WorldTiles wt = worldTiles(world);
        ensureExplored(world, wt);
        if (!wt.explored.hasTile(zoom, tx, tz)) {
            return Result.NOT_FOUND;
        }
        syncBudget();
        String key = key(wt.name, zoom, tx, tz);
        ByteLru.Entry cached = memory.get(key);
        if (cached != null && (!cached.partial() || bucket.available() == 0)) {
            // A complete tile, or a partial one while the image budget is spent, which is
            // better served right away than rendered again without new chunk images.
            return new Result(200, cached.data(), cached.partial(), cached.etag());
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
                Budget budget = new Budget(MAX_CHUNK_REQUESTS, REQUEST_TIMEOUT_MS, false);
                r = render(world, wt, zoom, tx, tz, budget);
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
        return new Result(200, r.png, r.partial, r.etag);
    }

    private static Rendered await(CompletableFuture<Rendered> f, long ms) {
        try {
            return f.get(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Rendered.BUSY;
        } catch (TimeoutException | ExecutionException e) {
            return Rendered.BUSY;
        }
    }

    /** Renders a tile, sharing a render that another request or task already runs. */
    private Rendered renderShared(World world, WorldTiles wt, int zoom, int tx, int tz, Budget budget) {
        String key = key(wt.name, zoom, tx, tz);
        CompletableFuture<Rendered> mine = new CompletableFuture<>();
        CompletableFuture<Rendered> running = inflight.putIfAbsent(key, mine);
        if (running != null) {
            return await(running, Math.max(1, budget.deadline - System.currentTimeMillis()) + REQUEST_TIMEOUT_MS);
        }
        Rendered r = null;
        try {
            r = render(world, wt, zoom, tx, tz, budget);
            return r;
        } finally {
            mine.complete(r);
            inflight.remove(key, mine);
        }
    }

    private record Rendered(byte[] png, int[] argb, boolean partial, String etag) {
        /** Marks a render that could not run because the render slots stayed full. */
        static final Rendered BUSY = new Rendered(null, null, true, null);
    }

    private Rendered render(World world, WorldTiles wt, int zoom, int tx, int tz, Budget budget) {
        if (!allowed(wt.name, zoom, tx, tz)) {
            return null;
        }
        String key = key(wt.name, zoom, tx, tz);
        ByteLru.Entry cached = memory.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && (!cached.partial() || now - cached.createdAt() < partialTtl(key))) {
            return new Rendered(cached.data(), null, cached.partial(), cached.etag());
        }
        Path file = file(wt.name, zoom, tx, tz);
        if (Files.isRegularFile(file)) {
            try {
                byte[] data = Files.readAllBytes(file);
                // Keep the time the tile was drawn, not the time it was read, for the freshness check.
                ByteLru.Entry e = entry(data, false, Files.getLastModifiedTime(file).toMillis());
                memory.put(key, e);
                return new Rendered(data, null, false, e.etag());
            } catch (IOException ignored) {
                // Fall through and render again.
            }
        }
        if (world == null || !wt.explored.hasTile(zoom, tx, tz)) {
            return null;
        }
        int[] argb;
        boolean partial;
        if (zoom <= 0) {
            int[] missing = new int[1];
            argb = renderBase(world, wt, zoom, tx, tz, budget, missing);
            partial = missing[0] > 0;
            if (partial) {
                if (zoom == 0) {
                    queueCompletion(world, wt, tx, tz);
                } else if (!budget.limited) {
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
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                    int cx = tx * 2 + dx;
                    int cz = tz * 2 + dz;
                    if (!wt.explored.hasTile(zoom - 1, cx, cz)) {
                        continue;
                    }
                    Rendered child = renderShared(world, wt, zoom - 1, cx, cz, budget);
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
                        System.arraycopy(small, y * half, argb, (dz * half + y) * TILE + dx * half, half);
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
        ByteLru.Entry e = entry(png, partial, now);
        memory.put(key, e);
        if (!partial) {
            writeTile(wt.name, zoom, tx, tz, png, now);
        }
        return new Rendered(png, argb, partial, e.etag());
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
    private int[] renderBase(World world, WorldTiles wt, int zoom, int tx, int tz, Budget budget, int[] missing) {
        int[] out = new int[TILE * TILE];
        int cpt = ExploredIndex.chunksPerTile(zoom);
        int cpx = TILE / cpt;
        int baseX = tx * cpt;
        int baseZ = tz * cpt;
        WorldMapManager wmm = world.getWorldMapManager();
        boolean canRequest = mapEnabled(wmm);
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
                    MapImage img = inMemory(wmm, cx, cz);
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
                if (!bucket.tryTake(budget.background)) {
                    budget.limited = true;
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
            awaitAll(futures, budget.deadline);
            for (int i = 0; i < futures.size(); i++) {
                int[] w = waiting.get(i);
                MapImage img = doneImage(futures.get(i));
                if (img == null) {
                    // Not in time, failed, or the chunk exists but has no picture yet. Counted so
                    // the tile is finished later.
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

    private static boolean mapEnabled(WorldMapManager wmm) {
        if (wmm == null) {
            return false;
        }
        try {
            return wmm.isWorldMapEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static MapImage inMemory(WorldMapManager wmm, int cx, int cz) {
        try {
            return wmm.getImageIfInMemory(cx, cz);
        } catch (RuntimeException e) {
            // Treated as not in memory.
            return null;
        }
    }

    private static void awaitAll(List<CompletableFuture<MapImage>> futures, long deadline) {
        long remaining = Math.max(1, deadline - System.currentTimeMillis());
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException ignored) {
            // Use whatever finished in time.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static MapImage doneImage(CompletableFuture<MapImage> f) {
        if (!f.isDone() || f.isCompletedExceptionally()) {
            return null;
        }
        return f.getNow(null);
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

    // ---- background work ---------------------------------------------------

    /**
     * Waits until background work may take chunk image tokens. Returns false when the queue stops
     * or the thread is interrupted.
     */
    private boolean awaitBackgroundBudget() {
        syncBudget();
        while (!bucket.backgroundReady()) {
            if (queue.stopping()) {
                return false;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            syncBudget();
        }
        return !queue.stopping();
    }

    /**
     * Waits until the budget holds the background half plus the tokens needed, capped at half the
     * capacity so the wait can always end. Returns false when the queue stops.
     */
    private boolean awaitBackgroundTokens(int needed) {
        while (true) {
            syncBudget();
            int left = bucket.available();
            if (left < 0 || needed == 0) {
                return !queue.stopping();
            }
            int half = bucket.rate() / 2;
            if (left >= half + Math.min(needed, Math.max(1, bucket.rate() - half))) {
                return !queue.stopping();
            }
            if (queue.stopping()) {
                return false;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Renders a zoom 0 tile in the background until every chunk is in. Each tile gets a bounded
     * number of rounds. After that it is served as it is, marked partial, until it is invalidated
     * or the give up time passes. A round cut short by the image budget is not counted. Returns
     * false when the tile is out of rounds or the queue is full.
     */
    private boolean queueCompletion(World world, WorldTiles wt, int tx, int tz) {
        String key = key(wt.name, 0, tx, tz);
        Integer used = rounds.get(key);
        if (used != null && used >= MAX_COMPLETION_ROUNDS) {
            return false;
        }
        if (queue.isPending("c|" + key)) {
            return true;
        }
        RenderQueue.Offer offer = queue.offer("c|" + key, wt.name, () -> complete(world, wt, tx, tz));
        if (offer == RenderQueue.Offer.ADDED) {
            rounds.merge(key, 1, Integer::sum);
            return true;
        }
        return offer == RenderQueue.Offer.ALREADY_PENDING;
    }

    private void complete(World world, WorldTiles wt, int tx, int tz) {
        String key = key(wt.name, 0, tx, tz);
        if (!wt.explored.hasTile(0, tx, tz)) {
            rounds.remove(key);
            return;
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            if (!awaitBackgroundBudget()) {
                return;
            }
            long started = System.currentTimeMillis();
            Budget b = new Budget(CHUNKS_PER_TILE * CHUNKS_PER_TILE, 20_000, true);
            int[] missing = new int[1];
            int[] argb = renderBase(world, wt, 0, tx, tz, b, missing);
            if (missing[0] == 0) {
                byte[] png = encodePng(argb);
                if (png != null) {
                    memory.put(key, entry(png, false, started));
                    writeTile(wt.name, 0, tx, tz, png, started);
                    rounds.remove(key);
                    invalidateRelated(wt, tx, tz, true);
                }
                return;
            }
            if (b.limited) {
                // The budget ran dry mid way. This round does not count against the tile.
                rounds.computeIfPresent(key, (k, v) -> v > 1 ? v - 1 : null);
                return;
            }
        }
    }

    /**
     * Finds old zoom 0 tiles on disk and queues a check of each against the current chunk images.
     * Tiles drawn from images that have not changed are kept and dated again.
     */
    private void queueAgeChecks(World world, WorldTiles wt) {
        int hours = tileMaxAgeHours();
        if (hours <= 0) {
            return;
        }
        Path zoomDir = dir.resolve(TileKeys.safe(wt.name)).resolve("0");
        if (!Files.isDirectory(zoomDir)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - hours * 3_600_000L;
        List<long[]> old = new ArrayList<>();
        try (var list = Files.list(zoomDir)) {
            for (Path p : (Iterable<Path>) list::iterator) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".png")) {
                    continue;
                }
                long[] xz = parseTileName(n.substring(0, n.length() - 4));
                if (xz == null) {
                    continue;
                }
                long at = Files.getLastModifiedTime(p).toMillis();
                if (at < cutoff) {
                    old.add(new long[]{at, xz[0], xz[1]});
                }
            }
        } catch (IOException | RuntimeException e) {
            return;
        }
        old.sort(Comparator.comparingLong(a -> a[0]));
        int queued = 0;
        for (long[] o : old) {
            if (queued >= AGE_CHECKS_PER_PASS) {
                break;
            }
            int tx = (int) o[1];
            int tz = (int) o[2];
            if (!wt.explored.hasTile(0, tx, tz)) {
                continue;
            }
            RenderQueue.Offer offer = queue.offer("a|" + key(wt.name, 0, tx, tz), wt.name, () -> ageCheck(world, wt, tx, tz));
            if (offer == RenderQueue.Offer.FULL) {
                break;
            }
            queued++;
        }
    }

    static long[] parseTileName(String base) {
        int us = base.indexOf('_', 1);
        if (us < 0) {
            return null;
        }
        try {
            return new long[]{Integer.parseInt(base.substring(0, us)), Integer.parseInt(base.substring(us + 1))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Compares an old zoom 0 tile with the chunk images it is drawn from. The images are fetched
     * within the background budget and hashed together. A matching sum only dates the tile again.
     * A different or missing sum draws the tile from those images, and listeners hear about it
     * when the picture changed.
     */
    private void ageCheck(World world, WorldTiles wt, int tx, int tz) {
        Path png = file(wt.name, 0, tx, tz);
        if (!Files.isRegularFile(png) || !wt.explored.hasTile(0, tx, tz)) {
            return;
        }
        WorldMapManager wmm = world.getWorldMapManager();
        if (!mapEnabled(wmm)) {
            return;
        }
        long started = System.currentTimeMillis();
        int baseX = tx * CHUNKS_PER_TILE;
        int baseZ = tz * CHUNKS_PER_TILE;
        List<int[]> chunks = new ArrayList<>();
        List<MapImage> held = new ArrayList<>();
        int needed = 0;
        for (int dz = 0; dz < CHUNKS_PER_TILE; dz++) {
            for (int dx = 0; dx < CHUNKS_PER_TILE; dx++) {
                int cx = baseX + dx;
                int cz = baseZ + dz;
                if (!wt.explored.hasChunk(cx, cz) || !wt.explored.hasRing(cx, cz)) {
                    continue;
                }
                MapImage img = inMemory(wmm, cx, cz);
                chunks.add(new int[]{cx, cz});
                held.add(img);
                if (img == null) {
                    needed++;
                }
            }
        }
        if (!awaitBackgroundTokens(needed)) {
            return;
        }
        List<CompletableFuture<MapImage>> futures = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            MapImage img = held.get(i);
            if (img != null) {
                futures.add(CompletableFuture.completedFuture(img));
                continue;
            }
            // The tokens were reserved above, so these takes do not stop at the background floor.
            bucket.tryTake(false);
            CompletableFuture<MapImage> f = requestImage(world, wt, wmm, chunks.get(i)[0], chunks.get(i)[1]);
            if (f == null) {
                return;
            }
            futures.add(f);
        }
        awaitAll(futures, System.currentTimeMillis() + 20_000);
        long sum = 0xcbf29ce484222325L;
        List<MapImage> images = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            MapImage img = doneImage(futures.get(i));
            if (img == null) {
                return;
            }
            images.add(img);
            sum = sum * 31 + chunks.get(i)[0];
            sum = sum * 31 + chunks.get(i)[1];
            sum = sum * 1_000_003L ^ MapImageDecoder.hash(img);
        }
        String hex = Long.toHexString(sum);
        Path sumFile = sumFile(wt.name, tx, tz);
        String stored = null;
        try {
            if (Files.isRegularFile(sumFile)) {
                stored = Files.readString(sumFile, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException ignored) {
            // Treated as missing.
        }
        if (hex.equals(stored)) {
            touch(png, started);
            touch(sumFile, started);
            return;
        }
        // Draw from the images just fetched, so the pixel cache must not serve older pictures.
        for (int i = 0; i < chunks.size(); i++) {
            int[] c = chunks.get(i);
            dropPixels(wt.name, c[0], c[1]);
            toChunkPixels(wt.name, CHUNK_PX, c[0], c[1], images.get(i));
        }
        int[] missing = new int[1];
        int[] argb = renderBase(world, wt, 0, tx, tz, new Budget(CHUNKS_PER_TILE * CHUNKS_PER_TILE, 20_000, true), missing);
        if (missing[0] > 0) {
            return;
        }
        byte[] data = encodePng(argb);
        if (data == null) {
            return;
        }
        byte[] before = null;
        try {
            before = Files.readAllBytes(png);
        } catch (IOException ignored) {
            // Treated as changed.
        }
        boolean changed = before == null || !Arrays.equals(before, data);
        memory.put(key(wt.name, 0, tx, tz), entry(data, false, started));
        writeTile(wt.name, 0, tx, tz, data, started);
        try {
            Json.writeAtomic(sumFile, hex.getBytes(StandardCharsets.UTF_8), started);
        } catch (IOException e) {
            noteDiskFailure(e);
        }
        if (changed) {
            invalidateRelated(wt, tx, tz, true);
        }
    }

    private static void touch(Path p, long at) {
        try {
            if (Files.exists(p)) {
                Files.setLastModifiedTime(p, FileTime.fromMillis(at));
            }
        } catch (IOException ignored) {
            // Checked again on the next pass.
        }
    }

    /**
     * Every few seconds, when the map has had no tile request for thirty seconds, queues one
     * explored tile that has no disk copy yet. Zoom 0 tiles come first, nearest the origin first,
     * then the upper zooms, which are cheap once zoom 0 exists. Only one such render runs at a time,
     * only while the queue is otherwise empty and only while the image budget is above half.
     */
    private void idleTick() {
        if (!preRenderIdle() || System.currentTimeMillis() - lastTileRequest < IDLE_MS
                || queue.paused() || !queue.idle()) {
            return;
        }
        syncBudget();
        if (!bucket.backgroundReady()) {
            return;
        }
        Universe u = Universe.get();
        if (u == null) {
            return;
        }
        List<World> list = new ArrayList<>(u.getWorlds().values());
        list.removeIf(w -> !worldOn(w.getName()));
        if (list.isEmpty()) {
            return;
        }
        list.sort(Comparator.comparing(World::getName));
        int perWorld = IDLE_SCAN_PER_TICK / list.size() + 1;
        for (int i = 0; i < list.size(); i++) {
            World w = list.get((idleWorld + i) % list.size());
            WorldTiles wt = worldTiles(w);
            if (wt.explored.refreshedAt() == 0) {
                continue;
            }
            int[] pick = nextMissing(wt, perWorld);
            if (pick == null) {
                continue;
            }
            idleWorld = (idleWorld + i + 1) % list.size();
            int zoom = pick[0];
            int x = pick[1];
            int z = pick[2];
            queue.offer("p|" + key(wt.name, zoom, x, z), wt.name, () -> {
                if (!awaitBackgroundBudget()) {
                    return;
                }
                renderShared(w, wt, zoom, x, z, new Budget(CHUNKS_PER_TILE * CHUNKS_PER_TILE, 20_000, true));
            });
            return;
        }
    }

    /** The next tile of a world that has no disk copy, scanning at most limit tiles, or null. */
    private int[] nextMissing(WorldTiles wt, int limit) {
        synchronized (wt) {
            int size = wt.explored.size();
            if (wt.idleBuiltFor != size) {
                List<int[]> all = new ArrayList<>();
                for (int zoom = 0; zoom <= MAX_ZOOM; zoom++) {
                    List<int[]> level = new ArrayList<>();
                    for (long k : wt.explored.tiles(zoom)) {
                        level.add(new int[]{zoom, TileKeys.x(k), TileKeys.z(k)});
                    }
                    level.sort(Comparator.comparingLong(t -> (long) t[1] * t[1] + (long) t[2] * t[2]));
                    all.addAll(level);
                }
                wt.idleList = all;
                wt.idleCursor = 0;
                wt.idleBuiltFor = size;
            }
            int scanned = 0;
            while (wt.idleCursor < wt.idleList.size() && scanned < limit) {
                int[] t = wt.idleList.get(wt.idleCursor);
                scanned++;
                ByteLru.Entry e = memory.get(key(wt.name, t[0], t[1], t[2]));
                wt.idleCursor++;
                if ((e == null || e.partial()) && allowed(wt.name, t[0], t[1], t[2])
                        && !Files.isRegularFile(file(wt.name, t[0], t[1], t[2]))) {
                    // Each tile is tried once per scan. A partial result is finished by the
                    // completion queue, and the scan starts over when the index changes.
                    return t;
                }
            }
            return null;
        }
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
            if (!worldOn(world.getName())) {
                continue;
            }
            WorldTiles wt = worldTiles(world);
            runControl("dirty", () -> refreshWorld(world, wt, force, now));
        }
    }

    private void refreshWorld(World world, WorldTiles wt, boolean force, long now) {
        if (wt.refreshing) {
            return;
        }
        boolean dirtyDue = !wt.dirtyChunks.isEmpty() && (force || now - wt.lastRefresh >= REFRESH_MS);
        if (!dirtyDue && wt.freshChunks.isEmpty()) {
            return;
        }
        wt.refreshing = true;
        try {
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
            applyDirty(world, wt, dirty, true);
            applyDirty(world, wt, fresh, false);
        } finally {
            wt.refreshing = false;
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
            int cx = TileKeys.x(k);
            int cz = TileKeys.z(k);
            engineIdx.add(ChunkUtil.indexChunk(cx, cz));
            if (clearEngineImages) {
                dropPixels(wt.name, cx, cz);
            }
            baseTiles.merge(TileKeys.baseTileOfChunk(k), e.getValue(), Math::max);
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
            int tx = TileKeys.x(t);
            int tz = TileKeys.z(t);
            String key = key(wt.name, 0, tx, tz);
            if (renderedSince(wt, tx, tz, bt.getValue())) {
                continue;
            }
            memory.remove(key);
            deleteTile(wt.name, 0, tx, tz);
            for (int[] r : related(tx, tz)) {
                rounds.remove(key(wt.name, r[0], r[1], r[2]));
            }
            invalidateRelated(wt, tx, tz, false);
            if (!wt.explored.hasTile(0, tx, tz)) {
                // Every chunk of the tile is gone. Tell browsers so the picture disappears.
                invalidateRelated(wt, tx, tz, true);
                continue;
            }
            if (!queueCompletion(world, wt, tx, tz)) {
                // The queue is full. Try again on the next round.
                for (Map.Entry<Long, Long> e : chunkTimes.entrySet()) {
                    long k = e.getKey();
                    if (TileKeys.baseTileOfChunk(k) == t) {
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
    private boolean renderedSince(WorldTiles wt, int tx, int tz, long since) {
        if (since == Long.MAX_VALUE) {
            return false;
        }
        for (int[] r : related(tx, tz)) {
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
    private Boolean renderedAtOrAfter(WorldTiles wt, int zoom, int x, int z, long since) {
        ByteLru.Entry cached = memory.get(key(wt.name, zoom, x, z));
        if (cached != null) {
            return !cached.partial() && cached.createdAt() >= since;
        }
        try {
            Path f = file(wt.name, zoom, x, z);
            if (!Files.isRegularFile(f)) {
                return null;
            }
            return Files.getLastModifiedTime(f).toMillis() >= since;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Every tile that shows part of a zoom 0 tile, as [zoom, x, z]. That is the tile itself, its
     * four zoom -1 and sixteen zoom -2 tiles, and one parent at each zoom from 1 to 4.
     */
    static List<int[]> related(int tx, int tz) {
        return TileKeys.related(tx, tz);
    }

    /** Drops the cached lower and upper levels of a zoom 0 tile and tells listeners which tiles changed. */
    private void invalidateRelated(WorldTiles wt, int tx, int tz, boolean notify) {
        List<int[]> changed = new ArrayList<>();
        for (int[] r : related(tx, tz)) {
            if (r[0] != 0) {
                memory.remove(key(wt.name, r[0], r[1], r[2]));
                deleteTile(wt.name, r[0], r[1], r[2]);
            }
            if (r[0] == 0 || wt.explored.hasTile(r[0], r[1], r[2]) || !wt.explored.hasTile(0, tx, tz)) {
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

    private void dropPixels(String world, int cx, int cz) {
        synchronized (pixels) {
            for (int z = MIN_ZOOM; z <= 0; z++) {
                int[] old = pixels.remove(pixelKey(world, chunkPixels(z), cx, cz));
                if (old != null) {
                    pixelInts -= old.length;
                }
            }
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

    /**
     * Draws a tile of zoom 1 to 4 from the four tiles of the zoom below, as the live renderer does.
     * children holds the PNG bytes of the quarters in the order x then z (top left, top right, bottom
     * left, bottom right), and a null quarter stays transparent. Nothing is read from the world and no
     * chunk image is requested, so any thread may call it. Returns null when no quarter is given or
     * one of them is not a tile image.
     */
    public static byte[] composeUpper(byte[][] children) {
        if (children == null || children.length != 4) {
            return null;
        }
        int[] argb = new int[TILE * TILE];
        int half = TILE / 2;
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            if (children[i] == null) {
                continue;
            }
            int[] src = decodePng(children[i]);
            if (src == null) {
                return null;
            }
            any = true;
            int dx = i % 2;
            int dz = i / 2;
            int[] small = MapImageDecoder.half(src, TILE);
            for (int y = 0; y < half; y++) {
                System.arraycopy(small, y * half, argb, (dz * half + y) * TILE + dx * half, half);
            }
        }
        return any ? encodePng(argb) : null;
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
        } catch (IOException | RuntimeException e) {
            // ImageIO throws unchecked exceptions for some damaged files.
            return null;
        }
    }

    /** A strong ETag for tile bytes, the first 128 bits of their SHA-256 in hex, quoted. */
    public static String etag(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            return "\"" + HexFormat.of().formatHex(d, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            return "\"" + Integer.toHexString(Arrays.hashCode(data)) + "-" + data.length + "\"";
        }
    }

    private static ByteLru.Entry entry(byte[] png, boolean partial, long at) {
        return new ByteLru.Entry(png, partial, at, etag(png));
    }

    private static String key(String world, int z, int x, int y) {
        return TileKeys.tileKey(world, z, x, y);
    }

    private Path file(String world, int z, int x, int y) {
        return dir.resolve(TileKeys.safe(world)).resolve(Integer.toString(z)).resolve(x + "_" + y + ".png");
    }

    private Path sumFile(String world, int x, int z) {
        return dir.resolve(TileKeys.safe(world)).resolve("0").resolve(x + "_" + z + ".sum");
    }

    static String safe(String world) {
        return TileKeys.safe(world);
    }

    /**
     * Writes a tile and dates it to the start of its render, which the freshness check relies on.
     * A zoom 0 tile loses its image sum, since the sum belongs to the picture it replaces.
     */
    private void writeTile(String world, int zoom, int x, int z, byte[] png, long renderedAt) {
        if (!worlds.containsKey(world)) {
            // The world was dropped while this render ran. Writing would bring its folder back.
            return;
        }
        if (zoom == 0) {
            deleteQuietly(sumFile(world, x, z));
        }
        try {
            Json.writeAtomic(file(world, zoom, x, z), png, renderedAt);
        } catch (IOException e) {
            noteDiskFailure(e);
        }
    }

    private void noteDiskFailure(IOException e) {
        diskFailures.incrementAndGet();
        lastDiskFailureAt = System.currentTimeMillis();
        fail("disk", "Atlas could not write map tiles under %s because %s. The memory cache still serves them",
                dir.toAbsolutePath(), describe(e));
    }

    private void deleteTile(String world, int zoom, int x, int z) {
        deleteQuietly(file(world, zoom, x, z));
        if (zoom == 0) {
            deleteQuietly(sumFile(world, x, z));
        }
    }

    private static void deleteQuietly(Path file) {
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

    /** Background tasks waiting in the render queue. */
    public int queuedTiles() {
        return queue.size();
    }

    /** Whether the world has a tile folder or is loaded, for commands that take a world name. */
    public boolean knows(String world) {
        Universe u = Universe.get();
        return (u != null && u.getWorld(world) != null) || worlds.containsKey(world)
                || Files.isDirectory(dir.resolve(TileKeys.safe(Objects.requireNonNull(world))));
    }
}
