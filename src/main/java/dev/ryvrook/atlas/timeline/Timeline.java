package dev.ryvrook.atlas.timeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.data.KillLog;
import dev.ryvrook.atlas.data.LiveState;
import dev.ryvrook.atlas.data.Payloads;
import dev.ryvrook.atlas.tiles.TileService;
import dev.ryvrook.atlas.util.Json;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The timeline. Takes one snapshot per world per day at timeline.hour server local time, or when an
 * admin asks, keeps the event log, collects the numbers for each day's stats and prunes old days.
 *
 * <p>Everything runs on the Atlas scheduler. A snapshot reads and hashes BATCH tiles per step with
 * STEP_DELAY_MS between steps, so the scheduler stays free for the live map. Nothing here touches a
 * world thread.
 */
public final class Timeline {
    static final int BATCH = 128;
    static final long STEP_DELAY_MS = 25;
    static final long FLUSH_SECONDS = 5;
    /** A daily snapshot missed while the server was down runs this long after start. */
    static final long CATCH_UP_DELAY_MS = 5 * 60 * 1000L;
    static final int FEATS_TOP = 10;

    private final Atlas atlas;
    private final HytaleLogger logger;
    private final ZoneId zone = ZoneId.systemDefault();
    private final TimelineStore store;
    private final EventLog events;
    private final ActivityTracker activity;
    private final Path stateFile;
    private final long startedAt = System.currentTimeMillis();
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();
    private volatile ScheduledExecutorService scheduler;

    private record Request(String world, Consumer<String> reply, boolean daily) {
    }

    /** Guarded by this. */
    private final ArrayDeque<Request> pending = new ArrayDeque<>();
    private SnapshotJob job;
    private Request jobRequest;
    /** True while a prune runs. No snapshot starts then, so a prune never collects a blob a new day is about to use. */
    private boolean pruning;
    private String lastDaily;
    private long lastFlushFailureLog;

    public Timeline(Atlas atlas, Path dataDir, HytaleLogger logger) {
        this.atlas = atlas;
        this.logger = logger;
        Path root = dataDir.resolve("snapshots");
        this.store = new TimelineStore(root);
        this.events = new EventLog(store, zone, EventLog.MAX_PER_DAY,
                () -> cfg().timelineEnabled() && cfg().timelineEvents());
        this.activity = new ActivityTracker(root.resolve("activity.json"));
        this.stateFile = root.resolve("state.json");
        events.setListener(l -> {
            if ("worldevent.start".equals(l.kind())) {
                activity.worldEvent(l.world(), l.at());
            }
        });
    }

    private AtlasConfig cfg() {
        return atlas.config();
    }

    public TimelineStore store() {
        return store;
    }

    public EventLog events() {
        return events;
    }

    public ActivityTracker activity() {
        return activity;
    }

    public ZoneId zone() {
        return zone;
    }

    public String today() {
        return LocalDate.now(zone).toString();
    }

    /** Starts the periodic work and installs the event log behind EventLog.record. */
    public synchronized void start(ScheduledExecutorService ex) {
        scheduler = ex;
        activity.load();
        loadState();
        EventLog.install(events);
        tasks.add(ex.scheduleWithFixedDelay(guard("timeline events", this::flushEvents), FLUSH_SECONDS, FLUSH_SECONDS,
                TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("timeline", this::tick), 20, 60, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("timeline save", this::saveActivity), 300, 300, TimeUnit.SECONDS));
        ex.schedule(guard("timeline prune", this::prune), 30, TimeUnit.SECONDS);
    }

    /** Stops the periodic work, abandons a running snapshot and writes what is queued. */
    public synchronized void stop() {
        for (ScheduledFuture<?> f : tasks) {
            f.cancel(false);
        }
        tasks.clear();
        scheduler = null;
        if (EventLog.current() == events) {
            EventLog.install(null);
        }
        job = null;
        jobRequest = null;
        pending.clear();
        flushEvents();
        saveActivity();
    }

    private Runnable guard(String name, Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable t) {
                logger.at(Level.WARNING).withCause(t).log("Atlas task %s failed", name);
            }
        };
    }

    private void flushEvents() {
        try {
            events.flush();
        } catch (IOException e) {
            long now = System.currentTimeMillis();
            if (now - lastFlushFailureLog > 5 * 60 * 1000L) {
                lastFlushFailureLog = now;
                logger.at(Level.WARNING).log("Atlas could not write the timeline event log because %s", e.getMessage());
            }
        }
    }

    private void saveActivity() {
        try {
            activity.prune(System.currentTimeMillis());
            activity.save();
        } catch (IOException e) {
            logger.at(Level.FINE).withCause(e).log("Atlas could not save the timeline activity counters");
        }
    }

    /** A death from the death system, for the kill and death counts. */
    public void death(KillLog.Kill k) {
        if (k == null || k.world() == null) {
            return;
        }
        activity.death(k.world(), k.killer() != null && k.killer().player(), k.victim() != null && k.victim().player(),
                k.at() > 0 ? k.at() : System.currentTimeMillis());
    }

    // ---- schedule ----------------------------------------------------------

    /** Every minute. Samples who is online and starts the daily snapshot when it is due. */
    private void tick() {
        long now = System.currentTimeMillis();
        Map<String, List<String>> online = new HashMap<>();
        for (LiveState.PlayerSample p : atlas.live().players()) {
            if (p.world() != null && p.uuid() != null) {
                online.computeIfAbsent(p.world(), k -> new ArrayList<>()).add(p.uuid().toString());
            }
        }
        for (String w : atlas.live().worlds().keySet()) {
            activity.online(w, online.getOrDefault(w, List.of()), now);
        }
        if (dailyDue(now)) {
            runDaily();
        }
    }

    private synchronized boolean dailyDue(long now) {
        AtlasConfig c = cfg();
        if (!c.timelineEnabled() || now - startedAt < CATCH_UP_DELAY_MS) {
            return false;
        }
        ZonedDateTime t = Instant.ofEpochMilli(now).atZone(zone);
        String today = t.toLocalDate().toString();
        return t.getHour() >= c.timelineHour() && !today.equals(lastDaily);
    }

    private void runDaily() {
        String today = today();
        synchronized (this) {
            lastDaily = today;
        }
        saveState();
        List<String> worlds = dailyWorlds();
        logger.at(Level.INFO).log("Atlas takes the daily timeline snapshot of %s", String.join(", ", worlds));
        for (String w : worlds) {
            enqueue(new Request(w, null, true));
        }
    }

    /** Loaded worlds that are plain worlds, enabled and have tiles. Portal and instance worlds come and go. */
    private List<String> dailyWorlds() {
        TreeSet<String> names = new TreeSet<>();
        Universe u = Universe.get();
        if (u != null) {
            names.addAll(u.getWorlds().keySet());
        }
        names.addAll(atlas.live().worlds().keySet());
        List<String> out = new ArrayList<>();
        for (String n : names) {
            LiveState.WorldInfo info = atlas.live().worlds().get(n);
            if (info != null && info.kind() != null && info.kind().transientWorld()) {
                continue;
            }
            if (!cfg().worldEnabled(n) || !java.nio.file.Files.isDirectory(atlas.tiles().diskDir(n))) {
                continue;
            }
            out.add(n);
        }
        return out;
    }

    /** When the next daily snapshot runs, in epoch ms, or -1 while the timeline is off. */
    public synchronized long nextRunAt() {
        AtlasConfig c = cfg();
        if (!c.timelineEnabled()) {
            return -1;
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime at = now.toLocalDate().atTime(c.timelineHour(), 0).atZone(zone);
        String today = now.toLocalDate().toString();
        if (today.equals(lastDaily)) {
            at = at.plusDays(1);
        } else if (!now.isBefore(at)) {
            long soon = Math.max(System.currentTimeMillis(), startedAt + CATCH_UP_DELAY_MS);
            return soon;
        }
        return at.toInstant().toEpochMilli();
    }

    private void loadState() {
        try {
            JsonObject o = TimelineStore.readObject(stateFile);
            synchronized (this) {
                lastDaily = o == null ? null : Json.str(o, "lastDaily");
            }
        } catch (IOException | RuntimeException ignored) {
            // A missing state runs the daily snapshot once more, which only dedupes.
        }
    }

    private void saveState() {
        JsonObject o = new JsonObject();
        synchronized (this) {
            o.addProperty("lastDaily", lastDaily);
        }
        try {
            Json.writeAtomic(stateFile, Json.GSON.toJson(o));
        } catch (IOException e) {
            logger.at(Level.FINE).withCause(e).log("Atlas could not save the timeline state");
        }
    }

    // ---- snapshots ---------------------------------------------------------

    /**
     * Queues an on demand snapshot. A null world means every world the daily snapshot covers.
     * Returns a sentence for the admin. The reply gets a sentence per world once it is done.
     */
    public String requestSnapshot(String world, Consumer<String> reply) {
        if (!cfg().timelineEnabled()) {
            return "The timeline is turned off in atlas.json.";
        }
        if (scheduler == null) {
            return "The timeline is not running yet.";
        }
        List<String> worlds;
        if (world == null) {
            worlds = dailyWorlds();
            if (worlds.isEmpty()) {
                return "No world has map tiles to snapshot yet.";
            }
        } else {
            if (!cfg().worldEnabled(world)) {
                return "The world " + world + " is disabled in atlas.json.";
            }
            if (!java.nio.file.Files.isDirectory(atlas.tiles().diskDir(world))) {
                return "The world " + world + " has no map tiles to snapshot.";
            }
            worlds = List.of(world);
        }
        for (String w : worlds) {
            enqueue(new Request(w, reply, false));
        }
        return "Taking a timeline snapshot of " + String.join(", ", worlds) + ". It runs in the background.";
    }

    private void enqueue(Request r) {
        boolean startNow;
        synchronized (this) {
            for (Request p : pending) {
                if (p.world().equals(r.world()) && p.reply() == null && r.reply() == null) {
                    return;
                }
            }
            pending.addLast(r);
            startNow = job == null && !pruning;
        }
        if (startNow) {
            schedule(this::nextJob, 0);
        }
    }

    private void schedule(Runnable r, long delayMs) {
        ScheduledExecutorService ex = scheduler;
        if (ex == null) {
            return;
        }
        try {
            ex.schedule(guard("timeline snapshot", r), delayMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Stopping.
        }
    }

    private void nextJob() {
        Request r;
        SnapshotJob j;
        synchronized (this) {
            if (job != null || pruning) {
                // A prune that is running starts the queue again when it is done.
                return;
            }
            r = pending.pollFirst();
            if (r == null) {
                return;
            }
            String date = today();
            String prevDate = store.previousDate(r.world(), date);
            Manifest previous = prevDate == null ? null : store.manifest(r.world(), prevDate);
            Manifest sameDay = store.manifest(r.world(), date);
            j = new SnapshotJob(r.world(), date, System.currentTimeMillis(), atlas.tiles().diskDir(r.world()),
                    store.blobs(), prevDate, previous, sameDay, TileService.MIN_ZOOM, TileService.MAX_ZOOM);
            job = j;
            jobRequest = r;
        }
        try {
            j.list();
        } catch (IOException e) {
            fail(j, r, e);
            return;
        }
        schedule(() -> step(j, r), 0);
    }

    private void step(SnapshotJob j, Request r) {
        synchronized (this) {
            if (job != j) {
                return;
            }
        }
        try {
            j.step(BATCH);
            if (!j.finished()) {
                schedule(() -> step(j, r), STEP_DELAY_MS);
                return;
            }
            synchronized (this) {
                if (job != j) {
                    // Stopped while the last batch was read. Nothing is written.
                    return;
                }
            }
            finish(j, r);
        } catch (IOException | RuntimeException e) {
            fail(j, r, e);
        }
    }

    private void fail(SnapshotJob j, Request r, Exception e) {
        logger.at(Level.WARNING).withCause(e).log("Atlas could not take the timeline snapshot of %s", j.world);
        reply(r, "The timeline snapshot of " + j.world + " failed because " + e.getMessage() + ".");
        done(j);
    }

    private void finish(SnapshotJob j, Request r) throws IOException {
        long takenAt = System.currentTimeMillis();
        Manifest m = j.result(takenAt);
        JsonObject stats = stats(j.world, j.date, takenAt, m);
        store.write(m, stats);
        String text = r.daily() ? "The daily snapshot of the map was taken." : "An admin took a snapshot of the map.";
        EventLog.record(j.world, takenAt, "snapshot", null, null, null, text);
        long seconds = Math.max(0, (takenAt - j.startedAt) / 1000);
        logger.at(Level.INFO).log("Atlas took the timeline snapshot of %s for %s with %d tiles, %d changed, %d new images, %d skipped in %d s",
                j.world, j.date, m.tiles().size(), m.changed().size(), j.newBlobs(), j.skipped(), seconds);
        reply(r, "Snapshot of " + j.world + " for " + j.date + " done with " + m.tiles().size() + " tiles, "
                + m.changed().size() + " changed, " + j.newBlobs() + " new images" + (m.partial()
                ? ", " + j.skipped() + " tiles were being redrawn and keep their earlier image." : "."));
        JsonObject o = new JsonObject();
        o.addProperty("world", j.world);
        o.addProperty("date", j.date);
        atlas.sse().broadcastAll("timeline", Json.GSON.toJson(o), c -> c.viewer().canSee(cfg(), "timeline"));
        done(j);
    }

    private void done(SnapshotJob j) {
        boolean more;
        synchronized (this) {
            if (job == j) {
                job = null;
                jobRequest = null;
            }
            more = !pending.isEmpty();
        }
        if (more) {
            schedule(this::nextJob, STEP_DELAY_MS);
        } else {
            schedule(this::prune, 1000);
        }
    }

    private static void reply(Request r, String text) {
        if (r != null && r.reply() != null) {
            try {
                r.reply().accept(text);
            } catch (RuntimeException ignored) {
                // The admin left.
            }
        }
    }

    /** Prunes by retention and disk cap. Runs at start and after every batch of snapshots. */
    public void prune() {
        AtlasConfig c = cfg();
        synchronized (this) {
            if (job != null || pruning) {
                return;
            }
            pruning = true;
        }
        try {
            long cap = c.timelineMaxDiskMb() <= 0 ? 0 : c.timelineMaxDiskMb() * 1024L * 1024L;
            TimelineStore.PruneResult r = store.prune(LocalDate.now(zone), c.timelineRetainDays(), cap);
            if (r.days() > 0 || r.blobs() > 0) {
                logger.at(Level.INFO).log("Atlas pruned %d timeline days and %d unused images, %d KB freed",
                        r.days(), r.blobs(), r.bytes() / 1024);
            }
        } catch (IOException | RuntimeException e) {
            logger.at(Level.WARNING).withCause(e).log("Atlas could not prune the timeline");
        } finally {
            boolean more;
            synchronized (this) {
                pruning = false;
                more = job == null && !pending.isEmpty();
            }
            if (more) {
                schedule(this::nextJob, 0);
            }
        }
    }

    // ---- stats -------------------------------------------------------------

    JsonObject stats(String world, String date, long takenAt, Manifest m) {
        JsonObject o = new JsonObject();
        o.addProperty("world", world);
        o.addProperty("date", date);
        o.addProperty("takenAt", takenAt);
        o.addProperty("windowSeconds", ActivityTracker.WINDOW_MS / 1000);
        Integer explored = atlas.tiles().exploredCounts().get(world);
        o.addProperty("exploredChunks", explored == null ? 0 : explored);
        o.addProperty("tiles", m.tiles().size());
        o.addProperty("changed", m.changed().size());
        ActivityTracker.Window w = activity.window(world, takenAt);
        int now = 0;
        for (LiveState.PlayerSample p : atlas.live().players()) {
            if (world.equals(p.world())) {
                now++;
            }
        }
        o.addProperty("playersSeen", Math.max(w.playersSeen(), now));
        o.addProperty("peakOnline", Math.max(w.peakOnline(), now));
        o.addProperty("onlineNow", now);
        o.addProperty("kills", w.kills());
        o.addProperty("deaths", w.deaths());
        long broken = 0;
        long placed = 0;
        for (JsonElement e : atlas.heat().query(world, takenAt - ActivityTracker.WINDOW_MS)) {
            JsonArray row = e.getAsJsonArray();
            broken += row.get(2).getAsLong();
            placed += row.get(3).getAsLong();
        }
        o.addProperty("blocksBroken", broken);
        o.addProperty("blocksPlaced", placed);
        o.addProperty("worldEvents", w.worldEvents());
        o.addProperty("events", events.count(world, date));
        o.add("feats", featsSummary());
        return o;
    }

    /** Numbers and the top ten from the Feats export, names as they are, or null without an export. */
    private JsonElement featsSummary() {
        JsonObject root = atlas.leaderboard().root();
        if (root == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        JsonObject lb = Payloads.leaderboard(root, atlas.leaderboard().updatedAt(), id -> false);
        JsonObject o = new JsonObject();
        long updated = (long) Json.num(lb, "updatedAt", 0);
        o.addProperty("updatedAt", updated > 0 ? updated : null);
        o.addProperty("totalFeats", (int) Json.num(lb, "totalFeats", 0));
        o.addProperty("totalPoints", (int) Json.num(lb, "totalPoints", 0));
        JsonArray players = Json.arr(lb, "players");
        o.addProperty("players", players.size());
        JsonObject rarity = Json.obj(lb, "rarity");
        o.addProperty("rarityCount", rarity == null ? 0 : rarity.size());
        o.addProperty("milestones", Json.arr(lb, "milestones").size());
        o.addProperty("firsts", Json.arr(lb, "firsts").size());
        JsonArray top = new JsonArray();
        for (JsonElement e : players) {
            if (top.size() >= FEATS_TOP) {
                break;
            }
            JsonObject r = e.getAsJsonObject();
            JsonObject t = new JsonObject();
            t.addProperty("rank", (int) Json.num(r, "rank", top.size() + 1));
            t.addProperty("uuid", Json.str(r, "uuid"));
            t.addProperty("name", Json.str(r, "name"));
            t.addProperty("points", (int) Json.num(r, "points", 0));
            t.addProperty("unlocked", (int) Json.num(r, "unlocked", 0));
            top.add(t);
        }
        o.add("top", top);
        return o;
    }

    // ---- status ------------------------------------------------------------

    /** Lines for /atlas timeline status. */
    public List<String> status() {
        AtlasConfig c = cfg();
        List<String> out = new ArrayList<>();
        if (!c.timelineEnabled()) {
            out.add("The timeline is turned off in atlas.json.");
        }
        Map<String, Integer> held = store.daysHeld();
        if (held.isEmpty()) {
            out.add("No snapshots are held yet.");
        } else {
            List<String> parts = new ArrayList<>();
            held.forEach((w, n) -> parts.add(w + " " + n + (n == 1 ? " day" : " days")));
            out.add("Snapshots held, " + String.join(", ", parts) + ".");
        }
        long bytes = store.diskBytes();
        String cap = c.timelineMaxDiskMb() <= 0 ? "no cap" : "a cap of " + c.timelineMaxDiskMb() + " MB";
        out.add(String.format(Locale.ROOT, "Disk used %.1f MB with %s, days kept %d.", bytes / (1024.0 * 1024.0), cap,
                c.timelineRetainDays()));
        long next = nextRunAt();
        if (next > 0) {
            LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(next), zone);
            out.add(String.format(Locale.ROOT, "Next daily snapshot at %s %02d:%02d server time.", t.toLocalDate(),
                    t.getHour(), t.getMinute()));
        }
        synchronized (this) {
            if (job != null) {
                out.add("Snapshot of " + job.world + " running, " + job.done() + " of " + job.total() + " tiles read"
                        + (pending.isEmpty() ? "." : ", " + pending.size() + " more queued."));
            }
        }
        if (!c.timelineEvents()) {
            out.add("The event log is turned off.");
        } else if (events.queueDrops() > 0) {
            out.add("The event log dropped " + events.queueDrops() + " events because its queue was full.");
        }
        return out;
    }

    /** True while a snapshot runs or waits. */
    public synchronized boolean busy() {
        return job != null || !pending.isEmpty();
    }
}
