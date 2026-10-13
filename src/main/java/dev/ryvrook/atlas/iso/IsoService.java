package dev.ryvrook.atlas.iso;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.tiles.ExploredIndex;
import dev.ryvrook.atlas.tiles.TileKeys;
import dev.ryvrook.atlas.tiles.TileService;
import dev.ryvrook.atlas.util.Json;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Isometric views. Renders run one at a time on the single Atlas-iso thread. Browsers may hold at
 * most QUEUE_LIMIT renders in the queue, at most LARGE_LIMIT of them wider than 17 by 17 chunks, and
 * the thread rests LARGE_COOLDOWN_MS after a large one. Admin commands and the daily snapshot queue
 * without those limits, and a browser view waiting in the queue is drawn before them, so a long
 * daily snapshot never holds up the views people are waiting for. Every read of a chunk that is not
 * in memory takes a token from the shared chunk image budget as background work, so views never
 * starve the tiles people are looking at.
 */
public final class IsoService {
    static final int QUEUE_LIMIT = 8;
    static final int LARGE_LIMIT = 2;
    static final long LARGE_COOLDOWN_MS = 2_000;
    /** A render that cannot get its chunk reads within this time is drawn with what it has. */
    static final long DEADLINE_MS = 120_000;
    /** Views are drawn again at least once per bucket. */
    static final long BUCKET_MS = 6 * 60 * 60 * 1000L;
    /** A failed view answers 503 for this long before it is tried again. */
    static final long FAILURE_MS = 30_000;
    /**
     * A chunk a render loaded from disk is saved again by the engine when it unloads about half a
     * minute later, with nothing changed. Saves of such chunks within this time are ignored.
     */
    static final long OWN_LOAD_MS = 120_000;
    /** Pending saved chunks kept before the whole cache is dropped instead. */
    static final int DIRTY_LIMIT = 100_000;

    /** How a request was answered. */
    public enum Kind { READY, QUEUED, BUSY, LIMITED, FAILED }

    /** The answer to a request. entry is set when READY, etaSeconds when QUEUED, retryAfter otherwise. */
    public record Answer(Kind kind, IsoCache.Entry entry, int etaSeconds, int retryAfter) {
    }

    /** A finished render for a command or a snapshot. */
    public record Done(IsoView view, IsoCache.Entry entry, IsoRenderer.Result result) {
        public int width() {
            return result.width();
        }

        public int height() {
            return result.height();
        }

        /** Time spent reading, drawing and encoding. */
        public long ms() {
            return result.readMs() + result.drawMs() + result.encodeMs();
        }

        public boolean partial() {
            return entry.partial();
        }

        public byte[] png() {
            return entry.png();
        }

        public long renderedAt() {
            return entry.renderedAt();
        }
    }

    private static final AtomicLong SEQ = new AtomicLong();

    private static final class Job {
        final IsoView view;
        final long bucket;
        final boolean browser;
        final long seq = SEQ.incrementAndGet();
        volatile long startedAt;
        final CompletableFuture<Done> future = new CompletableFuture<>();

        Job(IsoView view, long bucket, boolean browser) {
            this.view = view;
            this.bucket = bucket;
            this.browser = browser;
        }
    }

    private final Atlas atlas;
    private final HytaleLogger logger;
    private final IsoCache cache;
    private final IsoRenderer.ChunkBudget budget;
    private final ThreadPoolExecutor exec;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, Long> failures = new ConcurrentHashMap<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    /** Chunks a render loaded, as world|cx|cz to the time until which their saves are ignored. */
    private final Map<String, Long> ownLoads = new ConcurrentHashMap<>();
    private final AtomicLong ignoredSaves = new AtomicLong();
    private volatile boolean dirtyOverflow;
    private final AtomicInteger browserJobs = new AtomicInteger();
    private final AtomicInteger largeJobs = new AtomicInteger();
    private final AtomicLong done = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong invalidated = new AtomicLong();
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();
    private volatile Palette palette;
    private volatile boolean paletteStale = true;
    private volatile double msPerChunk = 4.0;
    private volatile long cooldownUntil;
    private volatile String last = "none yet";
    private volatile long lastFailureLog;

    public IsoService(Atlas atlas, Path dataDir, HytaleLogger logger, IsoRenderer.ChunkBudget budget) {
        this.atlas = atlas;
        this.logger = logger;
        this.budget = budget;
        this.cache = new IsoCache(dataDir.resolve("iso"), mb(atlas.config().isoCacheMb()));
        this.exec = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "Atlas-iso");
            t.setDaemon(true);
            return t;
        });
    }

    /** The chunk image budget of the tile service, taken from as background work. */
    public static IsoRenderer.ChunkBudget budgetOf(TileService tiles) {
        return () -> tiles.budget().tryTake(true);
    }

    private static long mb(int megabytes) {
        return Math.max(1, megabytes) * 1024L * 1024L;
    }

    private AtlasConfig cfg() {
        return atlas.config();
    }

    /** Lists the disk cache and starts the upkeep on the Atlas scheduler. */
    public synchronized void start(ScheduledExecutorService scheduler) {
        cache.load();
        tasks.add(scheduler.scheduleWithFixedDelay(this::upkeepQuietly, 30, 30, TimeUnit.SECONDS));
        tasks.add(scheduler.scheduleWithFixedDelay(this::pruneQuietly, 60, 600, TimeUnit.SECONDS));
    }

    /** Stops the upkeep and the render thread, waiting up to five seconds for a running render. */
    public synchronized void stop() {
        for (ScheduledFuture<?> f : tasks) {
            f.cancel(false);
        }
        tasks.clear();
        exec.shutdownNow();
        try {
            exec.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Job j : jobs.values()) {
            j.future.cancel(false);
        }
    }

    /** The asset store loaded block types again. The colour table is rebuilt before the next render. */
    public void assetsChanged() {
        paletteStale = true;
    }

    /** Called from the chunk save system on a world thread. Cheap, the views are dropped later. */
    public void chunkSaved(String world, int cx, int cz) {
        String key = world + "|" + cx + "|" + cz;
        if (!ownLoads.isEmpty()) {
            Long until = ownLoads.get(key);
            if (until != null && until > System.currentTimeMillis()) {
                ignoredSaves.incrementAndGet();
                return;
            }
        }
        if (dirty.size() >= DIRTY_LIMIT) {
            dirtyOverflow = true;
            return;
        }
        dirty.add(key);
    }

    /** Drops cached views that hold a chunk saved since the last call. */
    void drainDirty() {
        if (dirtyOverflow) {
            dirtyOverflow = false;
            dirty.clear();
            for (String w : new ArrayList<>(atlas.tiles().exploredCounts().keySet())) {
                cache.dropWorld(w);
            }
            return;
        }
        if (dirty.isEmpty()) {
            return;
        }
        for (Iterator<String> it = dirty.iterator(); it.hasNext(); ) {
            String k = it.next();
            it.remove();
            int b = k.lastIndexOf('|');
            int a = k.lastIndexOf('|', b - 1);
            try {
                invalidated.addAndGet(cache.invalidate(k.substring(0, a), Integer.parseInt(k.substring(a + 1, b)),
                        Integer.parseInt(k.substring(b + 1))));
            } catch (NumberFormatException ignored) {
                // Not a key this class wrote.
            }
        }
    }

    private void upkeepQuietly() {
        try {
            drainDirty();
            cache.setMemoryMax(mb(cfg().isoCacheMb()));
            long now = System.currentTimeMillis();
            failures.values().removeIf(at -> now - at > FAILURE_MS);
            ownLoads.values().removeIf(until -> until <= now);
        } catch (RuntimeException e) {
            logger.at(Level.FINE).withCause(e).log("Atlas isometric upkeep failed");
        }
    }

    private void pruneQuietly() {
        try {
            drainDirty();
            int n = cache.prune(bucket(System.currentTimeMillis()), cfg().isoDiskMb() * 1024L * 1024L);
            if (n > 0) {
                logger.at(Level.FINE).log("Atlas removed %d isometric views from the disk cache", n);
            }
        } catch (RuntimeException e) {
            logger.at(Level.FINE).withCause(e).log("Atlas could not prune the isometric cache");
        }
    }

    static long bucket(long now) {
        return now / BUCKET_MS;
    }

    // ---- area --------------------------------------------------------------

    /** Chunks a view of the world may read, explored chunks inside the render limit. */
    IsoRenderer.ChunkFilter filter(String world) {
        ExploredIndex ex = atlas.tiles().explored(world);
        AtlasConfig.RenderLimit limit = cfg().world(world).renderLimit;
        return (cx, cz) -> ex.hasChunk(cx, cz)
                && (limit == null || limit.touches(cx * 32.0, cz * 32.0, cx * 32.0 + 31, cz * 32.0 + 31));
    }

    /** True when at least one chunk of the view may be read. */
    public boolean anyAllowed(IsoView v) {
        IsoRenderer.ChunkFilter f = filter(v.world());
        for (int dz = -v.radius(); dz <= v.radius(); dz++) {
            for (int dx = -v.radius(); dx <= v.radius(); dx++) {
                if (f.allowed(v.cx() + dx, v.cz() + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- requests ----------------------------------------------------------

    /**
     * Answers a browser request. A cached view is READY, a view being drawn is QUEUED with an
     * estimate. Otherwise admit is asked whether this address may start a render, LIMITED when not,
     * and the render is queued unless the queue is full (BUSY). A view that just failed is FAILED.
     */
    public Answer request(IsoView v, java.util.function.LongSupplier admit) {
        drainDirty();
        long now = System.currentTimeMillis();
        long b = bucket(now);
        IsoCache.Entry e = cache.get(v, b, now);
        if (e != null) {
            return new Answer(Kind.READY, e, 0, 0);
        }
        String key = IsoCache.key(IsoCache.folder(v.world()), v, b);
        Job running = jobs.get(key);
        if (running != null) {
            return new Answer(Kind.QUEUED, null, eta(running), 0);
        }
        Long failedAt = failures.get(key);
        if (failedAt != null && now - failedAt < FAILURE_MS) {
            return new Answer(Kind.FAILED, null, 0, (int) Math.max(1, (FAILURE_MS - (now - failedAt)) / 1000));
        }
        synchronized (this) {
            running = jobs.get(key);
            if (running != null) {
                return new Answer(Kind.QUEUED, null, eta(running), 0);
            }
            if (browserJobs.get() >= QUEUE_LIMIT || (v.large() && largeJobs.get() >= LARGE_LIMIT)) {
                return new Answer(Kind.BUSY, null, 0, Math.max(2, etaAll()));
            }
            long wait = admit.getAsLong();
            if (wait > 0) {
                return new Answer(Kind.LIMITED, null, 0, (int) Math.min(Integer.MAX_VALUE, wait));
            }
            e = cache.get(v, b, now);
            if (e != null) {
                // Drawn and cached between the first look and here.
                return new Answer(Kind.READY, e, 0, 0);
            }
            Job j = new Job(v, b, true);
            if (!submit(key, j)) {
                return new Answer(Kind.BUSY, null, 0, 5);
            }
            return new Answer(Kind.QUEUED, null, eta(j), 0);
        }
    }

    /** Renders a view for a command or a snapshot, without the browser limits, and caches it too. */
    public CompletableFuture<Done> render(IsoView v) {
        long b = bucket(System.currentTimeMillis());
        Job j = new Job(v, b, false);
        String key = IsoCache.key(IsoCache.folder(v.world()), v, b) + "#direct" + System.nanoTime();
        if (!submit(key, j)) {
            j.future.completeExceptionally(new RejectedExecutionException("Atlas is stopping"));
        }
        return j.future;
    }

    private boolean submit(String key, Job j) {
        jobs.put(key, j);
        if (j.browser) {
            browserJobs.incrementAndGet();
        }
        if (j.view.large()) {
            largeJobs.incrementAndGet();
        }
        try {
            exec.execute(new Task(key, j));
            return true;
        } catch (RejectedExecutionException e) {
            finish(key, j);
            return false;
        }
    }

    /** A queued render. Browser views come first, then everything in the order it was queued. */
    private final class Task implements Runnable, Comparable<Task> {
        final String key;
        final Job job;

        Task(String key, Job job) {
            this.key = key;
            this.job = job;
        }

        @Override
        public void run() {
            IsoService.this.run(key, job);
        }

        @Override
        public int compareTo(Task o) {
            if (job.browser != o.job.browser) {
                return job.browser ? -1 : 1;
            }
            return Long.compare(job.seq, o.job.seq);
        }
    }

    private void finish(String key, Job j) {
        if (jobs.remove(key, j)) {
            if (j.browser) {
                browserJobs.decrementAndGet();
            }
            if (j.view.large()) {
                largeJobs.decrementAndGet();
            }
        }
    }

    private void run(String key, Job j) {
        try {
            long rest = cooldownUntil - System.currentTimeMillis();
            if (rest > 0) {
                Thread.sleep(rest);
            }
            j.startedAt = System.currentTimeMillis();
            World world = Universe.get() == null ? null : Universe.get().getWorld(j.view.world());
            if (world == null) {
                throw new IllegalStateException("the world " + j.view.world() + " is not loaded");
            }
            IsoRenderer.Result r = IsoRenderer.render(world, j.view, filter(j.view.world()), budget,
                    j.startedAt + DEADLINE_MS, palette(), idx -> ownLoads.put(j.view.world() + "|"
                            + com.hypixel.hytale.math.util.ChunkUtil.xOfChunkIndex(idx) + "|"
                            + com.hypixel.hytale.math.util.ChunkUtil.zOfChunkIndex(idx), System.currentTimeMillis() + OWN_LOAD_MS));
            long at = System.currentTimeMillis();
            IsoCache.Entry e = new IsoCache.Entry(r.png(), TileService.etag(r.png()), at, r.partial());
            try {
                cache.put(j.view, j.bucket, e, cfg().isoDiskMb() > 0);
            } catch (IOException io) {
                logger.at(Level.WARNING).log("Atlas could not write an isometric view to disk, %s", io.getMessage());
            }
            long ms = at - j.startedAt;
            msPerChunk = 0.7 * msPerChunk + 0.3 * ((double) ms / Math.max(1, j.view.chunks()));
            if (j.view.large()) {
                cooldownUntil = at + LARGE_COOLDOWN_MS;
            }
            done.incrementAndGet();
            last = describe(j.view) + " in " + ms + " ms, " + r.width() + " by " + r.height() + " pixels, "
                    + r.png().length / 1024 + " KB, " + r.read() + " of " + r.wanted() + " chunks read"
                    + (r.partial() ? ", partial" : "") + " (read " + r.readMs() + " ms, draw " + r.drawMs()
                    + " ms, encode " + r.encodeMs() + " ms)";
            logger.at(j.browser ? Level.FINE : Level.INFO).log("Atlas drew the isometric view %s", last);
            j.future.complete(new Done(j.view, e, r));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            j.future.cancel(false);
        } catch (Throwable t) {
            failed.incrementAndGet();
            failures.put(key, System.currentTimeMillis());
            long now = System.currentTimeMillis();
            if (now - lastFailureLog > 5 * 60_000L) {
                lastFailureLog = now;
                logger.at(Level.WARNING).withCause(t).log("Atlas could not draw the isometric view %s", describe(j.view));
            }
            j.future.completeExceptionally(t);
        } finally {
            finish(key, j);
        }
    }

    /** The colour table, built on first use and again after an asset reload. */
    private Palette palette() {
        Palette p = palette;
        if (p == null || paletteStale || p.size() != Math.min(Palette.MAX_IDS, BlockType.getAssetMap().getNextIndex())) {
            paletteStale = false;
            long t = System.nanoTime();
            p = Palette.fromAssets();
            palette = p;
            logger.at(Level.INFO).log("Atlas built the isometric colour table, %d block ids in %d ms", p.size(),
                    (System.nanoTime() - t) / 1_000_000);
        }
        return p;
    }

    static String describe(IsoView v) {
        return v.world() + " around " + v.blockX() + " " + v.blockZ() + " radius " + v.radius() + " direction "
                + v.azimuth() + " scale " + v.scale();
    }

    // ---- estimates ---------------------------------------------------------

    /** True when o is drawn before j or is j, the running render and the queue order of Task. */
    private static boolean ahead(Job o, Job j) {
        if (o == j || o.startedAt > 0) {
            return true;
        }
        if (o.browser != j.browser) {
            return o.browser;
        }
        return o.seq < j.seq;
    }

    /** Seconds until the job is likely done, counting the jobs drawn before it. */
    private int eta(Job j) {
        long now = System.currentTimeMillis();
        double ms = 0;
        int chunks = 0;
        for (Job o : jobs.values()) {
            if (!ahead(o, j)) {
                continue;
            }
            double est = estimate(o.view);
            if (o.startedAt > 0) {
                est = Math.max(200, est - (now - o.startedAt));
            } else {
                chunks += o.view.chunks();
            }
            ms += est;
        }
        ms += budgetWaitMs(chunks);
        return (int) Math.max(1, Math.ceil(ms / 1000.0));
    }

    private int etaAll() {
        double ms = 0;
        for (Job o : jobs.values()) {
            ms += estimate(o.view);
        }
        return (int) Math.max(1, Math.ceil(ms / 1000.0));
    }

    private double estimate(IsoView v) {
        return 300 + v.chunks() * msPerChunk + (v.large() ? LARGE_COOLDOWN_MS : 0);
    }

    /** How long the background half of the chunk budget needs to cover that many reads. */
    private double budgetWaitMs(int chunks) {
        TileService t = atlas.tiles();
        int rate = t.budgetPerMinute();
        int left = t.budgetLeft();
        if (rate <= 0 || left < 0) {
            return 0;
        }
        int usable = Math.max(0, left - rate / 2);
        int shortBy = Math.max(0, chunks - usable);
        return shortBy * 60_000.0 / rate;
    }

    // ---- status ------------------------------------------------------------

    /** Lines for /atlas iso status. */
    public List<String> status() {
        drainDirty();
        List<String> out = new ArrayList<>();
        AtlasConfig c = cfg();
        out.add("Isometric views are " + (c.isoEnabled() ? "on" : "off") + ", radius up to " + c.isoMaxRadius()
                + " chunks, " + c.isoRequestsPerMinute() + " new renders per minute and address.");
        Job current = null;
        for (Job j : jobs.values()) {
            if (j.startedAt > 0) {
                current = j;
            }
        }
        out.add("Queue " + jobs.size() + " (" + browserJobs.get() + " from browsers, " + largeJobs.get() + " large), "
                + (current == null ? "nothing drawing now." : "drawing " + describe(current.view) + "."));
        out.add("Done " + done.get() + ", failed " + failed.get() + ", dropped by chunk saves " + invalidated.get() + ", saves of chunks a render loaded ignored "
                + ignoredSaves.get()
                + ", about " + String.format(java.util.Locale.ROOT, "%.1f", msPerChunk) + " ms per chunk.");
        out.add("Last render " + last + ".");
        out.add("Memory " + cache.memoryEntries() + " views, " + cache.memoryBytes() / 1024 + " of "
                + cache.memoryMax() / 1024 + " KB. Disk " + cache.diskFiles() + " files, " + cache.diskBytes() / 1024
                + " of " + c.isoDiskMb() * 1024L + " KB.");
        int left = atlas.tiles().budgetLeft();
        Palette p = palette;
        out.add("Chunk budget " + (left < 0 ? "unlimited" : left + " of " + atlas.tiles().budgetPerMinute() + " left")
                + ". Colour table " + (p == null ? "not built yet" : p.size() + " ids, " + p.types() + " block types") + ".");
        return out;
    }

    /** Writes a finished render to the exports folder and returns the file. */
    public Path export(Done d) throws IOException {
        IsoView v = d.view();
        Path f = cache.dir().resolve("exports").resolve(TileKeys.safe(v.world()) + "_" + v.blockX() + "_" + v.blockZ()
                + "_r" + v.radius() + "_a" + v.azimuth() + "_s" + v.scale() + ".png");
        Json.writeAtomic(f, d.png(), -1);
        return f.toAbsolutePath();
    }

    /** Forgets the views of a world that went away. */
    public void dropWorld(String world) {
        cache.dropWorld(world);
    }
}
