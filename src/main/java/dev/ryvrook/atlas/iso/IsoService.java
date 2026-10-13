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
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Isometric views. Renders run one at a time on the single Atlas-iso thread. Browsers may hold at
 * most QUEUE_LIMIT renders in the queue, each address at most IsoQueue.PER_ADDRESS of them, and a
 * new view from an address at its cap replaces the oldest of its views that has not started. At
 * most LARGE_LIMIT large views (wider than 17 by 17 chunks) are admitted at once, a further one
 * waits its turn with a countdown, and the thread rests LARGE_COOLDOWN_MS after a large one. Admin
 * commands and the daily snapshot queue without those limits, and a browser view waiting in the
 * queue is drawn before them, so a long daily snapshot never holds up the views people are waiting
 * for. Every read of a chunk that is not in memory takes a token from the shared chunk image budget,
 * from the full bucket for browser views like a tile request and as background work for commands
 * and snapshots.
 *
 * <p>A chunk saved inside a cached view marks it stale and never drops it. A stale view is served at
 * once with a short cache time, and a check on the render thread compares the fingerprint of the
 * saved chunks with the one taken when the view was drawn. Only a real change draws the view again,
 * behind every browser view waiting and while the old picture is still served. That redraw takes
 * budget as foreground work, since someone is looking at the view, and a partial redraw never
 * replaces the complete picture.
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

    /**
     * The answer to a request. entry is set when READY, retryAfter when BUSY, LIMITED or FAILED.
     * ahead counts the views drawn before this one and etaSeconds estimates when it is done, for a
     * view that was not queued the drawing time of the views it would wait behind.
     */
    public record Answer(Kind kind, IsoCache.Entry entry, int etaSeconds, int retryAfter, int ahead, boolean stale) {
        Answer(Kind kind, IsoCache.Entry entry, int etaSeconds, int retryAfter, int ahead) {
            this(kind, entry, etaSeconds, retryAfter, ahead, false);
        }
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

    private static final class Job extends IsoQueue.Entry {
        final long bucket;
        final CompletableFuture<Done> future = new CompletableFuture<>();
        /** A check of a stale cached view, drawn again only when its area changed. */
        final boolean refresh;
        Task task;

        Job(String key, IsoView view, long bucket, String address) {
            this(key, view, bucket, address, false);
        }

        Job(String key, IsoView view, long bucket, String address, boolean refresh) {
            super(key, view, address);
            this.bucket = bucket;
            this.refresh = refresh;
        }
    }

    private final Atlas atlas;
    private final HytaleLogger logger;
    private final IsoCache cache;
    private final IsoRenderer.ChunkBudget budget;
    private final ThreadPoolExecutor exec;
    private final IsoQueue queue = new IsoQueue(QUEUE_LIMIT, LARGE_LIMIT);
    private final Map<String, Long> failures = new ConcurrentHashMap<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    /** Chunks a render loaded, as world|cx|cz to the time until which their saves are ignored. */
    private final Map<String, Long> ownLoads = new ConcurrentHashMap<>();
    private final AtomicLong ignoredSaves = new AtomicLong();
    private volatile boolean dirtyOverflow;
    private final AtomicLong done = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong markedStale = new AtomicLong();
    private final AtomicLong redrawnAfterChange = new AtomicLong();
    private final AtomicLong redrawnWithoutPrint = new AtomicLong();
    private final AtomicLong checkedUnchanged = new AtomicLong();
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

    /** The chunk image budget of the tile service, taken from as foreground or background work. */
    public static IsoRenderer.ChunkBudget budgetOf(TileService tiles) {
        return background -> tiles.budget().tryTake(background);
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
        for (IsoQueue.Entry e : queue.all()) {
            ((Job) e).future.cancel(false);
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

    /** Marks cached views that hold a chunk saved since the last call as stale. */
    void drainDirty() {
        if (dirtyOverflow) {
            dirtyOverflow = false;
            dirty.clear();
            // Too many saves to track, so the whole cache is dropped rather than kept with unknown changes.
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
                markedStale.addAndGet(cache.markStale(k.substring(0, a), Integer.parseInt(k.substring(a + 1, b)),
                        Integer.parseInt(k.substring(b + 1)), System.currentTimeMillis()));
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
     * Answers a browser request from the address with the rate key address. A cached view is READY,
     * a view being drawn or waiting is QUEUED with an estimate. Otherwise the view is offered to the
     * queue, which may replace the oldest waiting view of the same address, admit is asked whether
     * this address may start a render (LIMITED when not), and BUSY is answered only when the queue is
     * full of other views. A view that just failed is FAILED.
     */
    public Answer request(IsoView v, String address, java.util.function.LongSupplier admit) {
        drainDirty();
        long now = System.currentTimeMillis();
        long b = bucket(now);
        IsoCache.Entry e = cache.get(v, b, now);
        String key = IsoCache.key(IsoCache.folder(v.world()), v, b);
        if (e != null) {
            boolean stale = cache.isStale(v, b);
            if (stale) {
                refresh(v, b, key, now);
            }
            return new Answer(Kind.READY, e, 0, 0, 0, stale);
        }
        IsoQueue.Entry waiting = queue.get(key);
        if (waiting != null) {
            return queued(waiting);
        }
        Long failedAt = failures.get(key);
        if (failedAt != null && now - failedAt < FAILURE_MS) {
            return new Answer(Kind.FAILED, null, 0, (int) Math.max(1, (FAILURE_MS - (now - failedAt)) / 1000), 0);
        }
        synchronized (this) {
            waiting = queue.get(key);
            if (waiting != null) {
                return queued(waiting);
            }
            e = cache.get(v, b, now);
            if (e != null) {
                // Drawn and cached between the first look and here.
                return new Answer(Kind.READY, e, 0, 0, 0);
            }
            Job j = new Job(key, v, b, address == null ? "?" : address);
            List<IsoQueue.Entry> before = queue.aheadOfNew();
            IsoQueue.Offer o = queue.offer(j, admit);
            switch (o.outcome()) {
                case BUSY -> {
                    int ahead = before.size();
                    return new Answer(Kind.BUSY, null, seconds(before, true), Math.max(2, firstFree(before)), ahead);
                }
                case LIMITED -> {
                    return new Answer(Kind.LIMITED, null, seconds(before, true),
                            (int) Math.min(Integer.MAX_VALUE, o.waitSeconds()), before.size());
                }
                default -> {
                }
            }
            for (IsoQueue.Entry c : o.cancelled()) {
                Job cj = (Job) c;
                if (cj.task != null) {
                    exec.remove(cj.task);
                }
                cj.future.cancel(false);
            }
            for (IsoQueue.Entry p : o.promoted()) {
                execute((Job) p);
            }
            if (!j.deferred() && !execute(j)) {
                return new Answer(Kind.BUSY, null, 0, 5, 0);
            }
            return queued(j);
        }
    }

    /** Queues the check of a stale view once, unless it failed a moment ago. */
    private void refresh(IsoView v, long b, String key, long now) {
        Long failedAt = failures.get(key);
        if (failedAt != null && now - failedAt < FAILURE_MS) {
            return;
        }
        synchronized (this) {
            if (queue.get(key) != null) {
                return;
            }
            Job j = new Job(key, v, b, null, true);
            queue.addDirect(j);
            execute(j);
        }
    }

    private Answer queued(IsoQueue.Entry j) {
        List<IsoQueue.Entry> ahead = queue.ahead(j);
        return new Answer(Kind.QUEUED, null, seconds(ahead, j.browser()), 0, Math.max(0, ahead.size() - 1));
    }

    /** Renders a view for a command or a snapshot, without the browser limits, and caches it too. */
    public CompletableFuture<Done> render(IsoView v) {
        long b = bucket(System.currentTimeMillis());
        String key = IsoCache.key(IsoCache.folder(v.world()), v, b) + "#direct" + System.nanoTime();
        Job j = new Job(key, v, b, null);
        queue.addDirect(j);
        if (!execute(j)) {
            j.future.completeExceptionally(new RejectedExecutionException("Atlas is stopping"));
        }
        return j.future;
    }

    /** Hands a queued job to the render thread. False when the thread is stopping. */
    private boolean execute(Job j) {
        j.task = new Task(j);
        try {
            exec.execute(j.task);
            return true;
        } catch (RejectedExecutionException e) {
            finish(j);
            return false;
        }
    }

    /** A queued render, in the order of IsoQueue. */
    private final class Task implements Runnable, Comparable<Task> {
        final Job job;

        Task(Job job) {
            this.job = job;
        }

        @Override
        public void run() {
            IsoService.this.run(job);
        }

        @Override
        public int compareTo(Task o) {
            return IsoQueue.ORDER.compare(job, o.job);
        }
    }

    private void finish(Job j) {
        for (IsoQueue.Entry p : queue.finish(j)) {
            execute((Job) p);
        }
    }

    private void run(Job j) {
        try {
            long rest = cooldownUntil - System.currentTimeMillis();
            if (rest > 0) {
                Thread.sleep(rest);
            }
            if (!queue.start(j, System.currentTimeMillis())) {
                // Replaced by a newer view of the same address.
                return;
            }
            long startedAt = j.startedAt();
            World world = Universe.get() == null ? null : Universe.get().getWorld(j.view.world());
            if (world == null) {
                throw new IllegalStateException("the world " + j.view.world() + " is not loaded");
            }
            if (j.refresh) {
                Map<Long, Long> saved = cache.staleChunks(j.view, j.bucket);
                if (saved.isEmpty()) {
                    j.future.complete(null);
                    return;
                }
                IsoCache.Print print = cache.print(j.view, j.bucket);
                if (print != null && IsoRenderer.unchanged(world, j.view, print, saved.keySet(), filter(j.view.world()),
                        budget, palette())) {
                    cache.clearStale(j.view, j.bucket, saved);
                    checkedUnchanged.incrementAndGet();
                    j.future.complete(null);
                    return;
                }
                (print == null ? redrawnWithoutPrint : redrawnAfterChange).incrementAndGet();
            }
            IsoRenderer.Result r = IsoRenderer.render(world, j.view, filter(j.view.world()), budget, !j.browser() && !j.refresh,
                    startedAt + DEADLINE_MS, palette(), idx -> ownLoads.put(j.view.world() + "|"
                            + com.hypixel.hytale.math.util.ChunkUtil.xOfChunkIndex(idx) + "|"
                            + com.hypixel.hytale.math.util.ChunkUtil.zOfChunkIndex(idx), System.currentTimeMillis() + OWN_LOAD_MS));
            long at = System.currentTimeMillis();
            if (j.refresh && r.partial()) {
                // The old complete picture stays and stays stale, the next request after a pause tries again.
                failures.put(j.key, at);
                j.future.complete(null);
                return;
            }
            IsoCache.Entry e = new IsoCache.Entry(r.png(), TileService.etag(r.png()), at, r.partial());
            try {
                cache.put(j.view, j.bucket, e, cfg().isoDiskMb() > 0, r.print(), startedAt);
            } catch (IOException io) {
                logger.at(Level.WARNING).log("Atlas could not write an isometric view to disk, %s", io.getMessage());
            }
            long ms = at - startedAt;
            msPerChunk = 0.7 * msPerChunk + 0.3 * ((double) ms / Math.max(1, j.view.chunks()));
            if (j.view.large()) {
                cooldownUntil = at + LARGE_COOLDOWN_MS;
            }
            done.incrementAndGet();
            last = describe(j.view) + " in " + ms + " ms, " + r.width() + " by " + r.height() + " pixels, "
                    + r.png().length / 1024 + " KB, " + r.read() + " of " + r.wanted() + " chunks read"
                    + (r.partial() ? ", partial" : "") + " (read " + r.readMs() + " ms, draw " + r.drawMs()
                    + " ms, encode " + r.encodeMs() + " ms)";
            logger.at(j.browser() || j.refresh ? Level.FINE : Level.INFO).log("Atlas drew the isometric view %s", last);
            j.future.complete(new Done(j.view, e, r));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            j.future.cancel(false);
        } catch (Throwable t) {
            failed.incrementAndGet();
            failures.put(j.key, System.currentTimeMillis());
            long now = System.currentTimeMillis();
            if (now - lastFailureLog > 5 * 60_000L) {
                lastFailureLog = now;
                logger.at(Level.WARNING).withCause(t).log("Atlas could not draw the isometric view %s", describe(j.view));
            }
            j.future.completeExceptionally(t);
        } finally {
            finish(j);
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

    /**
     * Seconds until the last of these views is likely done, the running one counted with what is
     * left of it. foreground tells whether the chunk reads may use the full budget.
     */
    private int seconds(List<IsoQueue.Entry> views, boolean foreground) {
        long now = System.currentTimeMillis();
        double ms = 0;
        int chunks = 0;
        for (IsoQueue.Entry o : views) {
            double est = estimate(o.view);
            long started = o.startedAt();
            if (started > 0) {
                est = Math.max(200, est - (now - started));
            } else {
                chunks += o.view.chunks();
            }
            ms += est;
        }
        ms += budgetWaitMs(chunks, foreground);
        return (int) Math.max(1, Math.ceil(ms / 1000.0));
    }

    /** Seconds until the first of these views is likely done and frees a place in the queue. */
    private int firstFree(List<IsoQueue.Entry> views) {
        List<IsoQueue.Entry> l = new ArrayList<>(views);
        l.sort(IsoQueue.ORDER);
        return l.isEmpty() ? 1 : seconds(l.subList(0, 1), true);
    }

    private double estimate(IsoView v) {
        return 300 + v.chunks() * msPerChunk + (v.large() ? LARGE_COOLDOWN_MS : 0);
    }

    /**
     * How long the chunk budget needs to cover that many reads, the full bucket for foreground
     * work and the upper half for background work.
     */
    private double budgetWaitMs(int chunks, boolean foreground) {
        TileService t = atlas.tiles();
        int rate = t.budgetPerMinute();
        int left = t.budgetLeft();
        if (rate <= 0 || left < 0) {
            return 0;
        }
        int usable = Math.max(0, foreground ? left : left - rate / 2);
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
        IsoQueue.Entry current = null;
        for (IsoQueue.Entry j : queue.all()) {
            if (j.startedAt() > 0) {
                current = j;
            }
        }
        out.add("Queue " + queue.size() + " (" + queue.browserCount() + " from browsers at " + queue.addresses()
                + (queue.addresses() == 1 ? " address, " : " addresses, ") + queue.largeCount() + " large, "
                + queue.deferredCount() + " waiting for a large slot), "
                + (current == null ? "nothing drawing now." : "drawing " + describe(current.view) + "."));
        out.add("Done " + done.get() + ", failed " + failed.get() + ", replaced by newer views " + queue.cancelledTotal()
                + ", marked stale by chunk saves " + markedStale.get() + ", re-rendered after a real change "
                + redrawnAfterChange.get() + ", re-rendered without a fingerprint " + redrawnWithoutPrint.get()
                + ", found unchanged " + checkedUnchanged.get()
                + ", saves of chunks a render loaded ignored "
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
