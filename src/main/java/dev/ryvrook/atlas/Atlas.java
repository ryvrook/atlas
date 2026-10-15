package dev.ryvrook.atlas;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.ryvrook.atlas.auth.LinkService;
import dev.ryvrook.atlas.auth.Viewer;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.data.ChronicleExport;
import dev.ryvrook.atlas.data.DominionSnapshot;
import dev.ryvrook.atlas.data.EngineData;
import dev.ryvrook.atlas.data.HeatTracker;
import dev.ryvrook.atlas.data.KillLog;
import dev.ryvrook.atlas.data.LastSeen;
import dev.ryvrook.atlas.data.Leaderboard;
import dev.ryvrook.atlas.data.LiveState;
import dev.ryvrook.atlas.data.MapModSettings;
import dev.ryvrook.atlas.data.MarkerMerge;
import dev.ryvrook.atlas.data.Payloads;
import dev.ryvrook.atlas.data.PersonalMarkers;
import dev.ryvrook.atlas.http.AtlasVersion;
import dev.ryvrook.atlas.http.SseHub;
import dev.ryvrook.atlas.http.TrustedProxies;
import dev.ryvrook.atlas.markers.MarkerRoutes;
import dev.ryvrook.atlas.markers.MarkerService;
import dev.ryvrook.atlas.tiles.ExploredIndex;
import dev.ryvrook.atlas.tiles.TileService;
import dev.ryvrook.atlas.timeline.EventLog;
import dev.ryvrook.atlas.util.Json;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;

/**
 * Shared state behind the web server and the commands. Every payload the browser sees is built
 * here, so visibility rules live in one place.
 */
public final class Atlas {
    public static final String ADMIN_PERMISSION = "atlas.admin";
    /** The browser API version, sent in api/worlds and the hello event. */
    public static final int API_VERSION = 1;
    private static final String HIDDEN_LAYER = "This layer is not visible to you.";
    private static final int FEED_KEEP = 100;
    private static final int CHAT_KEEP = 100;

    private volatile AtlasConfig config;
    private final Path dataDir;
    private final long startedAt = System.currentTimeMillis();
    private final DominionSnapshot snapshot;
    private final LiveState live = new LiveState();
    private final HeatTracker heat;
    private final LinkService links;
    private final TileService tiles;
    private final SseHub sse = new SseHub();
    private final Deque<JsonObject> feed = new ArrayDeque<>();
    private final Deque<JsonObject> chat = new ArrayDeque<>();
    private final Map<UUID, long[]> adminCache = new ConcurrentHashMap<>();
    private volatile TrustedProxies proxies;
    private final KillLog kills;
    private final LastSeen lastSeen;
    private final Leaderboard leaderboard;
    private final ChronicleExport chronicle;
    /** The session API for the Chronicle web app, published from start until shutdown. */
    private final dev.ryvrook.atlas.auth.SessionService sessionApi;
    private final EngineData engine = new EngineData();
    private final PersonalMarkers personal = new PersonalMarkers();
    private final MapModSettings mapMod;
    /** Section hashes of the last snapshot announcement, so only changed sections are announced. */
    private final Map<String, Long> announced = new ConcurrentHashMap<>();
    /** Last hidden flag Dominion wrote for each player, kept after they leave the snapshot. */
    private final Map<UUID, Boolean> snapshotHidden = new ConcurrentHashMap<>();
    /** The Atlas scheduler, for work that must leave the world thread. Null until the plugin hands it over. */
    private volatile Executor background;
    /** Worlds seen on the last marker poll, with whether each is a portal or instance world. */
    private final Map<String, Boolean> seenWorlds = new ConcurrentHashMap<>();
    /** Marker layers from the API, the drop folder and the admin commands. */
    private final MarkerService markers;
    private final MarkerRoutes markerRoutes;
    /** The Coinage bridge, or null while Coinage is not installed. */
    private volatile dev.ryvrook.atlas.integration.CoinageLink coinage;

    public Atlas(AtlasConfig config, Path dataDir) {
        this.config = config;
        this.dataDir = dataDir;
        this.snapshot = new DominionSnapshot(resolveSnapshot(config));
        this.heat = new HeatTracker(dataDir);
        this.links = new LinkService(dataDir);
        this.tiles = new TileService(dataDir, config.tileCacheMb);
        this.proxies = TrustedProxies.parse(config.trustedProxies);
        this.kills = new KillLog(dataDir);
        this.lastSeen = new LastSeen(dataDir);
        this.leaderboard = new Leaderboard(resolveFeats(config));
        this.chronicle = new ChronicleExport(resolveChronicle(config));
        this.mapMod = new MapModSettings(resolveMapMod(config));
        this.markers = new MarkerService(this, dataDir);
        this.markerRoutes = new MarkerRoutes(this, markers);
        this.sessionApi = new dev.ryvrook.atlas.auth.SessionService(links, this::hiddenPlayers);
        live.setHideRules(config.hidePlayersCrouching, config.hidePlayersInvisible);
        // Timeline events. Names are recorded as they are, the hidden rule is applied when they are served.
        live.onJoin(p -> {
            Double[] at = eventPlace(p.uuid(), p.world(), p.x(), p.y(), p.z());
            EventLog.record(p.world(), System.currentTimeMillis(), "join", at[0], at[1], p.name(), p.uuid(),
                    p.name() + " joined the server.");
        });
        leaderboard.onEvent(this::featEvent);
        engine.onEvents(new EngineData.EventListener() {
            @Override
            public void started(EngineData.ActiveEvent e) {
                worldEventChanged(e, "start", System.currentTimeMillis());
            }

            @Override
            public void ended(EngineData.ActiveEvent e, long at) {
                worldEventChanged(e, "end", at);
            }
        });
    }

    /** The snapshot path resolved against the server working directory. */
    public static Path resolveSnapshot(AtlasConfig cfg) {
        return Path.of(cfg.dominionSnapshot).toAbsolutePath().normalize();
    }

    /** The Feats export path resolved against the server working directory. */
    public static Path resolveFeats(AtlasConfig cfg) {
        return Path.of(cfg.featsExport).toAbsolutePath().normalize();
    }

    /** The Chronicle stats export path resolved against the server working directory. */
    public static Path resolveChronicle(AtlasConfig cfg) {
        return Path.of(cfg.chronicleExport).toAbsolutePath().normalize();
    }

    /** The BetterMap folder resolved against the server working directory. */
    public static Path resolveMapMod(AtlasConfig cfg) {
        return Path.of(cfg.betterMapDir).toAbsolutePath().normalize();
    }

    public AtlasConfig config() {
        return config;
    }

    public MapModSettings mapMod() {
        return mapMod;
    }

    /** The BetterMap settings in force, or null when the file is absent or honorBetterMap is off. */
    public MapModSettings.Settings mapRules() {
        return config.honorBetterMap ? mapMod.current() : null;
    }

    public void setConfig(AtlasConfig cfg) {
        this.config = cfg;
        snapshot.setPath(resolveSnapshot(cfg));
        leaderboard.setPath(resolveFeats(cfg));
        chronicle.setPath(resolveChronicle(cfg));
        mapMod.setDir(resolveMapMod(cfg));
        tiles.setCacheMb(cfg.tileCacheMb);
        proxies = TrustedProxies.parse(cfg.trustedProxies);
        live.setHideRules(cfg.hidePlayersCrouching, cfg.hidePlayersInvisible);
        adminCache.clear();
    }

    public TrustedProxies proxies() {
        return proxies;
    }

    public Path dataDir() {
        return dataDir;
    }

    public long startedAt() {
        return startedAt;
    }

    public DominionSnapshot snapshot() {
        return snapshot;
    }

    public LiveState live() {
        return live;
    }

    public HeatTracker heat() {
        return heat;
    }

    public LinkService links() {
        return links;
    }

    public TileService tiles() {
        return tiles;
    }

    public SseHub sse() {
        return sse;
    }

    public KillLog kills() {
        return kills;
    }

    public LastSeen lastSeen() {
        return lastSeen;
    }

    public Leaderboard leaderboard() {
        return leaderboard;
    }

    /** The Chronicle stats export. */
    public ChronicleExport chronicle() {
        return chronicle;
    }

    /** The session API object, published while Atlas runs. */
    public dev.ryvrook.atlas.auth.SessionService sessionApi() {
        return sessionApi;
    }

    public EngineData engine() {
        return engine;
    }

    public PersonalMarkers personal() {
        return personal;
    }

    /** Marker layers from the API, the drop folder and the admin commands. */
    public MarkerService markers() {
        return markers;
    }

    /** The api/layers routes, called by the web server after {@link #route}. */
    public MarkerRoutes markerRoutes() {
        return markerRoutes;
    }

    /** Withdraws the marker API so other plugins run their disable hooks. Called when the plugin stops. */
    public void shutdown() {
        sessionApi.stop();
        markers.stop();
    }

    /** Drops admin permission answers older than the cache time. Runs every ten minutes. */
    public void pruneAdminCache() {
        long now = System.currentTimeMillis();
        adminCache.values().removeIf(v -> now - v[0] > 30_000);
    }

    /**
     * Compares the section hashes of the current snapshot with the last announcement and returns the
     * sections that changed, remembering the new hashes. Empty when nothing the browser fetches changed.
     */
    public List<String> changedSections() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : snapshot.view().sectionHashes.entrySet()) {
            Long prev = announced.put(e.getKey(), e.getValue());
            if (prev == null || !prev.equals(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** The server object shared by api/worlds and the hello event. */
    public JsonObject serverJson() {
        JsonObject o = new JsonObject();
        o.addProperty("apiVersion", API_VERSION);
        o.addProperty("title", config.publicTitle);
        Payloads.put(o, "publicUrl", config.hasPublicUrl() ? config.publicUrl.trim() : null);
        o.addProperty("version", AtlasVersion.VERSION);
        long now = System.currentTimeMillis();
        o.addProperty("now", now);
        o.addProperty("uptimeSeconds", (now - startedAt) / 1000);
        o.addProperty("betterMap", mapRules() != null);
        return o;
    }

    /** The hello event body {apiVersion, viewer, server}. */
    public JsonObject helloPayload(Viewer v) {
        JsonObject hello = new JsonObject();
        hello.addProperty("apiVersion", API_VERSION);
        hello.add("viewer", viewerJson(v));
        hello.add("server", serverJson());
        return hello;
    }

    /** The hello body without clock values, compared as text to spot identity changes. */
    public JsonObject helloIdentity(Viewer v) {
        JsonObject hello = helloPayload(v);
        JsonObject server = hello.getAsJsonObject("server");
        server.remove("now");
        server.remove("uptimeSeconds");
        return hello;
    }

    /** Hands Atlas the scheduler, which runs work that must not hold up a world thread. */
    public void useExecutor(Executor executor) {
        this.background = executor;
        // The plugin hands the scheduler over from start, which is when the marker API goes live.
        markers.start();
    }

    /** Runs a task on the Atlas scheduler, or on a fresh virtual thread before the scheduler is known. */
    public void runLater(Runnable r) {
        runInBackground(r);
    }

    /** Runs a task on the Atlas scheduler, or on a fresh virtual thread before the scheduler is known. */
    private void runInBackground(Runnable r) {
        Executor ex = background;
        if (ex != null) {
            try {
                ex.execute(r);
                return;
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Shutting down. Fall through.
            }
        }
        Thread.ofVirtual().name("Atlas-background").start(r);
    }

    private static void log(Level level, String format, Object... args) {
        try {
            com.hypixel.hytale.logger.HytaleLogger.get("Atlas").at(level).log(String.format(Locale.ROOT, format, args));
        } catch (RuntimeException | LinkageError ignored) {
            // No logging backend, as in unit tests.
        }
    }

    // ---- identity ----------------------------------------------------------

    public boolean hasAdminPermission(UUID uuid) {
        long now = System.currentTimeMillis();
        long[] cached = adminCache.get(uuid);
        if (cached != null && now - cached[0] < 30_000) {
            return cached[1] == 1;
        }
        boolean admin = false;
        try {
            PermissionsModule pm = PermissionsModule.get();
            admin = pm != null && pm.hasPermission(uuid, ADMIN_PERMISSION);
        } catch (RuntimeException ignored) {
            // Permission backend not ready.
        }
        adminCache.put(uuid, new long[]{now, admin ? 1 : 0});
        return admin;
    }

    /**
     * The faction tag of a player. Dominion lists the uuid of every member under memberIds, so this
     * also works while the player is offline. Older snapshots without member lists only know the
     * factions of players who are online.
     */
    public String factionOf(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return snapshot.view().factionOfPlayer.get(uuid.toString().toLowerCase(Locale.ROOT));
    }

    public Viewer viewer(LinkService.Session session, boolean tokenAdmin, String asTag) {
        Viewer v;
        if (session != null) {
            v = new Viewer(session.uuid(), session.name(), factionOf(session.uuid()),
                    tokenAdmin || hasAdminPermission(session.uuid()), false);
        } else if (tokenAdmin) {
            v = new Viewer(null, "admin", null, true, false);
        } else {
            v = Viewer.PUBLIC;
        }
        if (v.admin() && asTag != null && !asTag.isBlank()) {
            v = v.as(asTag.trim());
        }
        return v;
    }

    /** Hands Atlas the Coinage bridge, or null to drop it. */
    public void useCoinage(dev.ryvrook.atlas.integration.CoinageLink link) {
        this.coinage = link;
    }

    /** True when this viewer is a linked player who may see their own balance. */
    private boolean seesOwnMoney(Viewer v) {
        return config.showOwnMoney && coinage != null && v.uuid() != null && !v.viewingAs();
    }

    /**
     * The api/money body for the viewer's own player, {available, currency, balance, netWorth}, with the
     * amounts formatted by Coinage. Only ever about the viewer, never another player.
     */
    public JsonObject moneyPayload(Viewer v) {
        JsonObject o = new JsonObject();
        dev.ryvrook.atlas.integration.CoinageLink link = coinage;
        dev.ryvrook.atlas.integration.CoinageLink.Money m = null;
        if (seesOwnMoney(v) && link != null) {
            try {
                m = link.money(v.uuid());
            } catch (RuntimeException | LinkageError e) {
                log(Level.FINE, "Atlas could not read a balance from Coinage, %s", e);
            }
        }
        o.addProperty("available", m != null);
        Payloads.put(o, "currency", m == null ? null : m.currency());
        Payloads.put(o, "balance", m == null ? null : m.balance());
        Payloads.put(o, "netWorth", m == null ? null : m.netWorth());
        return o;
    }

    public JsonObject viewerJson(Viewer v) {
        JsonObject o = new JsonObject();
        o.addProperty("linked", v.linked());
        Payloads.put(o, "name", v.name());
        Payloads.put(o, "faction", v.faction());
        o.addProperty("admin", v.admin());
        Payloads.put(o, "viewingAs", v.viewingAs());
        o.addProperty("money", seesOwnMoney(v));
        JsonObject layers = new JsonObject();
        for (String layer : config.visibility.keySet()) {
            layers.addProperty(layer, v.canSee(config, layer) && (!"players".equals(layer) || config.showPlayers || v.admin()));
        }
        o.add("layers", layers);
        return o;
    }

    // ---- players -----------------------------------------------------------

    /** True when this viewer may see the players layer at all. */
    public boolean seesPlayers(Viewer v) {
        return v.canSee(config, "players") && (config.showPlayers || v.admin());
    }

    /**
     * Players visible to the viewer, optionally limited to one world. This is the players event body,
     * so it always carries hidden, full and left. The live push overwrites full and left on partial frames.
     */
    public JsonObject playersPayload(Viewer v, String worldFilter, boolean withTrails) {
        AtlasConfig cfg = config;
        JsonObject out = new JsonObject();
        long now = System.currentTimeMillis();
        out.addProperty("at", now);
        JsonArray list = new JsonArray();
        boolean visible = seesPlayers(v);
        out.addProperty("hidden", !visible);
        out.addProperty("full", true);
        out.add("players", list);
        out.add("left", new JsonArray());
        if (!visible) {
            return out;
        }
        boolean trails = withTrails && v.canSee(cfg, "paths");
        boolean factionLimited = v.factionLimited(cfg, "players");
        DominionSnapshot.View view = snapshot.view();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (LiveState.PlayerSample p : live.players()) {
            String key = p.uuid().toString().toLowerCase(Locale.ROOT);
            seen.add(key);
            if (worldFilter != null && !worldFilter.equals(p.world())) {
                continue;
            }
            JsonObject snap = view.playerByUuid.get(key);
            JsonObject row = playerRow(v, cfg, view, key, p.name(), p.world(), p.x(), p.y(), p.z(), snap,
                    p.health(), p.maxHealth(), factionLimited, true, "live");
            if (row == null) {
                continue;
            }
            if (trails) {
                JsonArray t = new JsonArray();
                for (double[] pt : live.trail(p.uuid())) {
                    t.add(Payloads.pair(Math.round(pt[0]), Math.round(pt[1])));
                }
                row.add("trail", t);
            }
            list.add(row);
        }
        // Snapshot players the live API does not list, only while the snapshot is fresh.
        if (snapshot.present() && now - view.loadedAt < 15_000) {
            for (Map.Entry<String, JsonObject> e : view.playerByUuid.entrySet()) {
                if (seen.contains(e.getKey())) {
                    continue;
                }
                JsonObject s = e.getValue();
                String world = Json.str(s, "world");
                if (worldFilter != null && !worldFilter.equals(world)) {
                    continue;
                }
                // The snapshot may leave x, y and z null or out, for hidden players and whenever its owner
                // does not export positions. Such players are listed without a position, never at the origin.
                double sx = Json.num(s, "x", Double.NaN);
                double sy = Json.num(s, "y", Double.NaN);
                double sz = Json.num(s, "z", Double.NaN);
                boolean located = !Double.isNaN(sx) && !Double.isNaN(sz);
                JsonObject row = playerRow(v, cfg, view, e.getKey(), Json.str(s, "name"), world,
                        located ? sx : 0, Double.isNaN(sy) ? 0 : sy, located ? sz : 0, s, null, null, factionLimited,
                        located, "snapshot");
                if (row != null) {
                    list.add(row);
                }
            }
        }
        return out;
    }

    /**
     * The api/players body {at, players, offline}. Offline holds the players seen in the last seven days
     * when the viewer may also see the lastSeen layer, and is null otherwise.
     */
    public JsonObject playersApiPayload(Viewer v, String worldFilter, boolean withTrails) {
        if (!seesPlayers(v)) {
            return Payloads.error(HIDDEN_LAYER);
        }
        JsonObject p = playersPayload(v, worldFilter, withTrails);
        JsonObject out = new JsonObject();
        out.add("at", p.get("at"));
        out.add("players", p.get("players"));
        // The offline list shows names and positions, so it needs the lastSeen layer as well.
        out.add("offline", v.canSee(config, "lastSeen") ? offlinePlayers(v, worldFilter) : JsonNull.INSTANCE);
        return out;
    }

    private JsonArray offlinePlayers(Viewer v, String worldFilter) {
        AtlasConfig cfg = config;
        DominionSnapshot.View view = snapshot.view();
        java.util.Set<UUID> online = new java.util.HashSet<>();
        for (LiveState.PlayerSample p : live.players()) {
            online.add(p.uuid());
        }
        boolean limited = v.factionLimited(cfg, "lastSeen");
        JsonArray list = new JsonArray();
        long floor = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        for (LastSeen.Entry e : lastSeen.since(floor)) {
            if (list.size() >= 200) {
                break;
            }
            if (online.contains(e.uuid()) || (worldFilter != null && !worldFilter.equals(e.world()))) {
                continue;
            }
            boolean self = v.uuid() != null && v.uuid().equals(e.uuid());
            String faction = factionOf(e.uuid());
            if (!v.admin() && !self) {
                if (isHiddenPlayer(e.uuid())) {
                    continue;
                }
                if (limited && !v.sameFaction(faction)) {
                    continue;
                }
                if (cfg.hidePlayersInOwnTerritory && !v.sameFaction(faction)
                        && inOwnTerritory(view, e.world(), faction, e.x(), e.z())) {
                    continue;
                }
            }
            JsonObject row = new JsonObject();
            Payloads.person(row, "uuid", "name", e.uuid(), e.name() == null ? "" : e.name(), hiddenFrom(v, e.uuid()));
            Payloads.put(row, "faction", faction);
            row.addProperty("at", e.at());
            row.addProperty("world", e.world());
            if (v.admin() || self) {
                row.addProperty("x", Math.round(e.x()));
                row.addProperty("z", Math.round(e.z()));
            } else if (e.y() < cfg.hideBelowY) {
                row.add("x", JsonNull.INSTANCE);
                row.add("z", JsonNull.INSTANCE);
            } else {
                row.addProperty("x", Math.floorDiv((long) Math.floor(e.x()), 32) * 32);
                row.addProperty("z", Math.floorDiv((long) Math.floor(e.z()), 32) * 32);
            }
            list.add(row);
        }
        return list;
    }

    /**
     * The one hidden player rule. A player is hidden when they are on the Atlas hide list or when the
     * Dominion snapshot marks them hidden. The snapshot flag is remembered after the player leaves the
     * snapshot so offline rows, kills and the leaderboard keep hiding them.
     */
    public boolean isHiddenPlayer(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (links.isHidden(uuid) || markers.apiHiddenReason(uuid) != null) {
            return true;
        }
        JsonObject snap = snapshot.view().playerByUuid.get(uuid.toString().toLowerCase(Locale.ROOT));
        if (snap != null) {
            boolean flag = Json.bool(snap, "hidden");
            if (flag) {
                snapshotHidden.put(uuid, Boolean.TRUE);
            } else {
                snapshotHidden.remove(uuid);
            }
            return flag;
        }
        return snapshotHidden.getOrDefault(uuid, Boolean.FALSE);
    }

    /**
     * Why a player is hidden, for admins. list for the hide list, the reason another plugin gave through the
     * API, dominion for the snapshot flag, crouching or invisible for the automatic rules, or null.
     */
    public String hiddenReason(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        if (links.isHidden(uuid)) {
            return "list";
        }
        String plugin = markers.apiHiddenReason(uuid);
        if (plugin != null) {
            return plugin;
        }
        if (isHiddenPlayer(uuid)) {
            return "dominion";
        }
        return live.autoHideReason(uuid);
    }

    /**
     * Every player whose name is hidden, for the session API. That is the hide list, players hidden through the
     * API and players the factions snapshot marks hidden, now or before they left it. The crouching and
     * invisible rules only hide positions and are left out.
     */
    public java.util.Set<UUID> hiddenPlayers() {
        java.util.Set<UUID> out = new java.util.HashSet<>(links.hiddenPlayers());
        out.addAll(markers.apiHiddenPlayers());
        snapshotHidden.forEach((uuid, flag) -> {
            if (Boolean.TRUE.equals(flag)) {
                out.add(uuid);
            }
        });
        for (Map.Entry<String, JsonObject> e : snapshot.view().playerByUuid.entrySet()) {
            UUID uuid = Payloads.uuid(e.getKey());
            if (uuid != null && Json.bool(e.getValue(), "hidden")) {
                out.add(uuid);
            }
        }
        return out;
    }

    /** Hides or shows a player for everyone but admins and themself, saved in the hide list. True when it changed. */
    public boolean setHiddenPlayer(UUID uuid, boolean hidden) {
        if (uuid == null || links.isHidden(uuid) == hidden) {
            return false;
        }
        links.toggleHidden(uuid);
        return true;
    }

    /**
     * True when the faction holds the chunk of the block x and z in that world. The own territory rule
     * of the players and last seen rows, and of places recorded for the timeline and the kill log.
     */
    static boolean inOwnTerritory(DominionSnapshot.View view, String world, String faction, double x, double z) {
        if (faction == null || world == null || !Double.isFinite(x) || !Double.isFinite(z)) {
            return false;
        }
        JsonObject t = view.territoryAt(world, Math.floorDiv((int) Math.floor(x), 32), Math.floorDiv((int) Math.floor(z), 32));
        return t != null && faction.equalsIgnoreCase(Json.str(t, "tag"));
    }

    /**
     * True when a player's place must be stored as unknown in a timeline event or the kill log. The
     * record time rules of the live map, underground, crouching or invisible, and in their own
     * territory. Cheap lookups only, so the world thread may call it.
     */
    public boolean placeWithheld(UUID player, String world, double x, double y, double z) {
        AtlasConfig cfg = config;
        boolean own = cfg.hidePlayersInOwnTerritory && player != null
                && inOwnTerritory(snapshot.view(), world, factionOf(player), x, z);
        return Payloads.placeWithheld(y, cfg.hideBelowY, live.autoHideReason(player) != null, own);
    }

    /** The x and z of a place for the event log, or nulls when the record time rules withhold it. */
    private Double[] eventPlace(UUID player, String world, double x, double y, double z) {
        return placeWithheld(player, world, x, y, z) ? new Double[]{null, null} : new Double[]{x, z};
    }

    /** Whether this viewer must not see the player's name or position. Admins and the player themself always may. */
    private boolean hiddenFrom(Viewer v, UUID uuid) {
        return Payloads.hiddenFrom(v.admin(), v.uuid(), uuid, this::isHiddenPlayer);
    }

    private JsonObject playerRow(Viewer v, AtlasConfig cfg, DominionSnapshot.View view, String uuidKey, String name,
                                 String world, double x, double y, double z, JsonObject snap,
                                 Float hp, Float maxHp, boolean factionLimited, boolean located, String source) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidKey);
        } catch (IllegalArgumentException e) {
            return null;
        }
        boolean self = v.uuid() != null && v.uuid().equals(uuid);
        boolean hidden = isHiddenPlayer(uuid) || Json.bool(snap, "hidden");
        // Crouching and invisible players are hidden from the players layer only. Their names stay in kills and feeds.
        String autoHidden = live.autoHideReason(uuid);
        String faction = snap != null ? Json.str(snap, "faction") : view.factionOfPlayer.get(uuidKey);
        if (faction != null && faction.isBlank()) {
            faction = null;
        }
        if (!self && world != null && !cfg.worldEnabled(world)) {
            // A world switched off in atlas.json is not on the map for anyone.
            return null;
        }
        if (!v.admin() && !self) {
            if (hidden || autoHidden != null) {
                return null;
            }
            if (hiddenWorld(v, world)) {
                // A portal or instance world this viewer may not open. Its players stay off every list and stream.
                return null;
            }
            if (factionLimited && !v.sameFaction(faction)) {
                return null;
            }
            if (located && cfg.hidePlayersInOwnTerritory && !v.sameFaction(faction)
                    && inOwnTerritory(view, world, faction, x, z)) {
                return null;
            }
        }
        JsonObject row = new JsonObject();
        row.addProperty("uuid", uuidKey);
        row.addProperty("name", name == null ? "" : name);
        Payloads.put(row, "world", world);
        boolean underground = false;
        if (!located) {
            // Online but without a known position. Every viewer, admins included, gets null coordinates.
            row.add("x", JsonNull.INSTANCE);
            row.add("y", JsonNull.INSTANCE);
            row.add("z", JsonNull.INSTANCE);
        } else if (v.admin()) {
            row.addProperty("x", Math.round(x * 100) / 100.0);
            row.addProperty("y", Math.round(y * 100) / 100.0);
            row.addProperty("z", Math.round(z * 100) / 100.0);
        } else if (!self && y < cfg.hideBelowY) {
            // Below the hideBelowY line the player counts as underground. Non admins get no position.
            row.add("x", JsonNull.INSTANCE);
            row.add("y", JsonNull.INSTANCE);
            row.add("z", JsonNull.INSTANCE);
            underground = true;
        } else {
            row.addProperty("x", Math.round(x));
            row.addProperty("y", Math.round(y));
            row.addProperty("z", Math.round(z));
        }
        row.addProperty("located", located);
        row.addProperty("underground", underground);
        Payloads.put(row, "hidden", v.admin() ? (Boolean) (hidden || autoHidden != null) : null);
        Payloads.put(row, "hiddenReason", v.admin() ? hiddenReason(uuid) : null);
        Payloads.put(row, "faction", faction);
        String color = snap != null ? Payloads.text(snap, "color") : null;
        if (color == null && faction != null) {
            color = Payloads.text(view.faction(faction), "color");
        }
        Payloads.put(row, "color", color);
        boolean health = (cfg.showPlayerHealth || v.admin()) && hp != null;
        Payloads.put(row, "health", health ? (Number) (Math.round(hp * 10) / 10.0) : null);
        Payloads.put(row, "maxHealth", health && maxHp != null ? (Number) (Math.round(maxHp * 10) / 10.0) : null);
        row.addProperty("self", self);
        row.addProperty("source", source);
        row.add("trail", JsonNull.INSTANCE);
        return row;
    }

    // ---- worlds ------------------------------------------------------------

    /**
     * True when this viewer may not open the world. A world disabled in atlas.json is closed to everyone,
     * admins included. Otherwise admins open every world, a world with hidden set in atlas.json follows
     * that flag, and portal and instance worlds are hidden while showPortalWorlds is false.
     */
    public boolean hiddenWorld(Viewer v, String world) {
        if (world == null) {
            return false;
        }
        AtlasConfig cfg = config;
        AtlasConfig.WorldSettings ws = cfg.world(world);
        if (!ws.enabled) {
            return true;
        }
        if (v.admin()) {
            return false;
        }
        if (ws.hidden != null) {
            return ws.hidden;
        }
        if (cfg.showPortalWorlds) {
            return false;
        }
        LiveState.WorldInfo info = live.worlds().get(world);
        return info != null && info.kind() != null && info.kind().transientWorld();
    }

    /** True when the engine has the world loaded. Without a running universe, as in tests, every name counts. */
    public boolean worldExists(String world) {
        return world != null && worldKnown(world, false);
    }

    public JsonObject worldsPayload(Viewer v) {
        JsonObject out = new JsonObject();
        out.addProperty("apiVersion", API_VERSION);
        out.addProperty("title", config.publicTitle);
        out.addProperty("standalone", !snapshot.present());
        AtlasConfig cfg = config;
        Payloads.put(out, "defaultWorld", cfg.defaultWorld.isEmpty() || hiddenWorld(v, cfg.defaultWorld)
                ? null : cfg.defaultWorld);
        out.add("viewer", viewerJson(v));
        out.add("server", serverJson());
        JsonObject tile = new JsonObject();
        tile.addProperty("size", TileService.TILE);
        tile.addProperty("chunkPixels", TileService.CHUNK_PX);
        tile.addProperty("chunkBlocks", 32);
        tile.addProperty("blocksPerTile", TileService.CHUNKS_PER_TILE * 32);
        tile.addProperty("minZoom", ExploredIndex.MIN_ZOOM);
        tile.addProperty("maxZoom", ExploredIndex.MAX_ZOOM);
        JsonObject levels = new JsonObject();
        for (int z = ExploredIndex.MIN_ZOOM; z <= ExploredIndex.MAX_ZOOM; z++) {
            int cpt = ExploredIndex.chunksPerTile(z);
            JsonObject l = new JsonObject();
            l.addProperty("pxPerBlock", TileService.TILE / (cpt * 32.0));
            l.addProperty("chunksPerTile", cpt);
            levels.add(Integer.toString(z), l);
        }
        tile.add("levels", levels);
        out.add("tile", tile);
        JsonArray worlds = new JsonArray();
        boolean showCounts = seesPlayers(v);
        MapModSettings.Settings rules = mapRules();
        Universe u = Universe.get();
        if (u != null) {
            for (World w : u.getWorlds().values()) {
                JsonObject o = new JsonObject();
                String name = w.getName();
                AtlasConfig.WorldSettings ws = cfg.world(name);
                if (!ws.enabled) {
                    // Disabled worlds are left out for everyone and answer 404 everywhere.
                    continue;
                }
                o.addProperty("name", name);
                LiveState.WorldInfo info = live.worlds().get(name);
                LiveState.WorldKind kind = info == null || info.kind() == null ? LiveState.WorldKind.plain(name) : info.kind();
                o.addProperty("kind", kind.kind());
                String display = ws.displayName != null && !ws.displayName.isBlank() ? ws.displayName.trim()
                        : kind.displayName() == null ? name : kind.displayName();
                o.addProperty("displayName", display);
                o.addProperty("hiddenWorld", hiddenWorld(v, name));
                o.add("portal", portalJson(kind.portal()));
                Payloads.put(o, "players", showCounts ? (Number) w.getPlayerCount() : null);
                if (info != null && info.spawn() != null && (rules == null || !rules.spawnHidden())) {
                    JsonObject s = new JsonObject();
                    s.addProperty("x", Math.round(info.spawn()[0]));
                    s.addProperty("y", Math.round(info.spawn()[1]));
                    s.addProperty("z", Math.round(info.spawn()[2]));
                    o.add("spawn", s);
                } else {
                    o.add("spawn", JsonNull.INSTANCE);
                }
                if (info != null && info.hour() != null) {
                    JsonObject t = new JsonObject();
                    t.addProperty("hour", info.hour());
                    t.addProperty("dayProgress", info.dayProgress() == null ? 0 : info.dayProgress());
                    o.add("time", t);
                } else {
                    o.add("time", JsonNull.INSTANCE);
                }
                o.addProperty("mapEnabled", info != null && info.mapEnabled());
                ExploredIndex ex = tiles.explored(name);
                o.addProperty("exploredChunks", ex.size());
                int[] b = ex.bounds();
                if (b != null) {
                    JsonObject bo = new JsonObject();
                    bo.addProperty("minX", b[0] * 32);
                    bo.addProperty("minZ", b[1] * 32);
                    bo.addProperty("maxX", b[2] * 32 + 32);
                    bo.addProperty("maxZ", b[3] * 32 + 32);
                    o.add("bounds", bo);
                } else {
                    o.add("bounds", JsonNull.INSTANCE);
                }
                // BetterMap draws its border only on the worlds it tracks.
                if (rules != null && rules.border() != null && rules.tracks(name)) {
                    JsonObject bd = new JsonObject();
                    bd.addProperty("shape", "circle");
                    bd.addProperty("x", rules.border().x());
                    bd.addProperty("z", rules.border().z());
                    bd.addProperty("radius", rules.border().radius());
                    o.add("border", bd);
                } else {
                    o.add("border", JsonNull.INSTANCE);
                }
                if (ws.renderLimit != null) {
                    JsonObject rl = new JsonObject();
                    rl.addProperty("shape", "circle");
                    rl.addProperty("x", ws.renderLimit.x);
                    rl.addProperty("z", ws.renderLimit.z);
                    rl.addProperty("radius", ws.renderLimit.radius);
                    o.add("renderLimit", rl);
                } else {
                    o.add("renderLimit", JsonNull.INSTANCE);
                }
                worlds.add(o);
            }
        }
        out.add("worlds", worlds);
        return out;
    }

    /** The portal state with its fixed field list, or null. */
    private static JsonElement portalJson(JsonObject p) {
        if (p == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        Payloads.put(o, "type", Payloads.text(p, "type"));
        String typeName = Payloads.text(p, "typeName");
        o.addProperty("typeName", typeName == null ? "" : typeName);
        o.addProperty("timeLimit", Payloads.flag(p, "timeLimit"));
        Payloads.put(o, "remainingSeconds", Payloads.number(p, "remainingSeconds"));
        Payloads.put(o, "elapsedSeconds", Payloads.number(p, "elapsedSeconds"));
        Payloads.put(o, "voidStage", Payloads.whole(p, "voidStage"));
        o.addProperty("voidActive", Payloads.flag(p, "voidActive"));
        o.addProperty("entries", Payloads.whole(p, "entries", 0));
        return o;
    }

    // ---- territories and points --------------------------------------------

    /** True while the Dominion snapshot is read and carries a sieges list, which the territories route draws. */
    public boolean snapshotDrawsSieges() {
        if (!snapshot.present()) {
            return false;
        }
        JsonElement s = snapshot.view().root.get("sieges");
        return s != null && s.isJsonArray();
    }

    public JsonObject territoriesPayload(Viewer v, String world) {
        JsonObject out = new JsonObject();
        DominionSnapshot.View view = snapshot.view();
        out.addProperty("world", world);
        Payloads.put(out, "at", snapshot.present() ? (Number) view.at() : null);
        JsonArray list = new JsonArray();
        JsonArray sieges = new JsonArray();
        out.add("territories", list);
        out.add("sieges", sieges);
        if (!v.canSee(config, "territories")) {
            return out;
        }
        boolean limited = v.factionLimited(config, "territories");
        for (JsonElement e : view.worldArray(world, "territories")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject t = e.getAsJsonObject();
            if (limited && !v.sameFaction(Json.str(t, "tag"))) {
                continue;
            }
            list.add(Payloads.territory(t, view.faction(Json.str(t, "tag"))));
        }
        long now = System.currentTimeMillis();
        for (JsonElement e : view.sieges()) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject s = e.getAsJsonObject();
            if (!world.equals(Json.str(s, "world"))) {
                continue;
            }
            if (limited && !v.sameFaction(Json.str(s, "attacker")) && !v.sameFaction(Json.str(s, "defender"))) {
                continue;
            }
            long ends = (long) Json.num(s, "endsAt", 0);
            if (ends > 0 && ends < now - 60_000) {
                continue;
            }
            sieges.add(Payloads.siege(s));
        }
        return out;
    }

    /**
     * Points of interest read on the world thread, waiting up to two seconds. Null when the world map is
     * not ready or the world thread did not answer in time.
     */
    private Map<String, MapMarker> pointsOfInterest(World w) {
        java.util.concurrent.CompletableFuture<Map<String, MapMarker>> f = new java.util.concurrent.CompletableFuture<>();
        try {
            w.execute(() -> {
                try {
                    Map<String, MapMarker> poi = w.getWorldMapManager().getPointsOfInterest();
                    f.complete(poi == null ? Map.of() : new java.util.LinkedHashMap<>(poi));
                } catch (Throwable t) {
                    f.complete(null);
                }
            });
            return f.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    public JsonObject pointsPayload(Viewer v, String world) {
        JsonObject out = new JsonObject();
        out.addProperty("world", world);
        JsonArray points = new JsonArray();
        JsonArray zones = new JsonArray();
        out.add("points", points);
        out.add("zones", zones);
        long now = System.currentTimeMillis();
        boolean seePoints = v.canSee(config, "points");
        boolean seeEvents = v.canSee(config, "events");
        if (seePoints || seeEvents) {
            boolean limited = v.factionLimited(config, "points");
            JsonObject events = snapshot.view().events();
            for (JsonElement e : snapshot.view().worldArray(world, "points")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject p = e.getAsJsonObject();
                long exp = (long) Json.num(p, "expiresAt", 0);
                if (exp > 0 && exp < now) {
                    continue;
                }
                String kind = Json.str(p, "kind");
                boolean eventPoint = Payloads.isEventKind(kind);
                if (eventPoint ? !seeEvents : !seePoints) {
                    continue;
                }
                String fac = Json.str(p, "faction");
                boolean ownerless = fac == null || fac.isBlank();
                // Hills, outposts and envoys are server events that everyone may contest.
                if (!eventPoint && limited && !ownerless && !v.sameFaction(fac)) {
                    continue;
                }
                points.add(Payloads.point(p, v.admin(), events));
            }
        }
        if (v.canSee(config, "zones")) {
            Universe u = Universe.get();
            World w = u == null ? null : u.getWorld(world);
            MapModSettings.Settings rules = mapRules();
            Map<String, MapMarker> poi = w == null || (rules != null && rules.hideAllPoi()) ? null : pointsOfInterest(w);
            if (poi != null) {
                for (Map.Entry<String, MapMarker> e : poi.entrySet()) {
                    MapMarker m = e.getValue();
                    if (m == null || m.transform == null || m.transform.position == null) {
                        continue;
                    }
                    if (rules != null && hiddenPoint(rules, world, m)) {
                        continue;
                    }
                    JsonObject z = new JsonObject();
                    z.addProperty("id", m.id != null ? m.id : e.getKey());
                    String name = null;
                    if (m.name != null) {
                        name = m.name.rawText != null ? m.name.rawText : m.name.messageId;
                    }
                    z.addProperty("name", prettyName(name != null ? name : e.getKey()));
                    z.addProperty("x", Math.round(m.transform.position.x));
                    z.addProperty("z", Math.round(m.transform.position.z));
                    Payloads.put(z, "icon", m.markerImage);
                    zones.add(z);
                }
            }
        }
        return out;
    }

    /** Turns translation keys such as server.map.zone.Emerald_Wilds into readable names. */
    static String prettyName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1 && !s.contains(" ")) {
            s = s.substring(dot + 1);
        }
        return s.replace('_', ' ').trim();
    }

    // ---- factions and stats ------------------------------------------------

    private JsonObject factionRow(Viewer v, DominionSnapshot.View view, JsonObject f) {
        boolean bank = v.admin() || v.sameFaction(Json.str(f, "tag"));
        JsonObject nation = v.canSee(config, "nations") ? view.nationOf(f) : null;
        return Payloads.faction(f, bank, nation);
    }

    public JsonObject factionsPayload(Viewer v) {
        JsonObject out = new JsonObject();
        Payloads.put(out, "at", snapshot.present() ? (Number) snapshot.view().at() : null);
        JsonArray list = new JsonArray();
        out.add("factions", list);
        if (!v.canSee(config, "factions")) {
            return out;
        }
        boolean limited = v.factionLimited(config, "factions");
        List<JsonObject> rows = new ArrayList<>();
        for (JsonElement e : snapshot.view().factions()) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject f = e.getAsJsonObject();
            if (Payloads.text(f, "tag") == null || (limited && !v.sameFaction(Json.str(f, "tag")))) {
                continue;
            }
            rows.add(factionRow(v, snapshot.view(), f));
        }
        rows.sort((a, b) -> Double.compare(Json.num(b, "value", 0), Json.num(a, "value", 0)));
        rows.forEach(list::add);
        return out;
    }

    /** Detail for one faction, or null when it is unknown or hidden from the viewer. */
    public JsonObject factionPayload(Viewer v, String tag) {
        if (!v.canSee(config, "factions")) {
            return null;
        }
        DominionSnapshot.View view = snapshot.view();
        JsonObject f = view.faction(tag);
        if (f == null || Payloads.text(f, "tag") == null || (v.factionLimited(config, "factions") && !v.sameFaction(tag))) {
            return null;
        }
        JsonObject out = new JsonObject();
        out.add("faction", factionRow(v, view, f));
        String realTag = Json.str(f, "tag");
        JsonArray lands = new JsonArray();
        if (v.canSee(config, "territories")) {
            for (Map.Entry<String, JsonObject> w : view.worlds.entrySet()) {
                int claims = 0;
                double sx = 0, sz = 0;
                for (JsonElement te : Json.arr(w.getValue(), "territories")) {
                    if (!te.isJsonObject()) {
                        continue;
                    }
                    JsonObject t = te.getAsJsonObject();
                    if (!realTag.equalsIgnoreCase(Json.str(t, "tag"))) {
                        continue;
                    }
                    for (JsonElement ce : Json.arr(t, "chunks")) {
                        int[] c = DominionSnapshot.chunkOf(ce);
                        if (c == null) {
                            continue;
                        }
                        claims++;
                        sx += c[0] * 32.0 + 16;
                        sz += c[1] * 32.0 + 16;
                    }
                }
                if (claims > 0) {
                    JsonObject l = new JsonObject();
                    l.addProperty("world", w.getKey());
                    l.addProperty("claims", claims);
                    l.addProperty("x", Math.round(sx / claims));
                    l.addProperty("z", Math.round(sz / claims));
                    lands.add(l);
                }
            }
        }
        out.add("lands", lands);
        JsonArray online = new JsonArray();
        JsonObject players = playersPayload(v, null, false);
        for (JsonElement pe : players.getAsJsonArray("players")) {
            JsonObject p = pe.getAsJsonObject();
            if (realTag.equalsIgnoreCase(Json.str(p, "faction"))) {
                JsonObject o = new JsonObject();
                o.addProperty("name", Json.str(p, "name"));
                Payloads.put(o, "world", Json.str(p, "world"));
                online.add(o);
            }
        }
        out.add("onlineMembers", online);
        JsonArray sieges = new JsonArray();
        if (v.canSee(config, "territories")) {
            for (JsonElement e : view.sieges()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject s = e.getAsJsonObject();
                if (realTag.equalsIgnoreCase(Json.str(s, "attacker")) || realTag.equalsIgnoreCase(Json.str(s, "defender"))) {
                    sieges.add(Payloads.siege(s));
                }
            }
        }
        out.add("sieges", sieges);
        return out;
    }

    /**
     * Factions grouped by nation. Nations come from the top level nations list when Dominion writes
     * one, and from the nation key on each faction otherwise. Listed nations appear even when none of
     * their factions are visible to the viewer. Factions without a nation are listed under independent.
     * The nations follow the nations visibility and the factions inside them follow the factions visibility.
     */
    public JsonObject nationsPayload(Viewer v) {
        JsonObject out = new JsonObject();
        DominionSnapshot.View view = snapshot.view();
        Payloads.put(out, "at", snapshot.present() ? (Number) view.at() : null);
        JsonArray list = new JsonArray();
        JsonArray independent = new JsonArray();
        out.add("nations", list);
        out.add("independent", independent);
        if (!v.canSee(config, "nations")) {
            return out;
        }
        Map<String, JsonObject> nations = new java.util.LinkedHashMap<>();
        for (JsonObject listed : view.nations) {
            String tag = Payloads.text(listed, "tag");
            if (tag == null) {
                continue;
            }
            JsonElement members = listed.get("members");
            nations.put(tag.trim().toUpperCase(Locale.ROOT), nationRow(listed,
                    members != null && members.isJsonArray() ? members.getAsJsonArray().size() : 0));
        }
        boolean showFactions = v.canSee(config, "factions");
        boolean limited = showFactions && v.factionLimited(config, "factions");
        List<JsonObject> rows = new ArrayList<>();
        if (showFactions) {
            for (JsonElement e : view.factions()) {
                if (e.isJsonObject() && Payloads.text(e.getAsJsonObject(), "tag") != null) {
                    rows.add(e.getAsJsonObject());
                }
            }
        }
        rows.sort((a, b) -> Double.compare(Json.num(b, "value", 0), Json.num(a, "value", 0)));
        for (JsonObject f : rows) {
            if (limited && !v.sameFaction(Json.str(f, "tag"))) {
                continue;
            }
            JsonObject brief = Payloads.factionBrief(f);
            JsonObject nation = view.nationOf(f);
            if (nation == null) {
                independent.add(brief);
                continue;
            }
            String key = Json.str(nation, "tag").trim().toUpperCase(Locale.ROOT);
            JsonObject n = nations.computeIfAbsent(key, k -> nationRow(nation, 0));
            n.getAsJsonArray("factions").add(brief);
            n.addProperty("value", Json.num(n, "value", 0) + Json.num(brief, "value", 0));
            n.addProperty("claims", (long) Json.num(n, "claims", 0) + (long) Json.num(brief, "claims", 0));
            if (n.get("memberCount").getAsLong() < n.getAsJsonArray("factions").size()) {
                n.addProperty("memberCount", n.getAsJsonArray("factions").size());
            }
        }
        List<JsonObject> sorted = new ArrayList<>(nations.values());
        sorted.sort((a, b) -> Double.compare(Json.num(b, "value", 0), Json.num(a, "value", 0)));
        sorted.forEach(list::add);
        return out;
    }

    private static JsonObject nationRow(JsonObject n, int memberCount) {
        JsonObject o = new JsonObject();
        Payloads.put(o, "id", Payloads.text(n, "id"));
        String tag = Payloads.text(n, "tag");
        String name = Payloads.text(n, "name");
        o.addProperty("tag", tag == null ? "" : tag);
        o.addProperty("name", name == null ? (tag == null ? "" : tag) : name);
        Payloads.put(o, "color", Payloads.text(n, "color"));
        o.addProperty("memberCount", memberCount);
        o.addProperty("value", 0.0);
        o.addProperty("claims", 0L);
        o.add("factions", new JsonArray());
        return o;
    }

    public JsonObject statsPayload(Viewer v) {
        JsonObject out = new JsonObject();
        long now = System.currentTimeMillis();
        DominionSnapshot.View view = snapshot.view();
        Universe u = Universe.get();
        out.addProperty("at", now);
        out.addProperty("online", u == null ? 0 : u.getPlayerCount());
        out.addProperty("worlds", u == null ? 0 : u.getWorlds().size());
        out.addProperty("uptimeSeconds", (now - startedAt) / 1000);
        JsonObject snap = new JsonObject();
        snap.addProperty("present", snapshot.present());
        Payloads.put(snap, "at", snapshot.present() ? (Number) view.at() : null);
        out.add("snapshot", snap);
        if (snapshot.present()) {
            int claims = 0;
            List<JsonObject> rows = new ArrayList<>();
            for (JsonElement e : view.factions()) {
                if (e.isJsonObject()) {
                    rows.add(e.getAsJsonObject());
                    claims += (int) Json.num(e.getAsJsonObject(), "claims", 0);
                }
            }
            JsonObject fs = new JsonObject();
            fs.addProperty("count", rows.size());
            fs.addProperty("claims", claims);
            boolean showFactions = v.canSee(config, "factions") && !v.factionLimited(config, "factions");
            fs.add("topValue", showFactions ? Payloads.top(rows, "value") : JsonNull.INSTANCE);
            fs.add("topPower", showFactions ? Payloads.top(rows, "power") : JsonNull.INSTANCE);
            fs.add("topKills", showFactions ? Payloads.top(rows, "kills") : JsonNull.INSTANCE);
            out.add("factions", fs);
            out.add("grace", Payloads.grace(Json.obj(view.root, "grace")));
            out.addProperty("eotw", Json.bool(view.root, "eotw"));
            int active = 0;
            for (JsonElement e : view.sieges()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                long ends = (long) Json.num(e.getAsJsonObject(), "endsAt", 0);
                if (ends == 0 || ends > now) {
                    active++;
                }
            }
            out.addProperty("activeSieges", active);
            out.add("events", view.events() != null && v.canSee(config, "events") ? Payloads.events(view.events()) : JsonNull.INSTANCE);
        } else {
            out.add("factions", JsonNull.INSTANCE);
            out.add("grace", JsonNull.INSTANCE);
            out.add("eotw", JsonNull.INSTANCE);
            out.add("activeSieges", JsonNull.INSTANCE);
            out.add("events", JsonNull.INSTANCE);
        }
        JsonObject activity = new JsonObject();
        long[] hour = heat.totals(now - 60 * 60 * 1000L);
        long[] day = heat.totals(now - 24 * 60 * 60 * 1000L);
        activity.addProperty("breaksLastHour", hour[0]);
        activity.addProperty("placesLastHour", hour[1]);
        activity.addProperty("breaksLastDay", day[0]);
        activity.addProperty("placesLastDay", day[1]);
        out.add("activity", activity);
        return out;
    }

    /** The from time of a changes or heat query, clamped to the heat retention. */
    private static long clampSince(long since, long now) {
        return Math.max(now - HeatTracker.RETENTION_MS, Math.min(since, now));
    }

    /** Tiles redrawn since a time, as [zoom, x, z, at] rows. */
    public JsonObject changesPayload(Viewer v, String world, long since) {
        long now = System.currentTimeMillis();
        long from = clampSince(since, now);
        JsonObject out = new JsonObject();
        out.addProperty("world", world);
        out.addProperty("now", now);
        out.addProperty("since", from);
        JsonArray changed = new JsonArray();
        if (v.canSee(config, "terrain")) {
            for (TileService.Change c : tiles.changesSince(world, from)) {
                JsonArray row = new JsonArray();
                row.add(c.zoom());
                row.add(c.x());
                row.add(c.y());
                row.add(c.at());
                changed.add(row);
            }
        }
        out.add("tiles", changed);
        return out;
    }

    /** Block activity per chunk since a time, as [cx, cz, breaks, places] rows. */
    public JsonObject heatPayload(Viewer v, String world, long since) {
        long now = System.currentTimeMillis();
        long from = clampSince(since, now);
        JsonObject out = new JsonObject();
        out.addProperty("world", world);
        out.addProperty("now", now);
        out.addProperty("since", from);
        out.addProperty("bucketSeconds", HeatTracker.BUCKET_MS / 1000);
        JsonArray rows = v.canSee(config, "heat") ? heat.query(world, from) : new JsonArray();
        if (v.canSee(config, "heat") && v.factionLimited(config, "heat")) {
            // Faction viewers only see activity inside their own land.
            DominionSnapshot.View view = snapshot.view();
            JsonArray own = new JsonArray();
            for (JsonElement re : rows) {
                JsonArray r = re.getAsJsonArray();
                JsonObject t = view.territoryAt(world, r.get(0).getAsInt(), r.get(1).getAsInt());
                if (t != null && v.sameFaction(Json.str(t, "tag"))) {
                    own.add(r);
                }
            }
            rows = own;
        }
        out.add("chunks", rows);
        return out;
    }

    // ---- feed and chat -----------------------------------------------------

    /**
     * Adds a feed line and sends it to browsers. Lines from the Dominion snapshot and from Atlas are both
     * reduced to {at, type, text, source}. Lines without text are dropped.
     */
    public void addFeed(JsonObject raw) {
        JsonObject line = Payloads.feedLine(raw);
        if (line == null) {
            return;
        }
        if ("dominion".equals(Json.str(line, "source"))) {
            // Lines Atlas writes are recorded where they happen, with their place and kind.
            EventLog.record(timelineWorld(Json.str(line, "world")), (long) Json.num(line, "at", System.currentTimeMillis()),
                    "dominion", null, null, null, Json.str(line, "text"));
        }
        synchronized (feed) {
            feed.addLast(line);
            while (feed.size() > FEED_KEEP) {
                feed.removeFirst();
            }
        }
        sse.broadcastAll("feed", Json.GSON.toJson(line), c -> c.viewer().canSee(config, "feed"));
    }

    /** The feedlog event body {items: [FeedLine]}, oldest first. */
    public JsonObject recentFeed() {
        JsonArray a = new JsonArray();
        synchronized (feed) {
            feed.forEach(a::add);
        }
        return items(a);
    }

    private static JsonObject items(JsonArray a) {
        JsonObject o = new JsonObject();
        o.add("items", a);
        return o;
    }

    /**
     * Adds a chat line captured from a chat event. The tag is the sender's faction from the snapshot. A
     * hidden player's line is shown as A player, since chat is shared by every viewer.
     */
    public void addChat(UUID sender, String name, String text) {
        boolean hidden = isHiddenPlayer(sender);
        addChat(hidden ? Payloads.HIDDEN_NAME : name, hidden ? null : factionOf(sender), text, System.currentTimeMillis());
    }

    /** Adds a chat line with its own time. The tag is the sender's faction tag, null when there is none. */
    public void addChat(String name, String tag, String text, long at) {
        if (text == null) {
            return;
        }
        JsonObject line = new JsonObject();
        line.addProperty("at", at);
        line.addProperty("name", name == null ? "" : name);
        Payloads.put(line, "tag", tag == null || tag.isBlank() ? null : tag);
        line.addProperty("text", text);
        synchronized (chat) {
            chat.addLast(line);
            while (chat.size() > CHAT_KEEP) {
                chat.removeFirst();
            }
        }
        sse.broadcastAll("chat", Json.GSON.toJson(line), c -> c.viewer().canSee(config, "chat"));
    }

    /** The chatlog event body {items: [ChatLine]}, oldest first. */
    public JsonObject recentChat() {
        JsonArray a = new JsonArray();
        synchronized (chat) {
            chat.forEach(a::add);
        }
        return items(a);
    }

    /** Seeds the feed buffer from a freshly loaded snapshot without broadcasting. */
    public void seedFeed() {
        synchronized (feed) {
            if (!feed.isEmpty()) {
                return;
            }
            JsonArray lines = snapshot.view().feed();
            int start = Math.max(0, lines.size() - FEED_KEEP);
            for (int i = start; i < lines.size(); i++) {
                if (lines.get(i).isJsonObject()) {
                    JsonObject line = Payloads.feedLine(lines.get(i).getAsJsonObject());
                    if (line != null) {
                        feed.addLast(line);
                    }
                }
            }
        }
    }

    // ---- Chapter I data ----------------------------------------------------

    /**
     * Markers of a world. Spawn, engine and poi markers from the providers, player made markers from
     * the world store as kind shared, and for a viewer with a player of their own their personal
     * markers, homes and deaths. Waits up to two seconds for the first collection on the world thread.
     */
    public JsonObject markersPayload(Viewer v, String worldName) {
        JsonObject out = new JsonObject();
        out.addProperty("world", worldName);
        out.add("at", JsonNull.INSTANCE);
        JsonArray list = new JsonArray();
        out.add("markers", list);
        if (!v.canSee(config, "markers")) {
            return out;
        }
        Universe u = Universe.get();
        World w = u == null ? null : u.getWorld(worldName);
        if (w == null) {
            return out;
        }
        // The personal read is queued first so it runs on the world thread next to the marker read.
        UUID owner = personalOwner(v);
        java.util.concurrent.CompletableFuture<PersonalMarkers.PersonalSet> own = owner == null ? null : personal.refresh(w, owner);
        MapModSettings.Settings rules = mapRules();
        String world = w.getName();
        List<MarkerMerge.Candidate> all = new ArrayList<>();
        LiveState.WorldInfo info = live.worlds().get(world);
        if (info != null && info.spawn() != null) {
            JsonObject s = new JsonObject();
            s.addProperty("id", "spawn");
            s.addProperty("kind", "spawn");
            s.addProperty("name", "World spawn");
            s.addProperty("x", Math.round(info.spawn()[0]));
            s.addProperty("y", Math.round(info.spawn()[1]));
            s.addProperty("z", Math.round(info.spawn()[2]));
            s.add("icon", JsonNull.INSTANCE);
            s.addProperty("major", true);
            s.add("alert", JsonNull.INSTANCE);
            all.add(new MarkerMerge.Candidate(withOwner(s, null, false), MarkerMerge.RANK_SPAWN, "spawn", List.of()));
        }
        EngineData.MarkerSet set;
        try {
            set = engine.markers(w).get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            set = null;
        }
        // The newest shared read wins over the one cached with the provider markers.
        EngineData.SharedSet latest = engine.shared(world);
        List<EngineData.SharedMarker> shared = latest != null ? latest.markers() : set != null ? set.shared() : List.of();
        Map<String, Double> sharedY = rules == null ? Map.of() : mapMod.sharedHeights(world);
        for (EngineData.SharedMarker m : shared) {
            all.add(new MarkerMerge.Candidate(sharedJson(v, m, sharedY.get(m.id())), MarkerMerge.RANK_SHARED, "shared",
                    m.byUuid() == null ? List.of() : List.of(m.byUuid().toString())));
        }
        if (own != null) {
            PersonalMarkers.PersonalSet mine;
            try {
                mine = own.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                mine = null;
            }
            if (mine == null) {
                // Offline or slow. The last read of the past hour stands in.
                mine = personal.get(owner, world);
            }
            if (mine != null) {
                Map<String, Double> personalY = rules == null ? Map.of() : mapMod.personalHeights(world, owner);
                for (PersonalMarkers.Entry e : mine.entries()) {
                    all.add(new MarkerMerge.Candidate(personalJson(v, e, personalY.get(e.id())), MarkerMerge.RANK_OWN,
                            e.kind(), List.of()));
                }
            }
        }
        if (set != null) {
            for (EngineData.Found f : set.markers()) {
                all.add(new MarkerMerge.Candidate(withOwner(f.json().deepCopy(), null, false),
                        f.direct() ? MarkerMerge.RANK_STORE : MarkerMerge.RANK_PROVIDER, f.source(), f.nameKeys()));
            }
            out.addProperty("at", set.at());
        }
        // Filters first, so a hidden entry can never stand in for a visible one in the merge.
        List<MarkerMerge.Candidate> visible = new ArrayList<>(all.size());
        for (MarkerMerge.Candidate c : all) {
            if (rules == null || visibleUnder(rules, world, c)) {
                visible.add(c);
            }
        }
        for (MarkerMerge.Candidate c : MarkerMerge.dedupe(visible, owner)) {
            list.add(markerJson(c));
        }
        return out;
    }

    /** One marker with the fixed API field list. kind is the drawn type and source where it came from. */
    private static JsonObject markerJson(MarkerMerge.Candidate c) {
        JsonObject m = c.json();
        JsonObject o = new JsonObject();
        String id = Payloads.text(m, "id");
        o.addProperty("id", id == null ? "" : id);
        String kind = Payloads.text(m, "kind");
        o.addProperty("kind", kind == null ? "engine" : kind);
        o.addProperty("source", c.source() == null ? "other" : c.source());
        String name = Payloads.text(m, "name");
        o.addProperty("name", name == null ? "" : name);
        o.addProperty("x", Payloads.whole(m, "x", 0));
        Payloads.put(o, "y", Payloads.whole(m, "y"));
        o.addProperty("z", Payloads.whole(m, "z", 0));
        Payloads.put(o, "icon", Payloads.text(m, "icon"));
        o.addProperty("major", Payloads.flag(m, "major"));
        JsonObject alert = Json.obj(m, "alert");
        if (alert == null) {
            o.add("alert", JsonNull.INSTANCE);
        } else {
            JsonObject a = new JsonObject();
            Payloads.put(a, "icon", Payloads.text(alert, "icon"));
            a.addProperty("durationSeconds", Payloads.number(alert, "durationSeconds", 0));
            a.addProperty("timeoutSeconds", Payloads.number(alert, "timeoutSeconds", 0));
            Payloads.put(a, "tint", Payloads.text(alert, "tint"));
            o.add("alert", a);
        }
        Payloads.put(o, "tint", Payloads.text(m, "tint"));
        JsonObject by = Json.obj(m, "by");
        if (by == null) {
            o.add("by", JsonNull.INSTANCE);
        } else {
            JsonObject b = new JsonObject();
            Payloads.put(b, "uuid", Payloads.text(by, "uuid"));
            Payloads.put(b, "name", Payloads.text(by, "name"));
            o.add("by", b);
        }
        o.addProperty("own", Payloads.flag(m, "own"));
        return o;
    }

    /**
     * True when an entry stays on the map under the server wide BetterMap hide rules. Spawn, points
     * of interest, block markers, warps, shared markers and deaths can each be hidden. Shared markers
     * are hidden for every viewer, their creator included, as the in game map does. Unexplored
     * points, block markers and warps are hidden only on the worlds BetterMap tracks, judged by the
     * chunks Atlas knows to exist.
     */
    private boolean visibleUnder(MapModSettings.Settings rules, String world, MarkerMerge.Candidate c) {
        String kind = Json.str(c.json(), "kind");
        switch (c.source()) {
            case "spawn":
                return !rules.spawnHidden();
            case "shared":
                return !rules.hideSharedMarkers();
            case "death":
                return !rules.hideDeaths();
            case EngineData.SOURCE_POI:
                if (rules.hideAllPoi() || rules.hiddenByName(c.nameKeys())) {
                    return false;
                }
                return !rules.hideUnexploredPoi() || !rules.tracks(world) || explored(world, c.json());
            case EngineData.SOURCE_BLOCK:
                List<String> k = c.nameKeys();
                if (rules.hideAllPoi() || (k.size() == 3 && rules.hiddenBlock(k.get(0), k.get(1), k.get(2)))) {
                    return false;
                }
                return !rules.hideUnexploredPoi() || !rules.tracks(world) || explored(world, c.json());
            case EngineData.SOURCE_WARP:
                if (rules.hideAllWarps()) {
                    return false;
                }
                return !rules.hideUnexploredWarps() || !rules.tracks(world) || explored(world, c.json());
            default:
                return !"death".equals(kind) || !rules.hideDeaths();
        }
    }

    /** True when the chunk under a marker is part of the explored area Atlas knows. */
    private boolean explored(String world, JsonObject m) {
        int x = (int) Math.round(Json.num(m, "x", 0));
        int z = (int) Math.round(Json.num(m, "z", 0));
        return tiles.explored(world).hasChunk(Math.floorDiv(x, 32), Math.floorDiv(z, 32));
    }

    /** True when the BetterMap rules hide a point of interest listed in the zones layer. */
    private boolean hiddenPoint(MapModSettings.Settings rules, String world, MapMarker m) {
        List<String> keys = new ArrayList<>(3);
        if (m.name != null && m.name.rawText != null) {
            keys.add(m.name.rawText);
        }
        if (m.id != null) {
            keys.add(m.id);
        }
        if (m.markerImage != null) {
            keys.add(m.markerImage);
        }
        if (rules.hiddenByName(keys)) {
            return true;
        }
        if (rules.hideUnexploredPoi() && rules.tracks(world)) {
            int x = (int) Math.round(m.transform.position.x);
            int z = (int) Math.round(m.transform.position.z);
            return !tiles.explored(world).hasChunk(Math.floorDiv(x, 32), Math.floorDiv(z, 32));
        }
        return false;
    }

    /**
     * The player whose personal markers this viewer receives, or null. Only a viewer with a player of
     * their own, which is a linked browser or an admin linked in game, gets any. Public viewers, the
     * admin token and admins viewing as a faction have no player and get none.
     */
    public static UUID personalOwner(Viewer v) {
        return v == null ? null : v.uuid();
    }

    private static JsonObject withOwner(JsonObject o, JsonObject by, boolean own) {
        if (!o.has("tint")) {
            o.add("tint", JsonNull.INSTANCE);
        }
        o.add("by", by == null ? JsonNull.INSTANCE : by);
        o.addProperty("own", own);
        return o;
    }

    /** The creator of a player made marker as this viewer may see it, or null. */
    private JsonObject creatorJson(Viewer v, UUID uuid, String name) {
        if (!config.showMarkerAuthors || (uuid == null && name == null)) {
            return null;
        }
        JsonObject by = new JsonObject();
        if (hiddenFrom(v, uuid)) {
            by.add("uuid", JsonNull.INSTANCE);
            by.addProperty("name", "A player");
        } else {
            by.addProperty("uuid", uuid == null ? null : uuid.toString());
            by.addProperty("name", name);
        }
        return by;
    }

    private JsonObject sharedJson(Viewer v, EngineData.SharedMarker m, Double y) {
        JsonObject o = new JsonObject();
        o.addProperty("id", m.id());
        o.addProperty("kind", "shared");
        o.addProperty("name", m.name());
        o.addProperty("x", m.x());
        if (y == null) {
            o.add("y", JsonNull.INSTANCE);
        } else {
            o.addProperty("y", Math.round(y));
        }
        o.addProperty("z", m.z());
        o.addProperty("icon", m.icon());
        o.addProperty("major", false);
        o.add("alert", JsonNull.INSTANCE);
        o.addProperty("tint", m.tint());
        return withOwner(o, creatorJson(v, m.byUuid(), m.byName()), false);
    }

    private JsonObject personalJson(Viewer v, PersonalMarkers.Entry e, Double heightFile) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id());
        o.addProperty("kind", e.kind());
        o.addProperty("name", e.name());
        o.addProperty("x", e.x());
        if (e.y() == null && heightFile != null && "personal".equals(e.kind())) {
            o.addProperty("y", Math.round(heightFile));
        } else {
            o.addProperty("y", e.y());
        }
        o.addProperty("z", e.z());
        o.addProperty("icon", e.icon());
        o.addProperty("major", false);
        o.add("alert", JsonNull.INSTANCE);
        o.addProperty("tint", e.tint());
        return withOwner(o, creatorJson(v, e.byUuid(), e.byName()), true);
    }

    /**
     * Reads the markers of every world and the personal markers of players with an open stream on the
     * world threads, and sends markers events for the sets that changed. The comparison and the sending
     * run on the given executor, which is the Atlas scheduler. Worlds that went away since the previous
     * poll are forgotten here as well.
     */
    public void pollMarkers(Executor executor) {
        if (background == null) {
            background = executor;
        }
        forgetRemovedWorlds();
        markers.poll();
        // The first read of a world raises no change, so its shared markers are remembered here instead.
        for (String world : seenWorlds.keySet()) {
            if (!sharedSeen.containsKey(world) && engine.shared(world) != null) {
                recordSharedChanges(world);
            }
        }
        engine.pollShared(executor, world -> {
            recordSharedChanges(world);
            JsonObject o = new JsonObject();
            o.addProperty("world", world);
            o.addProperty("own", false);
            sse.broadcastAll("markers", Json.GSON.toJson(o), c -> c.viewer().canSee(config, "markers"));
        });
        personal.poll(sse.viewerUuids(), executor, (uuid, world) -> {
            JsonObject o = new JsonObject();
            o.addProperty("world", world);
            o.addProperty("own", true);
            sse.broadcastAll("markers", Json.GSON.toJson(o),
                    c -> uuid.equals(personalOwner(c.viewer())) && c.viewer().canSee(config, "markers"));
        });
    }

    /** Shared markers seen per world at the last change, so the timeline can name what was added or removed. */
    private final Map<String, Map<String, EngineData.SharedMarker>> sharedSeen = new ConcurrentHashMap<>();

    /** Records shared markers added or removed since the last change of a world. The first look only remembers them. */
    private void recordSharedChanges(String world) {
        EngineData.SharedSet set = engine.shared(world);
        if (set == null) {
            return;
        }
        Map<String, EngineData.SharedMarker> now = new java.util.LinkedHashMap<>();
        for (EngineData.SharedMarker m : set.markers()) {
            now.put(m.id(), m);
        }
        Map<String, EngineData.SharedMarker> before = sharedSeen.put(world, now);
        if (before == null) {
            return;
        }
        long at = System.currentTimeMillis();
        for (EngineData.SharedMarker m : now.values()) {
            if (!before.containsKey(m.id())) {
                String who = config.showMarkerAuthors ? m.byName() : null;
                EventLog.record(world, at, "marker.add", (double) m.x(), (double) m.z(), who,
                        who == null ? null : m.byUuid(), (who == null ? "A marker was placed" : who + " placed a marker")
                                + (m.name() == null || m.name().isBlank() ? "." : ", " + m.name() + "."));
            }
        }
        for (EngineData.SharedMarker m : before.values()) {
            if (!now.containsKey(m.id())) {
                EventLog.record(world, at, "marker.remove", (double) m.x(), (double) m.z(), null, null,
                        "A marker was removed" + (m.name() == null || m.name().isBlank() ? "." : ", " + m.name() + "."));
            }
        }
    }

    /** Compares the loaded worlds with the previous poll and drops the caches of worlds that are gone. */
    private void forgetRemovedWorlds() {
        Universe u = Universe.get();
        if (u == null) {
            return;
        }
        java.util.Set<String> now = new java.util.HashSet<>(u.getWorlds().keySet());
        for (String gone : new ArrayList<>(seenWorlds.keySet())) {
            if (!now.contains(gone)) {
                worldRemoved(gone, Boolean.TRUE.equals(seenWorlds.get(gone)));
            }
        }
        for (String name : now) {
            LiveState.WorldInfo info = live.worlds().get(name);
            boolean transientWorld = info != null && info.kind() != null && info.kind().transientWorld();
            seenWorlds.merge(name, transientWorld, (a, b) -> a || b);
        }
    }

    /**
     * Forgets a removed world. Marker caches and jobs, personal sets and the world's in flight reads always
     * go. Heat goes only for portal and instance worlds, whose map data is dropped with them.
     */
    public void worldRemoved(String name, boolean transientWorld) {
        seenWorlds.remove(name);
        sharedSeen.remove(name);
        engine.forgetMarkers(name);
        personal.dropWorld(name);
        live.forget(name);
        if (transientWorld) {
            heat.dropWorld(name);
            markers.forgetWorld(name);
        }
    }

    private JsonObject worldEventJson(EngineData.ActiveEvent e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id().toString());
        o.addProperty("world", e.world());
        o.addProperty("name", e.name());
        o.addProperty("displayName", e.displayName());
        o.addProperty("startedAt", e.startedAt());
        Payloads.put(o, "x", e.x());
        Payloads.put(o, "z", e.z());
        o.addProperty("located", located(e));
        return o;
    }

    /** Events with a place in the world. Bootstrap and prelude events have none and are listed only. */
    private static boolean located(EngineData.ActiveEvent e) {
        return e.x() != null && e.z() != null;
    }

    public JsonObject worldEventsPayload(Viewer v, String world) {
        JsonObject out = new JsonObject();
        JsonArray list = new JsonArray();
        out.add("events", list);
        if (!v.canSee(config, "worldevents")) {
            return out;
        }
        for (EngineData.ActiveEvent e : engine.activeEvents(world)) {
            if (world == null && hiddenWorld(v, e.world())) {
                continue;
            }
            list.add(worldEventJson(e));
        }
        return out;
    }

    /**
     * Announces a world event start or end over the event stream and in the Atlas feed. Events without a
     * location are not announced.
     */
    public void worldEventChanged(EngineData.ActiveEvent e, String state, long at) {
        if (!located(e)) {
            // Events without a location are background state such as bootstrap and prelude events.
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("world", e.world());
        o.addProperty("id", e.id().toString());
        o.addProperty("name", e.name());
        o.addProperty("displayName", e.displayName());
        o.addProperty("state", state);
        Payloads.put(o, "x", e.x());
        Payloads.put(o, "z", e.z());
        o.addProperty("at", at);
        sse.broadcastAll("worldevent", Json.GSON.toJson(o), c -> c.viewer().canSee(config, "worldevents")
                && !hiddenWorld(c.viewer(), e.world()));
        String where = worldLabel(e.world());
        EventLog.record(e.world(), at, "start".equals(state) ? "worldevent.start" : "worldevent.end",
                e.x() == null ? null : e.x().doubleValue(), e.z() == null ? null : e.z().doubleValue(), null,
                "start".equals(state) ? e.displayName() + " began." : e.displayName() + " ended.");
        feedLine("worldevent", "start".equals(state)
                ? e.displayName() + " began in " + where + "."
                : e.displayName() + " ended in " + where + ".", at, e.world());
    }

    /** The display name of a world for feed lines. */
    private String worldLabel(String world) {
        LiveState.WorldInfo info = world == null ? null : live.worlds().get(world);
        if (info != null && info.kind() != null && info.kind().displayName() != null) {
            return info.kind().displayName();
        }
        return world == null ? "the world" : world;
    }

    /** Adds a feed line written by Atlas. world is the world the line is about, so the world gate applies. */
    private void feedLine(String type, String text, long at, String world) {
        JsonObject line = new JsonObject();
        line.addProperty("at", at);
        line.addProperty("type", type);
        line.addProperty("text", text);
        line.addProperty("source", "atlas");
        line.addProperty("world", world);
        addFeed(line);
    }

    private JsonObject partyJson(Viewer v, KillLog.Party p) {
        JsonObject o = new JsonObject();
        boolean hidden = p.player() && hiddenFrom(v, p.uuid());
        Payloads.person(o, "uuid", "name", p.player() ? p.uuid() : null, p.name() == null ? "" : p.name(), hidden);
        o.addProperty("type", p.type() == null ? (p.player() ? "Player" : "") : p.type());
        o.addProperty("player", p.player());
        o.addProperty("boss", !p.player() && p.boss());
        return o;
    }

    /** One kill as this viewer may see it, or null when it is limited to other factions. */
    private JsonObject killJson(Viewer v, KillLog.Kill k) {
        if (v.factionLimited(config, "kills")) {
            String fv = k.victim().uuid() == null ? null : factionOf(k.victim().uuid());
            String fk = k.killer() == null || k.killer().uuid() == null ? null : factionOf(k.killer().uuid());
            if (!v.sameFaction(fv) && !v.sameFaction(fk)) {
                return null;
            }
        }
        // The place follows the player the death is about, as on the timeline. The victim when that is a
        // player, else the killer. It is withheld when either party is hidden from the viewer, since a
        // killer stands close to the place, and in a world the viewer may not open.
        KillLog.Party about = k.victim().player() ? k.victim() : k.killer();
        UUID aboutId = about == null || !about.player() ? null : about.uuid();
        UUID killerId = k.killer() == null || !k.killer().player() ? null : k.killer().uuid();
        boolean withheld = hiddenFrom(v, aboutId) || hiddenFrom(v, killerId) || hiddenWorld(v, k.world());
        Integer[] at = dev.ryvrook.atlas.timeline.TimelineRoutes.place("death", k.x(), k.z(), v.admin(),
                aboutId != null && aboutId.equals(v.uuid()), withheld);
        return Payloads.kill(k.at(), k.world(), partyJson(v, k.victim()),
                k.killer() == null ? JsonNull.INSTANCE : partyJson(v, k.killer()), k.cause(), k.ranged(), at[0], at[1]);
    }

    private JsonArray killRows(Viewer v, int limit) {
        JsonArray list = new JsonArray();
        if (!v.canSee(config, "kills")) {
            return list;
        }
        for (KillLog.Kill k : kills.recent(KillLog.KEEP)) {
            JsonObject o = killJson(v, k);
            if (o != null) {
                list.add(o);
            }
        }
        while (list.size() > Math.max(0, limit)) {
            list.remove(0);
        }
        return list;
    }

    /** The killlog event body {items: [Kill]}, newest last. */
    public JsonObject killLog(Viewer v, int limit) {
        return items(killRows(v, limit));
    }

    public JsonObject killsPayload(Viewer v, int limit) {
        JsonObject out = new JsonObject();
        out.add("kills", killRows(v, Math.min(KillLog.KEEP, Math.max(1, limit))));
        return out;
    }

    /**
     * Records a death from the death system. The log entry is made at once, and sending it to browsers and
     * writing the feed line run on the Atlas scheduler so the world thread is not held up.
     */
    public void addKill(KillLog.Kill k) {
        kills.add(k);
        runInBackground(() -> announceKill(k));
    }

    private void announceKill(KillLog.Kill k) {
        sse.broadcast("kill", c -> c.viewer().canSee(config, "kills"), c -> {
            JsonObject o = killJson(c.viewer(), k);
            return o == null ? null : Json.GSON.toJson(o);
        });
        String where = worldLabel(k.world());
        KillLog.Party victim = k.victim();
        KillLog.Party killer = k.killer();
        if (!victim.player() && victim.boss() && killer != null) {
            feedLine("boss", feedName(killer) + " defeated " + victim.name() + " in " + where + ".", k.at(), k.world());
            EventLog.record(timelineWorld(k.world()), k.at(), "kill", coord(k.x()), coord(k.z()), killer.name(),
                    killer.player() ? killer.uuid() : null, killer.name() + " defeated " + victim.name() + ".");
        } else if (victim.player()) {
            feedLine("death", deathText(victim, killer, k.cause(), where, true), k.at(), k.world());
            boolean pvp = killer != null && killer.player();
            EventLog.record(timelineWorld(k.world()), k.at(), pvp ? "kill" : "death", coord(k.x()), coord(k.z()), victim.name(),
                    victim.uuid(), deathText(victim, killer, k.cause(), null, false));
        }
    }

    /** The sentence for a player death. shared means feed text, which names hidden players as A player. */
    private String deathText(KillLog.Party victim, KillLog.Party killer, String cause, String where, boolean shared) {
        String v = shared ? feedName(victim) : victim.name();
        String tail = where == null ? "." : " in " + where + ".";
        if (killer != null && killer.player()) {
            return v + " was slain by " + (shared ? feedName(killer) : killer.name()) + tail;
        } else if (killer != null) {
            return v + " was slain by " + (killer.boss() ? "" : "a ") + killer.name() + tail;
        } else if (cause != null) {
            return v + " died from " + cause.replace('_', ' ').toLowerCase(Locale.ROOT) + tail;
        }
        return v + " died" + tail;
    }

    /** The world a timeline event without one is filed under, the configured default world or the engine's. */
    private String timelineWorld(String world) {
        if (world != null && !world.isBlank()) {
            return world;
        }
        if (!config.defaultWorld.isEmpty()) {
            return config.defaultWorld;
        }
        try {
            Universe u = Universe.get();
            World d = u == null ? null : u.getDefaultWorld();
            if (d != null) {
                return d.getName();
            }
        } catch (RuntimeException ignored) {
            // Universe not ready.
        }
        return "default";
    }

    /** Records a new Feats unlock, milestone or first in the world the player is in. */
    private void featEvent(Leaderboard.FeatEvent e) {
        LiveState.PlayerSample p = e.uuid() == null ? null : live.sampleOf(e.uuid());
        String who = e.name();
        String tier = e.tier() == null || e.tier().isBlank() ? "" : " " + e.tier().toLowerCase(Locale.ROOT);
        // Event text is the same for every viewer, so a hidden feat is never named in it.
        boolean secret = e.hiddenFeat();
        String text = switch (e.kind()) {
            case "feats.milestone" -> (who == null ? "A milestone was reached, " : who + " reached the milestone ")
                    + (secret ? "of a hidden feat" : e.featName()) + ".";
            case "feats.first" -> (who == null ? "Someone" : who) + " was the first to earn "
                    + (secret ? "a hidden feat" : e.featName()) + ".";
            default -> (who == null ? "A player" : who) + (secret ? " earned a hidden" + tier + " feat."
                    : " earned the" + tier + " feat " + e.featName() + ".");
        };
        Double[] at = p == null ? new Double[]{null, null} : eventPlace(p.uuid(), p.world(), p.x(), p.y(), p.z());
        EventLog.record(timelineWorld(p == null ? null : p.world()), e.at(), e.kind(), at[0], at[1], who, e.uuid(), text);
    }

    /** A block coordinate as the event log takes it. */
    private static Double coord(Integer v) {
        return v == null ? null : v.doubleValue();
    }

    /** Feed text is shared by every viewer, so hidden players are always A player there. */
    private String feedName(KillLog.Party p) {
        if (p.player() && isHiddenPlayer(p.uuid())) {
            return Payloads.HIDDEN_NAME;
        }
        return p.name();
    }

    /** The leaderboard from the Feats export, with hidden players renamed for this viewer. */
    public JsonObject leaderboardPayload(Viewer v) {
        if (!v.canSee(config, "leaderboard")) {
            return Payloads.emptyLeaderboard();
        }
        return Payloads.leaderboard(leaderboard.root(), leaderboard.updatedAt(), uuid -> hiddenFrom(v, uuid),
                v.viewingAs() ? null : v.uuid(), v.admin());
    }

    /** The Chronicle stats from its export, with hidden players renamed for this viewer. */
    public JsonObject chroniclePayload(Viewer v) {
        String url = config.chronicleUrl == null || config.chronicleUrl.isBlank() ? null : config.chronicleUrl.trim();
        if (!v.canSee(config, "stats")) {
            return ChronicleExport.empty(url);
        }
        return ChronicleExport.payload(chronicle.root(), chronicle.updatedAt(), url, uuid -> hiddenFrom(v, uuid));
    }

    /** Records where a player was when they left. Called from the disconnect event. */
    public void recordLastSeen(UUID uuid, String name, String world, double x, double y, double z) {
        if (uuid == null || world == null) {
            return;
        }
        lastSeen.record(new LastSeen.Entry(uuid, name, System.currentTimeMillis(), world, x, y, z));
        Double[] at = eventPlace(uuid, world, x, y, z);
        EventLog.record(world, System.currentTimeMillis(), "leave", at[0], at[1], name, uuid,
                (name == null ? "A player" : name) + " left the server.");
    }

    // ---- routing -------------------------------------------------------------

    /** The status and body of one API answer. */
    public record ApiResult(int status, JsonObject body) {
        public static ApiResult ok(JsonObject body) {
            return new ApiResult(200, body);
        }

        public static ApiResult error(int status, String message) {
            return new ApiResult(status, Payloads.error(message));
        }
    }

    private static final ApiResult NOT_FOUND = ApiResult.error(404, "Not found.");

    /** The layer each data endpoint needs, checked before the body is built. */
    private boolean layerVisible(Viewer v, String head) {
        return switch (head) {
            case "players" -> seesPlayers(v);
            case "markers" -> v.canSee(config, "markers");
            case "worldevents" -> v.canSee(config, "worldevents");
            case "kills" -> v.canSee(config, "kills");
            case "leaderboard" -> v.canSee(config, "leaderboard");
            case "territories" -> v.canSee(config, "territories");
            case "points" -> v.canSee(config, "points") || v.canSee(config, "events") || v.canSee(config, "zones");
            case "factions" -> v.canSee(config, "factions");
            case "nations" -> v.canSee(config, "nations");
            case "stats", "chronicle" -> v.canSee(config, "stats");
            case "changes" -> v.canSee(config, "terrain");
            case "heat" -> v.canSee(config, "heat");
            default -> true;
        };
    }

    /**
     * True when a world exists for the API. A world the engine has loaded exists, and so does a world the
     * Dominion snapshot lists, for territories and points. Without a running universe, as in tests, every
     * name counts.
     */
    private boolean worldKnown(String world, boolean snapshotWorlds) {
        Universe u = Universe.get();
        if (u == null) {
            return true;
        }
        if (u.getWorld(world) != null) {
            return true;
        }
        return snapshotWorlds && snapshot.view().worlds.containsKey(world);
    }

    /**
     * Answers the data endpoints of API version 1. parts are the decoded path parts after /api/. Returns
     * null for endpoints this does not answer (health, tiles, events, link and marker icons), which the
     * web server handles itself. A hidden layer answers 403, an unknown world or faction 404, and every
     * body follows api-v1.
     */
    public ApiResult route(Viewer v, List<String> parts, Map<String, String> query) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        String head = parts.get(0);
        java.util.Set<String> mine = java.util.Set.of("worlds", "players", "markers", "worldevents", "kills",
                "leaderboard", "territories", "points", "factions", "nations", "stats", "changes", "heat", "bosses",
                "chronicle", "money");
        if (!mine.contains(head)) {
            return null;
        }
        if ("bosses".equals(head)) {
            return NOT_FOUND;
        }
        if ("worlds".equals(head)) {
            return parts.size() == 1 ? ApiResult.ok(worldsPayload(v)) : NOT_FOUND;
        }
        if ("money".equals(head)) {
            return parts.size() == 1 ? ApiResult.ok(moneyPayload(v)) : NOT_FOUND;
        }
        boolean worldInPath = java.util.Set.of("markers", "territories", "points", "changes", "heat").contains(head);
        String world = worldInPath ? (parts.size() >= 2 ? parts.get(1) : null) : query.get("world");
        if (worldInPath && (world == null || world.isBlank())) {
            return ApiResult.error(400, "Name a world.");
        }
        if (world != null && (hiddenWorld(v, world) || !worldKnown(world, "territories".equals(head) || "points".equals(head)))) {
            return ApiResult.error(404, "No world with that name.");
        }
        if (!layerVisible(v, head)) {
            return ApiResult.error(403, HIDDEN_LAYER);
        }
        long since = parseLong(query.get("since"), System.currentTimeMillis() - 60 * 60 * 1000L);
        return switch (head) {
            case "players" -> ApiResult.ok(playersApiPayload(v, world,
                    "1".equals(query.get("trails")) || "true".equals(query.get("trails"))));
            case "markers" -> ApiResult.ok(markersPayload(v, world));
            case "worldevents" -> ApiResult.ok(worldEventsPayload(v, world));
            case "kills" -> ApiResult.ok(killsPayload(v, (int) Math.min(KillLog.KEEP, Math.max(1, parseLong(query.get("limit"), 50)))));
            case "leaderboard" -> ApiResult.ok(leaderboardPayload(v));
            case "territories" -> ApiResult.ok(territoriesPayload(v, world));
            case "points" -> ApiResult.ok(pointsPayload(v, world));
            case "factions" -> {
                if (parts.size() < 2) {
                    yield ApiResult.ok(factionsPayload(v));
                }
                JsonObject f = factionPayload(v, parts.get(1));
                yield f == null ? ApiResult.error(404, "No faction with that tag is visible.") : ApiResult.ok(f);
            }
            case "nations" -> ApiResult.ok(nationsPayload(v));
            case "stats" -> ApiResult.ok(statsPayload(v));
            case "chronicle" -> parts.size() == 1 ? ApiResult.ok(chroniclePayload(v)) : NOT_FOUND;
            case "changes" -> ApiResult.ok(changesPayload(v, world, since));
            case "heat" -> ApiResult.ok(heatPayload(v, world, since));
            default -> NOT_FOUND;
        };
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
