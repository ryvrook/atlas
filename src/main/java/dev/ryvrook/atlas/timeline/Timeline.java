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
 * The timeline. Takes timeline.perDay snapshots per world and day at evenly spaced hours from
 * timeline.hour server local time, one per slot (see Slots), or when an admin asks, keeps the event
 * log, collects the numbers for each slot's stats and prunes old days. A slot missed while the server
 * was down runs CATCH_UP_DELAY_MS after the start, only the latest one missed. An admin snapshot
 * goes into the slot of the moment and replaces what that slot held.
 *
 * <p>Everything runs on the Atlas scheduler. A snapshot reads and hashes BATCH tiles per step with
 * STEP_DELAY_MS between steps, so the scheduler stays free for the live map. Nothing here touches a
 * world thread.
 */
public final class Timeline {
    static final int BATCH = 128;
    static final long STEP_DELAY_MS = 25;
    static final long FLUSH_SECONDS = 5;
    /** A slot missed while the server was down runs this long after start. */
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

    private record Request(String world, Consumer<String> reply, boolean daily, String slot) {
    }

    /** Guarded by this. */
    private final ArrayDeque<Request> pending = new ArrayDeque<>();
    private SnapshotJob job;
    private Request jobRequest;
    /** True while a prune runs. No snapshot starts then, so a prune never collects a blob a new day is about to use. */
    private boolean pruning;
    /** The last slot the schedule ran, or a plain date as Atlas 1.0 and 1.1 saved it. */
    private String lastSlot;
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

    /** The slot of this moment, the one a snapshot taken now belongs to. */
    public String currentSlot() {
        return Slots.current(ZonedDateTime.now(zone), cfg().timelineHour(), cfg().timelinePerDay());
    }

    /** The hours of the slots of a day. */
    public int[] slotHours() {
        return Slots.hours(cfg().timelineHour(), cfg().timelinePerDay());
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

    /** Every minute. Samples who is online and starts the snapshot of a slot when it is due. */
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
        return !Slots.current(t, c.timelineHour(), c.timelinePerDay()).equals(lastSlot);
    }

    private void runDaily() {
        String slot = currentSlot();
        synchronized (this) {
            lastSlot = slot;
        }
        saveState();
        List<String> worlds = dailyWorlds();
        logger.at(Level.INFO).log("Atlas takes the timeline snapshot of %s for the slot %s", String.join(", ", worlds), slot);
        for (String w : worlds) {
            enqueue(new Request(w, null, true, slot));
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

    /** When the next scheduled snapshot runs, in epoch ms, or -1 while the timeline is off. */
    public synchronized long nextRunAt() {
        AtlasConfig c = cfg();
        if (!c.timelineEnabled()) {
            return -1;
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        if (!Slots.current(now, c.timelineHour(), c.timelinePerDay()).equals(lastSlot)) {
            return Math.max(System.currentTimeMillis(), startedAt + CATCH_UP_DELAY_MS);
        }
        return Slots.next(now, c.timelineHour(), c.timelinePerDay()).toInstant().toEpochMilli();
    }

    private void loadState() {
        try {
            JsonObject o = TimelineStore.readObject(stateFile);
            synchronized (this) {
                String slot = o == null ? null : Json.str(o, "lastSlot");
                // Atlas 1.0 and 1.1 kept the date of the last daily snapshot.
                lastSlot = slot != null ? slot : o == null ? null : Json.str(o, "lastDaily");
            }
        } catch (IOException | RuntimeException ignored) {
            // A missing state runs the slot of the moment once more, which only dedupes.
        }
    }

    private void saveState() {
        JsonObject o = new JsonObject();
        synchronized (this) {
            o.addProperty("lastSlot", lastSlot);
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
        String slot = currentSlot();
        for (String w : worlds) {
            enqueue(new Request(w, reply, false, slot));
        }
        return "Taking a timeline snapshot of " + String.join(", ", worlds) + " for the slot " + slotText(slot)
                + ". It runs in the background.";
    }

    private void enqueue(Request r) {
        boolean startNow;
        synchronized (this) {
            for (Request p : pending) {
                if (p.world().equals(r.world()) && p.slot().equals(r.slot()) && p.reply() == null && r.reply() == null) {
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
            String slot = r.slot();
            String prevSlot = store.previousSlot(r.world(), slot);
            // The complete tiles of the slot before, so a tile missing now is carried over even when that
            // slot was written before Atlas carried tiles over. Worked out in list, outside this lock.
            String world = r.world();
            java.util.function.Supplier<Manifest> previous = () -> prevSlot == null ? null : store.effective(world, prevSlot);
            Manifest sameSlot = store.manifest(r.world(), slot);
            dev.ryvrook.atlas.tiles.ExploredIndex index = atlas.tiles().explored(r.world());
            // An index that holds nothing yet cannot tell empty ground from unknown, so nothing is drawn.
            SnapshotJob.Explored explored = index.size() > 0 ? index::hasTile : null;
            j = new SnapshotJob(r.world(), slot, System.currentTimeMillis(), atlas.tiles().diskDir(r.world()),
                    store.blobs(), prevSlot, previous, sameSlot, TileService.MIN_ZOOM, TileService.MAX_ZOOM, explored);
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
        dev.ryvrook.atlas.iso.IsoSnapshots.dayTaken(j.world, j.date, store.dayDir(j.world, j.date));
        String text = r.daily() ? "The scheduled snapshot of the map was taken." : "An admin took a snapshot of the map.";
        EventLog.record(j.world, takenAt, "snapshot", null, null, null, text);
        long seconds = Math.max(0, (takenAt - j.startedAt) / 1000);
        logger.at(Level.INFO).log("Atlas took the timeline snapshot of %s for %s with %d tiles, %d changed, %d new images, %d skipped, %d carried over and %d drawn in %d s",
                j.world, j.date, m.tiles().size(), m.changed().size(), j.newBlobs(), j.skipped(), j.carried(), j.composed(), seconds);
        reply(r, "Snapshot of " + j.world + " for " + slotText(j.date) + " done with " + m.tiles().size() + " tiles, "
                + m.changed().size() + " changed, " + j.newBlobs() + " new images" + (m.partial()
                ? ", " + j.skipped() + " tiles were being redrawn and keep their earlier image." : "."));
        JsonObject o = new JsonObject();
        o.addProperty("world", j.world);
        o.addProperty("date", Slots.date(j.date));
        o.addProperty("slot", j.date);
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

    /** A slot for people, such as 2026-10-13 16:00. */
    static String slotText(String slot) {
        int h = Slots.hour(slot);
        return h < 0 ? slot : Slots.date(slot) + String.format(Locale.ROOT, " %02d:00", h);
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

    JsonObject stats(String world, String slot, long takenAt, Manifest m) {
        String date = Slots.date(slot);
        JsonObject o = new JsonObject();
        o.addProperty("world", world);
        o.addProperty("date", date);
        o.addProperty("slot", slot);
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
        int[] hours = slotHours();
        out.add(hours.length + (hours.length == 1 ? " snapshot" : " snapshots") + " a day at " + Slots.schedule(hours)
                + " server time, timeline.perDay " + c.timelinePerDay() + " from timeline.hour " + c.timelineHour() + ".");
        Map<String, Integer> held = store.daysHeld();
        Map<String, Integer> slots = store.slotsHeld();
        if (held.isEmpty()) {
            out.add("No snapshots are held yet.");
        } else {
            List<String> parts = new ArrayList<>();
            held.forEach((w, n) -> {
                int k = slots.getOrDefault(w, n);
                parts.add(w + " " + n + (n == 1 ? " day" : " days") + " in " + k + (k == 1 ? " slot" : " slots"));
            });
            out.add("Snapshots held, " + String.join(", ", parts) + ".");
        }
        long bytes = store.diskBytes();
        String cap = c.timelineMaxDiskMb() <= 0 ? "no cap" : "a cap of " + c.timelineMaxDiskMb() + " MB";
        out.add(String.format(Locale.ROOT, "Disk used %.1f MB with %s, days kept %d.", bytes / (1024.0 * 1024.0), cap,
                c.timelineRetainDays()));
        long next = nextRunAt();
        if (next > 0) {
            LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(next), zone);
            out.add(String.format(Locale.ROOT, "Next snapshot at %s %02d:%02d server time, last slot taken %s.", t.toLocalDate(),
                    t.getHour(), t.getMinute(), lastSlotText()));
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

    private synchronized String lastSlotText() {
        return lastSlot == null ? "none yet" : slotText(lastSlot);
    }

    /** True while a snapshot runs or waits. */
    public synchronized boolean busy() {
        return job != null || !pending.isEmpty();
    }
}
