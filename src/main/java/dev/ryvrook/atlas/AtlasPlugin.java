package dev.ryvrook.atlas;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import dev.ryvrook.atlas.http.ApiServer;
import dev.ryvrook.atlas.http.AtlasVersion;
import dev.ryvrook.atlas.systems.BlockActivitySystem;
import dev.ryvrook.atlas.systems.ChunkSaveSystem;
import dev.ryvrook.atlas.systems.DeathSystem;
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
    private volatile ScheduledThreadPoolExecutor scheduler;
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();
    private ScheduledFuture<?> playerTask;
    /** Per task failure state for guard, as [time of the last log line]. Absent while the task succeeds. */
    private final Map<String, long[]> failures = new ConcurrentHashMap<>();
    private long lastSnapshotWarn;
    private long warnedErrorAt;

    // Player stream state, touched only by the players task.
    private final Map<String, String> lastSignature = new HashMap<>();
    private Map<String, Set<String>> lastVisible = new HashMap<>();
    private long lastFull;

    // World watch state, touched only by the world watch task.
    private Set<String> knownWorlds;
    private final Set<String> transientWorlds = ConcurrentHashMap.newKeySet();

    public AtlasPlugin(JavaPluginInit init) {
        super(init);
    }

    public Atlas atlas() {
        return atlas;
    }

    public ApiServer server() {
        return server;
    }

    @Override
    protected void setup() {
        try {
            AtlasVersion.VERSION = String.valueOf(getManifest().getVersion());
        } catch (RuntimeException ignored) {
            // Keep the built in version string.
        }
        AtlasConfig cfg;
        try {
            cfg = AtlasConfig.load(getDataDirectory());
        } catch (Exception e) {
            getLogger().at(Level.WARNING).withCause(e).log("Atlas could not read atlas.json, using defaults");
            cfg = new AtlasConfig();
        }
        warnReplacedToken(cfg);
        atlas = new Atlas(cfg, getDataDirectory());
        atlas.heat().load();
        atlas.links().load();
        atlas.kills().load();
        atlas.lastSeen().load();
        loadTransientWorlds();
        server = new ApiServer(atlas, getLogger());

        getCommandRegistry().registerCommand(new AtlasCommand(this));
        getEntityStoreRegistry().registerSystem(BlockActivitySystem.breaks(atlas.heat()));
        getEntityStoreRegistry().registerSystem(BlockActivitySystem.places(atlas.heat()));
        getEntityStoreRegistry().registerSystem(new DeathSystem(atlas::addKill));
        getChunkStoreRegistry().registerSystem(new ChunkSaveSystem(atlas.tiles()));
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
        getLogger().at(Level.INFO).log("Atlas set up, web server will listen on %s:%d", cfg.bind, cfg.port);
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

        Path snap = atlas.snapshot().path();
        if (!Files.isRegularFile(snap)) {
            getLogger().at(Level.INFO).log("Atlas found no Dominion snapshot at %s and runs standalone until the file appears",
                    snap.toAbsolutePath());
        }
        pollSnapshot();
        pollMapMod();
        atlas.seedFeed();
        atlas.leaderboard().poll();
        try {
            server.start();
            getLogger().at(Level.INFO).log("Atlas web map listening on http://%s/", server.boundAddress());
        } catch (Exception e) {
            getLogger().at(Level.SEVERE).withCause(e).log("Atlas could not start its web server on %s:%d",
                    atlas.config().bind, atlas.config().port);
        }
        warnPublicUrl();
        tasks.add(ex.scheduleWithFixedDelay(guard("snapshot", this::pollSnapshot), 1, 1, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("world info",
                () -> atlas.live().refreshOnWorldThreads(atlas.config().showPlayerHealth || atlas.sse().size() > 0)),
                1, 2, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("world watch", this::watchWorlds), 5, 5, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("world events", () -> atlas.engine().pollEvents()), 7, 5, TimeUnit.SECONDS));
        java.util.concurrent.Executor markerWork = r -> ex.execute(guard("markers", r));
        tasks.add(ex.scheduleWithFixedDelay(guard("markers", () -> atlas.pollMarkers(markerWork)), 6, 5, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("leaderboard", this::pollLeaderboard), 5, 5, TimeUnit.SECONDS));
        tasks.add(ex.scheduleWithFixedDelay(guard("map settings", this::pollMapMod), 15, 15, TimeUnit.SECONDS));
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
        }), 10, 600, TimeUnit.SECONDS));
        schedulePlayers();
    }

    @Override
    protected void shutdown() {
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
        if (server != null) {
            server.stop();
        }
        if (atlas != null) {
            atlas.tiles().shutdown();
            saveQuietly();
        }
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
        atlas.sse().broadcast("players", c -> true, c -> {
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
        boolean wasPresent = atlas.snapshot().present();
        boolean changed = atlas.snapshot().poll(atlas::addFeed, this::addSnapshotChat);
        warnSnapshotError();
        if (!changed) {
            return;
        }
        boolean present = atlas.snapshot().present();
        if (present != wasPresent) {
            getLogger().at(Level.INFO).log(present
                    ? "Atlas found the Dominion snapshot at %s"
                    : "Atlas lost the Dominion snapshot at %s and runs standalone", atlas.snapshot().path().toAbsolutePath());
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
        atlas.live().worlds().remove(name);
        atlas.personal().dropWorld(name);
        long at = System.currentTimeMillis();
        for (EngineData.ActiveEvent e : atlas.engine().dropWorld(name)) {
            atlas.worldEventChanged(e, "end", at);
        }
        if (transientWorld) {
            atlas.tiles().dropWorld(name);
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
        } catch (Exception e) {
            getLogger().at(Level.WARNING).withCause(e).log("Atlas could not save heat data");
        }
        try {
            atlas.links().save();
        } catch (Exception e) {
            getLogger().at(Level.WARNING).withCause(e).log("Atlas could not save sessions");
        }
        try {
            atlas.kills().save();
            atlas.lastSeen().save();
        } catch (Exception e) {
            getLogger().at(Level.WARNING).withCause(e).log("Atlas could not save the kill feed and last seen list");
        }
    }

    /** Reloads atlas.json and moves the web server when the address changed. Returns a problem or null. */
    public String reload() {
        AtlasConfig next;
        try {
            next = AtlasConfig.load(getDataDirectory());
        } catch (Exception e) {
            return "atlas.json has an error. " + e.getMessage();
        }
        warnReplacedToken(next);
        atlas.setConfig(next);
        warnRejectedProxies();
        pollSnapshot();
        pollMapMod();
        atlas.leaderboard().poll();
        schedulePlayers();
        String problem;
        if (server.running()) {
            problem = server.reconfigure();
        } else {
            try {
                server.start();
                problem = null;
            } catch (Exception e) {
                problem = "The web server could not bind to " + next.bind + ":" + next.port + ". " + e.getMessage();
            }
        }
        warnPublicUrl();
        return problem;
    }

    private void warnReplacedToken(AtlasConfig cfg) {
        if (cfg.adminTokenReplaced) {
            getLogger().at(Level.WARNING).log("Atlas replaced the adminToken in atlas.json because it was shorter than %d "
                    + "characters. The new random token is in the file and the old one no longer works.", AtlasConfig.MIN_ADMIN_TOKEN);
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

    /** The address players should open, or null when the owner has not set publicUrl. */
    public String publicUrl() {
        AtlasConfig cfg = atlas.config();
        return cfg.hasPublicUrl() ? cfg.publicUrl.trim() : null;
    }

    public int port() {
        return atlas.config().port;
    }
}
