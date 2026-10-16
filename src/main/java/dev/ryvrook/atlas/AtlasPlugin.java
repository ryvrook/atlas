package dev.ryvrook.atlas;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.ryvrook.atlas.command.AtlasCommand;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.data.DominionSnapshot;
import dev.ryvrook.atlas.data.EngineData;
import dev.ryvrook.atlas.data.LiveState;
import dev.ryvrook.atlas.data.Payloads;
import dev.ryvrook.atlas.http.ApiServer;
import dev.ryvrook.atlas.integration.TrackerLink;
import dev.ryvrook.atlas.integration.TrackerPresence;
import dev.ryvrook.atlas.iso.IsoChunkSaveSystem;
import dev.ryvrook.atlas.iso.IsoRoutes;
import dev.ryvrook.atlas.iso.IsoService;
import dev.ryvrook.atlas.iso.IsoSnapshots;
import dev.ryvrook.atlas.systems.BlockActivitySystem;
import dev.ryvrook.atlas.systems.ChunkSaveSystem;
import dev.ryvrook.atlas.systems.DeathSystem;
import dev.ryvrook.atlas.timeline.Timeline;
import dev.ryvrook.atlas.timeline.TimelineRoutes;
import dev.ryvrook.atlas.util.Json;
import org.joml.Vector3d;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Atlas, a live web map for Hytale servers. Serves terrain tiles built from the world map,
 * live players, and faction data exported by Dominion, from an embedded HTTP server.
 *
 * <p>Every periodic task runs on the plugin's own Atlas-scheduler threads, never on the engine's
 * shared scheduled executor. Work that needs a world thread hops there with world.execute.
 */
public final class AtlasPlugin extends JavaPlugin {
    private static final long FULL_PLAYERS_MS = 10_000;
    private static final long GUARD_REPEAT_MS = 5 * 60 * 1000L;
    private static final long SNAPSHOT_WARN_MS = 60_000;
    private static final long TRANSIENT_CLEANUP_AFTER_MS = 2 * 60 * 1000L;

    private Atlas atlas;
    private ApiServer server;
    private volatile Timeline timeline;
    private IsoService iso;
    private IsoSnapshots isoSnapshots;
    private volatile ScheduledThreadPoolExecutor scheduler;
    private volatile boolean stopping;
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();
    private ScheduledFuture<?> playerTask;
    /** Per task failure state for guard, as [time of the last log line]. Absent while the task succeeds. */
    private final Map<String, long[]> failures = new ConcurrentHashMap<>();
    /** Held while the snapshot is polled, which the scheduler and a reload may both do. */
    private final Object snapshotLock = new Object();
    private long lastSnapshotWarn;
    private long warnedErrorAt;
    /** Why atlas.json was rejected, or null while it is valid. The web server stays down while set. */
    private volatile String configError;
    /** Disk writes that failed, as name to {time, message}. Cleared by the next success. */
    private final Map<String, String[]> diskFailures = new ConcurrentHashMap<>();

    // Player stream state, touched only by the players task.
    private final Map<String, String> lastSignature = new HashMap<>();
    private Map<String, Set<String>> lastVisible = new HashMap<>();
    private long lastFull;

    // World watch state, touched only by the world watch task.
    private Set<String> knownWorlds;
    private final Set<String> transientWorlds = ConcurrentHashMap.newKeySet();

    // TrackerAPI, decided once in setup.
    private TrackerPresence.Source trackerSource = TrackerPresence.Source.OWN;
    private TrackerPresence.Watch trackerWatch;
    private volatile TrackerLink trackerLink;
    // Coinage, bound in start when the plugin manager lists it.
    private volatile dev.ryvrook.atlas.integration.CoinageLink coinageLink;
    private final dev.ryvrook.atlas.integration.DominionWatch dominionWatch = new dev.ryvrook.atlas.integration.DominionWatch();

    public AtlasPlugin(JavaPluginInit init) {
        super(init);
    }

    public Atlas atlas() {
        return atlas;
    }

    public ApiServer server() {
        return server;
    }

    /** The isometric views, or null before setup. */
    public IsoService iso() {
        return iso;
    }

    /** The daily isometric views and key places, or null before setup. */
    public IsoSnapshots isoSnapshots() {
        return isoSnapshots;
    }

    /** The timeline, or null before setup. */
    public Timeline timeline() {
        return timeline;
    }

    @Override
    protected void setup() {
        AtlasConfig cfg;
        try {
            cfg = AtlasConfig.load(getDataDirectory());
            configError = null;
        } catch (Exception e) {
            // Fail closed. The rest of the plugin runs on built in defaults, but nothing is served.
            configError = String.valueOf(e.getMessage());
            logConfigError();
            cfg = new AtlasConfig();
        }
        warnConfig(cfg);
        atlas = new Atlas(cfg, getDataDirectory());
        // The tile service logs and reads the live settings from here on, before anything renders.
        atlas.tiles().attach(getLogger(), atlas::config);
        atlas.heat().load();
        atlas.links().load();
        atlas.kills().load();
        atlas.lastSeen().load();
        loadTransientWorlds();
        server = new ApiServer(atlas, getLogger());
        server.setHealthExtras(this::healthExtras);
        server.setOtherMapMods(this::otherMapMods);
        timeline = new Timeline(atlas, getDataDirectory(), getLogger());
        TimelineRoutes timelineRoutes = new TimelineRoutes(atlas, this::timeline);
        server.setTimeline(timelineRoutes);
        iso = new IsoService(atlas, getDataDirectory(), getLogger(), IsoService.budgetOf(atlas.tiles()));
        isoSnapshots = new IsoSnapshots(atlas, iso, getLogger(), this::timeline);
        IsoRoutes.install(new IsoRoutes(atlas, iso, this::timeline, timelineRoutes));
        IsoSnapshots.install(isoSnapshots);

        getCommandRegistry().registerCommand(new AtlasCommand(this));
        // Block activity and deaths come either from Atlas's own systems or from TrackerAPI, never both.
        dev.ryvrook.atlas.integration.PluginPresence.Found tracker = TrackerPresence.find();
        trackerSource = TrackerPresence.decide(cfg.trackerMode(), tracker);
        trackerWatch = new TrackerPresence.Watch(cfg.trackerMode(), trackerSource, tracker);
        String trackerLine = TrackerPresence.describe(cfg.trackerMode(), trackerSource, tracker);
        if (trackerSource == TrackerPresence.Source.NONE) {
            getLogger().at(Level.WARNING).log("%s", trackerLine);
        } else {
            getLogger().at(Level.INFO).log("%s", trackerLine);
        }
        if (trackerSource == TrackerPresence.Source.OWN) {
            getEntityStoreRegistry().registerSystem(BlockActivitySystem.breaks(atlas.heat()));
            getEntityStoreRegistry().registerSystem(BlockActivitySystem.places(atlas.heat()));
            getEntityStoreRegistry().registerSystem(new DeathSystem(this::recordKill, atlas::placeWithheld));
        }
        getChunkStoreRegistry().registerSystem(new ChunkSaveSystem(atlas.tiles()));
        getChunkStoreRegistry().registerSystem(new IsoChunkSaveSystem(iso));
        registerAssetReload();
        getEventRegistry().registerAsyncGlobal(PlayerChatEvent.class, future -> future.thenApply(this::onChat));
        getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onDisconnect);

        atlas.tiles().onInvalidate((world, changed) -> {
            JsonObject o = new JsonObject();
            o.addProperty("world", world);
            o.addProperty("at", System.currentTimeMillis());
            JsonArray list = new JsonArray();
            for (int[] t : changed) {
                JsonArray row = new JsonArray();
                row.add(t[0]);
                row.add(t[1]);
                row.add(t[2]);
                list.add(row);
            }
            o.add("tiles", list);
            String json = Json.GSON.toJson(o);
            atlas.sse().broadcastAll("tiles", json, c -> c.viewer().canSee(atlas.config(), "terrain")
                    && (c.world() == null || c.world().equals(world)));
        });
        warnRejectedProxies();
        if (configError == null) {
            getLogger().at(Level.INFO).log("Atlas set up, web server will listen on %s:%d", cfg.bind, cfg.port);
        }
    }

    @Override
    protected void start() {
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "Atlas-scheduler");
            t.setDaemon(true);
            return t;
        });
        ex.setRemoveOnCancelPolicy(true);
        ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler = ex;
        atlas.useExecutor(r -> runAsync(r));
        // Session listeners of other plugins run on this scheduler, never on the thread that made the change.
        atlas.sessionApi().start(ex);

        Path snap = atlas.snapshot().path();
        if (atlas.snapshot().off()) {
            getLogger().at(Level.INFO).log("Atlas leaves the Dominion snapshot alone because dominion is never");
        } else if (!Files.isRegularFile(snap)) {
            getLogger().at(Level.INFO).log("Atlas found no Dominion snapshot at %s and runs standalone until the file appears",
                    snap.toAbsolutePath());
        }
        bindTracker();
        bindCoinage();
        pollSnapshot();
        pollMapMod();
        atlas.seedFeed();
        atlas.leaderboard().poll();
        atlas.chronicle().poll();
        timeline.start(ex);
        iso.start(ex);
        tasks.add(ex.scheduleWithFixedDelay(guard("iso sweep",
                () -> isoSnapshots.sweep(timeline.store().root())), 120, 600, TimeUnit.SECONDS));
        if (configError == null) {
            server.startWithRetry(ex);
            warnPublicUrl();
        }
        tasks.add(ex.scheduleWithFixedDelay(guard("snapshot", this::pollSnapshot), 1, 1, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("world info",
                () -> atlas.live().refreshOnWorldThreads(atlas.config().showPlayerHealth || atlas.sse().size() > 0)),
                1, 2, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("world watch", this::watchWorlds), 5, 5, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("world events", () -> atlas.engine().pollEvents()), 7, 5, TimeUnit.SECONDS));
        java.util.concurrent.Executor markerWork = r -> ex.execute(guard("markers", r));
        tasks.add(ex.scheduleWithFixedDelay(guard("markers", () -> atlas.pollMarkers(markerWork)), 6, 5, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("leaderboard", this::pollLeaderboard), 5, 5, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("chronicle", () -> pollChronicle(false)), 5, 5, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("map settings", this::pollMapMod), 15, 15, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("tracker", this::watchTracker), 5, 5, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("dominion", this::watchDominion), 5, 5, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("explored", () -> atlas.tiles().refreshExplored()), 5, 600, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("tiles", () -> {
            atlas.tiles().refreshLoaded();
            atlas.tiles().refreshDirty(false);
        }), 30, 30, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("save", this::saveQuietly), 60, 300, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("prune", () -> {
            atlas.heat().prune();
            atlas.lastSeen().prune();
            atlas.personal().prune();
            atlas.links().prune();
            atlas.pruneAdminCache();
            server.pruneRateLimits();
        }), 10, 600, TimeUnit.SECONDS));
        schedulePlayers();
        reloadDependentsAfterReload(ex);
    }

    /**
     * Reloads the plugins that list Atlas when they were already enabled at start, which only happens after a
     * /plugin reload of Atlas. Two seconds later, so Atlas finishes starting first, and on a thread of its own
     * since the pass may wait for another reloader and for each plugin.
     */
    private void reloadDependentsAfterReload(ScheduledThreadPoolExecutor ex) {
        List<Dependents.Dependent> dependents = Dependents.enabled(getIdentifier(), getLogger());
        if (dependents.isEmpty()) {
            return;
        }
        getLogger().at(Level.INFO).log("Atlas found %d plugins linked to its previous instance and reloads them in two seconds",
                dependents.size());
        tasks.add(ex.schedule(() -> {
            if (stopping) {
                return;
            }
            Thread pass = new Thread(() -> {
                try {
                    Dependents.reload(dependents, getLogger());
                } catch (RuntimeException | LinkageError e) {
                    getLogger().at(Level.WARNING).withCause(e).log("Atlas could not reload the plugins linked to it");
                }
            }, "Atlas-dependents");
            pass.setDaemon(true);
            pass.start();
        }, 2, TimeUnit.SECONDS));
    }

    @Override
    protected void shutdown() {
        stopping = true;
        if (atlas != null) {
            // Withdrawn first, so no session listener is handed a change while the scheduler goes down.
            atlas.sessionApi().stop();
        }
        TrackerLink link = trackerLink;
        trackerLink = null;
        if (link != null) {
            try {
                link.close();
            } catch (RuntimeException | LinkageError e) {
                // TrackerAPI went first and closed the subscriptions itself.
            }
        }
        dev.ryvrook.atlas.integration.CoinageLink money = coinageLink;
        coinageLink = null;
        if (money != null) {
            if (atlas != null) {
                atlas.useCoinage(null);
            }
            try {
                money.close();
            } catch (RuntimeException | LinkageError e) {
                // Coinage was unloaded first. Its hooks went with it.
            }
        }
        for (ScheduledFuture<?> f : tasks) {
            f.cancel(false);
        }
        tasks.clear();
        if (playerTask != null) {
            playerTask.cancel(false);
            playerTask = null;
        }
        ScheduledThreadPoolExecutor ex = scheduler;
        scheduler = null;
        if (ex != null) {
            ex.shutdownNow();
        }
        if (timeline != null) {
            timeline.stop();
        }
        IsoRoutes.install(null);
        IsoSnapshots.install(null);
        if (iso != null) {
            iso.stop();
        }
        if (server != null) {
            server.stop();
        }
        if (atlas != null) {
            atlas.shutdown();
            atlas.tiles().shutdown();
            saveQuietly();
        }
    }

    /** One death for the kill log, the feed and the timeline, from DeathSystem or from TrackerAPI. */
    private void recordKill(dev.ryvrook.atlas.data.KillLog.Kill k) {
        atlas.addKill(k);
        Timeline t = timeline;
        if (t != null) {
            t.death(k);
        }
    }

    /**
     * Hooks the TrackerAPI bridge to TrackerAPI's enable and disable when setup chose TrackerAPI. The bridge
     * class is the only one that names TrackerAPI types and is loaded here for the first time.
     */
    private void bindTracker() {
        if (trackerSource != TrackerPresence.Source.TRACKER) {
            return;
        }
        try {
            trackerLink = dev.ryvrook.atlas.integration.TrackerBridge.install(atlas.heat(), this::recordKill,
                    atlas::placeWithheld, msg -> getLogger().at(Level.WARNING).log("%s", msg));
        } catch (RuntimeException | LinkageError e) {
            getLogger().at(Level.WARNING).withCause(e).log(
                    "Atlas could not bind to TrackerAPI and records no block activity and no kills until it is reloaded");
        }
    }

    /**
     * Hooks the Coinage bridge when the plugin manager lists Coinage 1.0.0 or newer. The bridge class is the
     * only one that names Coinage types and is loaded here for the first time. Atlas only reads balances, for
     * the account menu of a linked player while showOwnMoney is on. Coinage reloads Atlas itself after its
     * own reload, so no watch is needed here.
     */
    private void bindCoinage() {
        dev.ryvrook.atlas.config.TrackerMode mode = atlas.config().coinageMode();
        if (mode == dev.ryvrook.atlas.config.TrackerMode.NEVER) {
            return;
        }
        dev.ryvrook.atlas.integration.PluginPresence.Found found = dev.ryvrook.atlas.integration.PluginPresence.find(
                dev.ryvrook.atlas.integration.CoinageLink.GROUP, dev.ryvrook.atlas.integration.CoinageLink.NAME);
        if (found == null) {
            if (mode == dev.ryvrook.atlas.config.TrackerMode.ALWAYS) {
                getLogger().at(Level.WARNING).log("Atlas shows no balances because coinage is always and Coinage is not installed");
            }
            return;
        }
        if (dev.ryvrook.atlas.integration.VersionCompare.below(found.version(), dev.ryvrook.atlas.integration.CoinageLink.MINIMUM)) {
            getLogger().at(Level.INFO).log("Atlas leaves Coinage %s alone because it is older than %s",
                    found.version(), dev.ryvrook.atlas.integration.CoinageLink.MINIMUM);
            return;
        }
        try {
            dev.ryvrook.atlas.integration.CoinageLink link = dev.ryvrook.atlas.integration.CoinageBridge.install();
            coinageLink = link;
            atlas.useCoinage(link);
            getLogger().at(Level.INFO).log("Atlas found Coinage %s and shows a linked player their own balance %s",
                    found.version(), atlas.config().showOwnMoney ? "in the account menu" : "once showOwnMoney is true");
        } catch (RuntimeException | LinkageError e) {
            getLogger().at(Level.WARNING).withCause(e).log("Atlas could not bind to Coinage and shows no balances");
        }
    }

    /** Every 5 seconds. Asks for a reload of Atlas once when TrackerAPI was loaded, reloaded or unloaded. */
    private void watchTracker() {
        TrackerPresence.Watch w = trackerWatch;
        TrackerPresence.Watch.Notice n = w == null ? null : w.check(TrackerPresence.find());
        if (n != null) {
            getLogger().at(n.warn() ? Level.WARNING : Level.INFO).log("%s", n.text());
        }
    }

    /**
     * Every 5 seconds. Drops the Dominion snapshot from the map once Dominion has been missing for a minute,
     * so a file left by a removed Dominion does not keep old territories on the map, and reads it again when
     * Dominion is back. Only the default path is watched, and only while dominion is auto.
     */
    private void watchDominion() {
        boolean watched = atlas.config().dominionMode() == dev.ryvrook.atlas.config.TrackerMode.AUTO
                && Atlas.resolveSnapshot(atlas.config()).equals(
                java.nio.file.Path.of(AtlasConfig.DEFAULT_DOMINION_SNAPSHOT).toAbsolutePath().normalize());
        boolean missing = watched && dev.ryvrook.atlas.integration.PluginPresence.missing(
                dev.ryvrook.atlas.integration.DominionWatch.GROUP, dev.ryvrook.atlas.integration.DominionWatch.NAME);
        Boolean gone = dominionWatch.update(watched, missing, System.currentTimeMillis());
        if (gone != null) {
            // The snapshot task applies it within a second.
            atlas.snapshot().setOwnerGone(gone);
        }
    }

    /** Rebuilds the isometric colour table after the block types were loaded again. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerAssetReload() {
        getEventRegistry().register((Class) LoadedAssetsEvent.class, BlockType.class, e -> iso.assetsChanged());
    }

    /** Runs a task on the Atlas scheduler, or right here when the scheduler is not running. */
    public void runAsync(Runnable r) {
        ScheduledThreadPoolExecutor ex = scheduler;
        if (ex != null && !ex.isShutdown()) {
            try {
                ex.execute(guard("background", r));
                return;
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Shutting down. Run inline below.
            }
        }
        guard("background", r).run();
    }

    private synchronized void schedulePlayers() {
        if (playerTask != null) {
            playerTask.cancel(false);
        }
        ScheduledThreadPoolExecutor ex = scheduler;
        if (ex == null) {
            return;
        }
        long periodMs = Math.max(100, Math.round(1000.0 / atlas.config().clampedHz()));
        playerTask = ex.scheduleAtFixedRate(guard("players", this::pushPlayers), periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    // ---- players -------------------------------------------------------------

    /**
     * Samples players and sends one shared payload per viewer class. Payloads list only players
     * that moved since the previous tick, plus a full list every ten seconds. Partial payloads also
     * carry the players that left the viewer's view since the previous payload.
     */
    private synchronized void pushPlayers() {
        atlas.live().sample();
        Set<String> moved = movedPlayers();
        if (atlas.sse().size() == 0) {
            lastVisible = new HashMap<>();
            return;
        }
        long now = System.currentTimeMillis();
        boolean full = now - lastFull >= FULL_PLAYERS_MS;
        if (full) {
            lastFull = now;
        }
        Map<String, Set<String>> before = lastVisible;
        Map<String, Set<String>> next = new ConcurrentHashMap<>();
        // Ended streams kept for a resume get no players frames. The next full frame, at most ten
        // seconds after a resume, brings them up to date.
        atlas.sse().broadcast("players", c -> !c.ended(), c -> {
            JsonObject p = atlas.playersPayload(c.viewer(), c.world(), false);
            String key = c.shareKey();
            JsonArray rows = p.has("players") ? p.getAsJsonArray("players") : new JsonArray();
            Set<String> visible = new HashSet<>();
            for (JsonElement e : rows) {
                visible.add(Json.str(e.getAsJsonObject(), "uuid"));
            }
            next.put(key, visible);
            Set<String> prev = before.get(key);
            if (full || prev == null) {
                p.addProperty("full", true);
                return Json.GSON.toJson(p);
            }
            JsonArray changed = new JsonArray();
            for (JsonElement e : rows) {
                String uuid = Json.str(e.getAsJsonObject(), "uuid");
                if (moved.contains(uuid) || !prev.contains(uuid)) {
                    changed.add(e);
                }
            }
            JsonArray left = new JsonArray();
            for (String uuid : prev) {
                if (!visible.contains(uuid)) {
                    left.add(uuid);
                }
            }
            if (changed.isEmpty() && left.isEmpty()) {
                return null;
            }
            p.add("players", changed);
            p.addProperty("full", false);
            p.add("left", left);
            return Json.GSON.toJson(p);
        });
        lastVisible = next;
    }

    /** Uuid keys of players whose world, position or health changed since the previous call. */
    private Set<String> movedPlayers() {
        Set<String> moved = new HashSet<>();
        Map<String, String> seen = new HashMap<>();
        for (LiveState.PlayerSample p : atlas.live().players()) {
            String key = p.uuid().toString().toLowerCase(Locale.ROOT);
            seen.put(key, signature(p.world(), p.x(), p.y(), p.z()) + "|" + p.health());
        }
        DominionSnapshot.View view = atlas.snapshot().view();
        for (Map.Entry<String, JsonObject> e : view.playerByUuid.entrySet()) {
            if (!seen.containsKey(e.getKey())) {
                JsonObject s = e.getValue();
                seen.put(e.getKey(), signature(Json.str(s, "world"), Json.num(s, "x", 0), Json.num(s, "y", 0), Json.num(s, "z", 0))
                        + "|" + view.loadedAt);
            }
        }
        for (Map.Entry<String, String> e : seen.entrySet()) {
            if (!e.getValue().equals(lastSignature.get(e.getKey()))) {
                moved.add(e.getKey());
            }
        }
        lastSignature.clear();
        lastSignature.putAll(seen);
        return moved;
    }

    private static String signature(String world, double x, double y, double z) {
        return world + "|" + Math.round(x * 100) + "|" + Math.round(y * 100) + "|" + Math.round(z * 100);
    }

    // ---- snapshot, leaderboard and worlds --------------------------------------

    /**
     * Re-reads the BetterMap settings when the file changed. Says so once when the file is found and
     * stays quiet while it is absent.
     */
    private void pollMapMod() {
        if (atlas.mapMod().poll() && atlas.config().honorBetterMap) {
            getLogger().at(Level.INFO).log("Atlas found BetterMap settings at %s and applies its server wide map rules",
                    atlas.mapMod().configFile());
        }
    }

    private void pollSnapshot() {
        synchronized (snapshotLock) {
            pollSnapshotLocked();
        }
    }

    private void pollSnapshotLocked() {
        boolean wasPresent = atlas.snapshot().present();
        boolean changed = atlas.snapshot().poll(atlas::addFeed, this::addSnapshotChat);
        warnSnapshotError();
        if (!changed) {
            return;
        }
        boolean present = atlas.snapshot().present();
        if (present != wasPresent) {
            getLogger().at(Level.INFO).log(present ? "Atlas found the Dominion snapshot at %s"
                    : atlas.snapshot().ownerGone()
                    ? "Atlas has seen no running Dominion for a minute, so it stops drawing the snapshot left at %s and runs standalone"
                    : "Atlas lost the Dominion snapshot at %s and runs standalone", atlas.snapshot().path().toAbsolutePath());
            // Dominion's own siege layer is shown only while the snapshot does not draw the sieges.
            atlas.markers().refreshLayer(dev.ryvrook.atlas.markers.MarkerService.DOMINION_SIEGES);
        }
        List<String> sections = atlas.changedSections();
        if (sections.isEmpty()) {
            return;
        }
        JsonObject o = new JsonObject();
        JsonArray list = new JsonArray();
        sections.forEach(list::add);
        o.add("sections", list);
        o.addProperty("at", atlas.snapshot().view().at());
        o.addProperty("present", present);
        atlas.sse().broadcastAll("snapshot", Json.GSON.toJson(o), c -> true);
    }

    /** Logs a snapshot parse failure at most once a minute, with the reason. */
    private void warnSnapshotError() {
        long at = atlas.snapshot().lastErrorAt();
        String err = atlas.snapshot().lastError();
        if (at == 0 || err == null || at == warnedErrorAt) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSnapshotWarn < SNAPSHOT_WARN_MS) {
            return;
        }
        lastSnapshotWarn = now;
        warnedErrorAt = at;
        getLogger().at(Level.WARNING).log("Atlas could not read the Dominion snapshot at %s because %s",
                atlas.snapshot().path().toAbsolutePath(), err);
    }

    private void pollLeaderboard() {
        if (!atlas.leaderboard().poll()) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("updatedAt", atlas.leaderboard().updatedAt());
        atlas.sse().broadcastAll("leaderboard", Json.GSON.toJson(o), c -> c.viewer().canSee(atlas.config(), "leaderboard"));
    }

    /**
     * Checks the Chronicle export and tells browsers that may see stats to fetch api/chronicle again when it
     * appeared, changed or went away, or always when force is set, as after a reload that may have changed
     * chronicleUrl.
     */
    private void pollChronicle(boolean force) {
        if (!atlas.chronicle().poll() && !force) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("available", atlas.chronicle().available());
        Payloads.put(o, "updatedAt", atlas.chronicle().available() ? (Number) atlas.chronicle().updatedAt() : null);
        atlas.sse().broadcastAll("chronicle", Json.GSON.toJson(o), c -> c.viewer().canSee(atlas.config(), "stats"));
    }

    /**
     * Notices worlds the engine added or removed within five seconds. A removed portal or instance
     * world loses its explored index, its tiles in memory and on disk, and its world events. Every
     * change is announced with a worlds event so browsers fetch the world list again.
     */
    private void watchWorlds() {
        Universe u = Universe.get();
        if (u == null) {
            return;
        }
        Set<String> now = new HashSet<>(u.getWorlds().keySet());
        for (String name : now) {
            LiveState.WorldInfo info = atlas.live().worlds().get(name);
            if (info != null && info.kind() != null && info.kind().transientWorld() && transientWorlds.add(name)) {
                saveTransientWorlds();
            }
        }
        Set<String> prev = knownWorlds;
        knownWorlds = now;
        boolean changed = false;
        if (prev != null) {
            for (String gone : prev) {
                if (!now.contains(gone)) {
                    dropWorld(gone);
                    changed = true;
                }
            }
            for (String added : now) {
                if (!prev.contains(added)) {
                    changed = true;
                }
            }
        }
        if (System.currentTimeMillis() - atlas.startedAt() > TRANSIENT_CLEANUP_AFTER_MS) {
            // Portal worlds from an earlier run that did not come back still have tile folders.
            for (String name : new ArrayList<>(transientWorlds)) {
                if (!now.contains(name)) {
                    atlas.tiles().deleteDiskWorld(name);
                    transientWorlds.remove(name);
                    saveTransientWorlds();
                }
            }
        }
        if (changed) {
            JsonObject o = new JsonObject();
            o.addProperty("updatedAt", System.currentTimeMillis());
            atlas.sse().broadcastAll("worlds", Json.GSON.toJson(o), c -> true);
        }
    }

    private void dropWorld(String name) {
        boolean transientWorld = transientWorlds.contains(name);
        atlas.worldRemoved(name, transientWorld);
        atlas.live().worlds().remove(name);
        atlas.personal().dropWorld(name);
        long at = System.currentTimeMillis();
        for (EngineData.ActiveEvent e : atlas.engine().dropWorld(name)) {
            atlas.worldEventChanged(e, "end", at);
        }
        if (transientWorld) {
            atlas.tiles().dropWorld(name);
            iso.dropWorld(name);
            transientWorlds.remove(name);
            saveTransientWorlds();
            getLogger().at(Level.INFO).log("Atlas dropped the map data of the removed world %s", name);
        }
    }

    private Path transientFile() {
        return getDataDirectory().resolve("transient-worlds.json");
    }

    private void loadTransientWorlds() {
        try {
            Path f = transientFile();
            if (Files.isRegularFile(f)) {
                JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
                if (root.isJsonArray()) {
                    for (JsonElement e : root.getAsJsonArray()) {
                        if (e.isJsonPrimitive()) {
                            transientWorlds.add(e.getAsString());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // A damaged list only leaves old tile folders behind.
        }
    }

    private void saveTransientWorlds() {
        JsonArray a = new JsonArray();
        transientWorlds.forEach(a::add);
        try {
            Json.writeAtomic(transientFile(), Json.GSON.toJson(a));
        } catch (Exception e) {
            getLogger().at(Level.FINE).withCause(e).log("Atlas could not save the portal world list");
        }
    }

    // ---- events ----------------------------------------------------------------

    /** Feeds one public chat line from the Dominion snapshot into the map chat. */
    private void addSnapshotChat(JsonObject line) {
        String name = Json.str(line, "name");
        String text = Json.str(line, "text");
        if (name == null || text == null) {
            return;
        }
        String tag = Json.str(line, "tag");
        atlas.addChat(name, tag, text, (long) Json.num(line, "at", System.currentTimeMillis()));
    }

    private PlayerChatEvent onChat(PlayerChatEvent e) {
        try {
            if (snapshotOwnsChat()) {
                // Dominion cancels chat events and routes channels itself, so its snapshot is the only reliable source.
                return e;
            }
            if (!e.isCancelled() && e.getSender() != null && isPublic(e)) {
                atlas.addChat(e.getSender().getUuid(), e.getSender().getUsername(), e.getContent());
            }
        } catch (RuntimeException ex) {
            getLogger().at(Level.FINE).withCause(ex).log("Atlas chat capture failed");
        }
        return e;
    }

    /** Records where a player was when they left, for the last seen list. */
    private void onDisconnect(PlayerDisconnectEvent e) {
        try {
            PlayerRef ref = e.getPlayerRef();
            if (ref == null || ref.getUuid() == null) {
                return;
            }
            String world = null;
            double[] pos = null;
            try {
                Transform t = ref.getTransform();
                Vector3d p = t == null ? null : t.getPosition();
                Universe u = Universe.get();
                World w = u == null || ref.getWorldUuid() == null ? null : u.getWorld(ref.getWorldUuid());
                if (p != null && w != null) {
                    world = w.getName();
                    pos = new double[]{p.x, p.y, p.z};
                }
            } catch (RuntimeException ignored) {
                // Fall back to the last sample below.
            }
            if (pos == null) {
                LiveState.PlayerSample s = atlas.live().sampleOf(ref.getUuid());
                if (s == null) {
                    return;
                }
                world = s.world();
                pos = new double[]{s.x(), s.y(), s.z()};
            }
            atlas.recordLastSeen(ref.getUuid(), ref.getUsername(), world, pos[0], pos[1], pos[2]);
        } catch (RuntimeException ex) {
            getLogger().at(Level.FINE).withCause(ex).log("Atlas could not record where a player left");
        }
    }

    /**
     * True when the Dominion snapshot is loaded and carries a chat array. An older Dominion that
     * writes no chat array leaves the chat listener in charge.
     */
    private boolean snapshotOwnsChat() {
        return atlas.snapshot().present() && atlas.snapshot().view().hasChat;
    }

    /**
     * Faction, ally and other private channels narrow the targets of the chat event. A message that
     * reaches fewer players than are online is treated as private and never reaches the map.
     */
    private static boolean isPublic(PlayerChatEvent e) {
        List<?> targets = e.getTargets();
        if (targets == null) {
            return true;
        }
        int online;
        try {
            Universe u = Universe.get();
            online = u == null ? 0 : u.getPlayerCount();
        } catch (RuntimeException ex) {
            return false;
        }
        return targets.size() >= online;
    }

    /**
     * Wraps a periodic task. The first failure is logged with its stack trace. While the task keeps
     * failing the message is repeated at most every five minutes without the trace. A success in
     * between resets this, so the next failure is logged in full again.
     */
    private Runnable guard(String name, Runnable r) {
        return () -> {
            try {
                r.run();
                if (!failures.isEmpty()) {
                    failures.remove(name);
                }
            } catch (Throwable t) {
                long now = System.currentTimeMillis();
                long[] state = failures.get(name);
                if (state == null) {
                    failures.put(name, new long[]{now});
                    getLogger().at(Level.WARNING).withCause(t).log("Atlas task %s failed", name);
                } else if (now - state[0] >= GUARD_REPEAT_MS) {
                    state[0] = now;
                    getLogger().at(Level.WARNING).log("Atlas task %s is still failing with %s", name, String.valueOf(t));
                }
            }
        };
    }

    public void saveQuietly() {
        try {
            atlas.heat().save();
            diskFailures.remove("heat");
        } catch (Exception e) {
            diskFailed("heat", e);
            getLogger().at(Level.WARNING).withCause(e).log("Atlas could not save heat data");
        }
        try {
            atlas.links().save();
            diskFailures.remove("sessions");
        } catch (Exception e) {
            diskFailed("sessions", e);
            getLogger().at(Level.WARNING).withCause(e).log("Atlas could not save sessions");
        }
        try {
            atlas.kills().save();
            atlas.lastSeen().save();
            diskFailures.remove("kills");
        } catch (Exception e) {
            diskFailed("kills", e);
            getLogger().at(Level.WARNING).withCause(e).log("Atlas could not save the kill feed and last seen list");
        }
    }

    private void diskFailed(String name, Exception e) {
        diskFailures.put(name, new String[]{Long.toString(System.currentTimeMillis()), String.valueOf(e.getMessage())});
    }

    /** Failing tasks and disk writes for the admin health answer. */
    private void healthExtras(JsonObject o) {
        JsonArray degraded = new JsonArray();
        JsonArray tasks = new JsonArray();
        for (Map.Entry<String, long[]> e : failures.entrySet()) {
            JsonObject row = new JsonObject();
            row.addProperty("task", e.getKey());
            row.addProperty("since", e.getValue()[0]);
            tasks.add(row);
        }
        o.add("failingTasks", tasks);
        if (!tasks.isEmpty()) {
            degraded.add("taskFailing");
        }
        JsonArray disk = new JsonArray();
        for (Map.Entry<String, String[]> e : diskFailures.entrySet()) {
            JsonObject row = new JsonObject();
            row.addProperty("file", e.getKey());
            row.addProperty("at", Long.parseLong(e.getValue()[0]));
            row.addProperty("error", e.getValue()[1]);
            disk.add(row);
        }
        o.add("diskWriteFailures", disk);
        if (!disk.isEmpty()) {
            degraded.add("diskWriteFailing");
        }
        o.add("degraded", degraded);
    }

    /**
     * Folders and jars in the mods folder whose names suggest another map mod, which may want the
     * same port. BetterMap draws the in game map only and serves no web page, so it is left out.
     */
    private String otherMapMods() {
        Path mods = getDataDirectory().toAbsolutePath().getParent();
        if (mods == null || !Files.isDirectory(mods)) {
            return "";
        }
        List<String> found = new ArrayList<>();
        try (var stream = Files.list(mods)) {
            stream.map(p -> p.getFileName().toString())
                    .filter(n -> {
                        String l = n.toLowerCase(Locale.ROOT);
                        return l.contains("map") && !l.contains("atlas") && !l.contains("bettermap");
                    })
                    .sorted()
                    .limit(5)
                    .forEach(found::add);
        } catch (Exception ignored) {
            // An unreadable mods folder only leaves the hint out.
        }
        return String.join(", ", found);
    }

    /** Reloads atlas.json and moves the web server when the address changed. Returns a problem or null. */
    public String reload() {
        AtlasConfig next;
        try {
            next = AtlasConfig.load(getDataDirectory());
        } catch (Exception e) {
            // Fail closed. Settings the owner meant to tighten must not stay loose, so the map goes down.
            configError = String.valueOf(e.getMessage());
            logConfigError();
            server.stop();
            return "atlas.json has an error, so the web map is down until it is fixed. " + configError;
        }
        configError = null;
        warnConfig(next);
        AtlasConfig before = atlas.config();
        atlas.setConfig(next);
        applyWorldSettings(before, next);
        warnRejectedProxies();
        pollSnapshot();
        pollMapMod();
        atlas.leaderboard().poll();
        pollChronicle(!java.util.Objects.equals(before == null ? null : before.chronicleUrl, next.chronicleUrl));
        schedulePlayers();
        String problem;
        if (server.running()) {
            problem = server.reconfigure();
        } else {
            problem = server.startWithRetry(scheduler);
        }
        warnPublicUrl();
        return problem;
    }

    /**
     * Applies changed per world settings after a reload. A world that was switched off drops its tiles in
     * memory and its queued renders, its tiles on disk stay so switching it on again brings it back. Open
     * browsers are told to fetch the world list again when any world setting changed.
     */
    private void applyWorldSettings(AtlasConfig before, AtlasConfig next) {
        if (before == null) {
            return;
        }
        Set<String> names = new java.util.TreeSet<>(next.worlds.keySet());
        names.addAll(before.worlds.keySet());
        boolean changed = !before.defaultWorld.equals(next.defaultWorld);
        for (String name : names) {
            AtlasConfig.WorldSettings a = before.world(name);
            AtlasConfig.WorldSettings b = next.world(name);
            if (a.enabled && !b.enabled) {
                atlas.tiles().forgetMemory(name);
            }
            changed |= a.enabled != b.enabled || !java.util.Objects.equals(a.hidden, b.hidden)
                    || !java.util.Objects.equals(a.displayName, b.displayName) || !sameLimit(a.renderLimit, b.renderLimit);
        }
        if (changed) {
            JsonObject o = new JsonObject();
            o.addProperty("updatedAt", System.currentTimeMillis());
            atlas.sse().broadcastAll("worlds", Json.GSON.toJson(o), c -> true);
        }
    }

    private static boolean sameLimit(AtlasConfig.RenderLimit a, AtlasConfig.RenderLimit b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.x == b.x && a.z == b.z && a.radius == b.radius;
    }

    /** True while atlas.json is rejected and the web server is kept down. */
    public String configError() {
        return configError;
    }

    private void logConfigError() {
        getLogger().at(Level.SEVERE).log("Atlas keeps its web server down because atlas.json is invalid, %s. "
                + "Fix the file and run /atlas reload.", configError);
    }

    private void warnConfig(AtlasConfig cfg) {
        if (cfg.adminTokenReplaced) {
            getLogger().at(Level.WARNING).log("Atlas replaced the adminToken in atlas.json because it was shorter than %d "
                    + "characters. The new random token is in the file and the old one no longer works.", AtlasConfig.MIN_ADMIN_TOKEN);
        }
        if (!cfg.unknownKeys.isEmpty()) {
            getLogger().at(Level.WARNING).log("Atlas ignores unknown keys in atlas.json, %s", String.join(", ", cfg.unknownKeys));
        }
        if (cfg.created) {
            getLogger().at(Level.INFO).log("Atlas wrote a new atlas.json with every option at its default");
        } else if (!cfg.addedKeys.isEmpty()) {
            getLogger().at(Level.INFO).log("Atlas added missing keys to atlas.json, %s", String.join(", ", cfg.addedKeys));
        }
    }

    private void warnRejectedProxies() {
        for (String entry : atlas.proxies().rejected()) {
            getLogger().at(Level.WARNING).log("Atlas ignores the trusted proxy entry %s because it is not an address or CIDR range", entry);
        }
    }

    private void warnPublicUrl() {
        AtlasConfig cfg = atlas.config();
        if (!cfg.hasPublicUrl() && ("0.0.0.0".equals(cfg.bind) || "::".equals(cfg.bind))) {
            getLogger().at(Level.WARNING).log("Atlas has no publicUrl in atlas.json and listens on every address, so players "
                    + "in game get no working link to the map, only the port. A localhost link would only work on this "
                    + "machine. Set publicUrl to the address players should open, for example http://play.example.com:%d/",
                    cfg.port);
        }
    }

    /**
     * One line per suite plugin for /atlas integrations, with the version the plugin manager holds, what Atlas
     * takes from it or what it takes from Atlas, and the key that controls the link. Never touches a class of
     * another plugin.
     */
    public List<String> integrationLines() {
        AtlasConfig cfg = atlas.config();
        List<String> out = new ArrayList<>();
        TrackerLink t = trackerLink;
        out.add(line("TrackerAPI", "tracker " + cfg.trackerMode().key(), found -> switch (trackerSource) {
            case TRACKER -> t != null && t.bound() ? "feeds the heat layer and the kill feed"
                    : "is chosen but not bound, so the heat layer and the kill feed stay empty";
            case OWN -> "is left alone, Atlas records block activity and kills itself";
            case NONE -> "is too old, Atlas records no block activity and no kills";
        }, trackerSource == TrackerPresence.Source.OWN ? "Atlas records block activity and kills itself"
                : "Atlas records no block activity and no kills"));
        dev.ryvrook.atlas.integration.CoinageLink money = coinageLink;
        out.add(line("Coinage", "coinage " + cfg.coinageMode().key(), found ->
                cfg.coinageMode() == dev.ryvrook.atlas.config.TrackerMode.NEVER ? "is left alone"
                        : money != null && money.bound()
                        ? (cfg.showOwnMoney ? "shows a linked player their own money" : "is bound, showOwnMoney is false")
                        : "is not bound, reload Atlas to bind it", "no balances in the account menu"));
        DominionSnapshot snap = atlas.snapshot();
        out.add(line("Dominion", "dominion " + cfg.dominionMode().key(), found -> (snap.off() ? "is left alone"
                : snap.present() ? "fills the faction layers from a snapshot of " + snap.view().factions().size() + " factions"
                : "has written no snapshot yet") + layers("Ryvrook:Dominion"),
                snap.off() || !snap.present() ? "no faction layers" : "faction layers from the snapshot left on disk"));
        out.add(line("Feats", "feats " + cfg.featsMode().key(), found -> (atlas.leaderboard().off() ? "is left alone"
                : atlas.leaderboard().root() != null ? "fills the leaderboard panel from its export"
                : "has written no export yet") + layers("Ryvrook:Feats"), "no leaderboard panel"));
        out.add(line("ChronicleStats", "chronicle " + cfg.chronicleMode().key(), found -> (atlas.chronicle().off() ? "is left alone"
                : atlas.chronicle().available() ? "fills the Chronicle tab from its export"
                : "has written no export yet") + layers("Ryvrook:ChronicleStats"), "no Chronicle tab"));
        out.add(line("Lullaby", null, found -> "runs and has no link with Atlas", "nothing changes"));
        out.add(line("SocialBridge", null, found -> "reads the map address, hidden players and link codes through AtlasApi" + layers("Ryvrook:SocialBridge"),
                "nothing changes"));
        out.add(line("RestAPI", null, found -> "serves the map status, worlds, layers and markers through AtlasApi" + layers("Ryvrook:RestAPI"),
                "nothing changes"));
        return out;
    }

    private String line(String name, String key, java.util.function.Function<dev.ryvrook.atlas.integration.PluginPresence.Found, String> running,
                        String without) {
        dev.ryvrook.atlas.integration.PluginPresence.Found found = dev.ryvrook.atlas.integration.PluginPresence.find("Ryvrook", name);
        String text;
        if (found == null) {
            text = name + " is not installed, " + without;
        } else {
            String state = found.identity() instanceof com.hypixel.hytale.server.core.plugin.PluginBase b && b.getState() != null
                    ? b.getState().name() : "ENABLED";
            text = "ENABLED".equals(state) ? name + " " + found.version() + " " + running.apply(found)
                    : name + " " + found.version() + " is installed but " + state.toLowerCase(java.util.Locale.ROOT) + ", " + without;
        }
        return key == null ? text : text + " (" + key + ")";
    }

    private String layers(String pluginId) {
        int n = atlas.markers().apiLayerCount(pluginId);
        return n == 0 ? "" : ", with " + n + (n == 1 ? " layer" : " layers") + " of its own";
    }

    /** The address players should open, or null when the owner has not set publicUrl. */
    public String publicUrl() {
        AtlasConfig cfg = atlas.config();
        return cfg.hasPublicUrl() ? cfg.publicUrl.trim() : null;
    }

    public int port() {
        return atlas.config().port;
    }
}
