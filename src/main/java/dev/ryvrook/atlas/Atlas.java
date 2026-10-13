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
import dev.ryvrook.atlas.data.DominionSnapshot;
import dev.ryvrook.atlas.data.EngineData;
import dev.ryvrook.atlas.data.HeatTracker;
import dev.ryvrook.atlas.data.KillLog;
import dev.ryvrook.atlas.data.LastSeen;
import dev.ryvrook.atlas.data.Leaderboard;
import dev.ryvrook.atlas.data.LiveState;
import dev.ryvrook.atlas.data.MapModSettings;
import dev.ryvrook.atlas.data.MarkerMerge;
import dev.ryvrook.atlas.data.PersonalMarkers;
import dev.ryvrook.atlas.http.AtlasVersion;
import dev.ryvrook.atlas.http.SseHub;
import dev.ryvrook.atlas.http.TrustedProxies;
import dev.ryvrook.atlas.tiles.ExploredIndex;
import dev.ryvrook.atlas.tiles.TileService;
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

/**
 * Shared state behind the web server and the commands. Every payload the browser sees is built
 * here, so visibility rules live in one place.
 */
public final class Atlas {
    public static final String ADMIN_PERMISSION = "atlas.admin";
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
    private final EngineData engine = new EngineData();
    private final PersonalMarkers personal = new PersonalMarkers();
    private final MapModSettings mapMod;
    /** Section hashes of the last snapshot announcement, so only changed sections are announced. */
    private final Map<String, Long> announced = new ConcurrentHashMap<>();
    /** Last hidden flag Dominion wrote for each player, kept after they leave the snapshot. */
    private final Map<UUID, Boolean> snapshotHidden = new ConcurrentHashMap<>();

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
        this.mapMod = new MapModSettings(resolveMapMod(config));
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
        mapMod.setDir(resolveMapMod(cfg));
        tiles.setCacheMb(cfg.tileCacheMb);
        proxies = TrustedProxies.parse(cfg.trustedProxies);
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

    public EngineData engine() {
        return engine;
    }

    public PersonalMarkers personal() {
        return personal;
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
        o.addProperty("title", config.publicTitle);
        o.addProperty("publicUrl", config.hasPublicUrl() ? config.publicUrl.trim() : null);
        long now = System.currentTimeMillis();
        o.addProperty("uptimeMs", now - startedAt);
        o.addProperty("version", AtlasVersion.VERSION);
        o.addProperty("now", now);
        o.addProperty("betterMap", mapRules() != null);
        return o;
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

    public JsonObject viewerJson(Viewer v) {
        JsonObject o = new JsonObject();
        o.addProperty("linked", v.linked());
        o.addProperty("name", v.name());
        o.addProperty("faction", v.faction());
        o.addProperty("admin", v.admin());
        o.addProperty("viewingAs", v.viewingAs());
        JsonObject layers = new JsonObject();
        for (String layer : config.visibility.keySet()) {
            layers.addProperty(layer, v.canSee(config, layer) && (!"players".equals(layer) || config.showPlayers || v.admin()));
        }
        o.add("layers", layers);
        return o;
    }

    // ---- players -----------------------------------------------------------

    /** Players visible to the viewer, optionally limited to one world. */
    public JsonObject playersPayload(Viewer v, String worldFilter, boolean withTrails) {
        AtlasConfig cfg = config;
        JsonObject out = new JsonObject();
        long now = System.currentTimeMillis();
        out.addProperty("at", now);
        JsonArray list = new JsonArray();
        out.add("players", list);
        if (!v.canSee(cfg, "players") || (!cfg.showPlayers && !v.admin())) {
            out.addProperty("hidden", true);
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
                    p.health(), p.maxHealth(), factionLimited, true);
            if (row == null) {
                continue;
            }
            row.addProperty("source", "live");
            if (trails) {
                JsonArray t = new JsonArray();
                for (double[] pt : live.trail(p.uuid())) {
                    JsonArray xy = new JsonArray();
                    xy.add(pt[0]);
                    xy.add(pt[1]);
                    t.add(xy);
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
                        located ? sx : 0, Double.isNaN(sy) ? 0 : sy, located ? sz : 0, s, null, null, factionLimited, located);
                if (row != null) {
                    row.addProperty("source", "snapshot");
                    list.add(row);
                }
            }
        }
        return out;
    }

    /**
     * The api/players payload. Adds the offline list of players seen in the last seven days when the
     * viewer may see the lastSeen layer.
     */
    public JsonObject playersApiPayload(Viewer v, String worldFilter, boolean withTrails) {
        AtlasConfig cfg = config;
        JsonObject out = playersPayload(v, worldFilter, withTrails);
        // The offline list shows names and positions, so it needs everything the live list needs as well
        // as the lastSeen layer.
        if (!v.canSee(cfg, "players") || (!cfg.showPlayers && !v.admin()) || !v.canSee(cfg, "lastSeen")) {
            return out;
        }
        out.add("offline", offlinePlayers(v, worldFilter));
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
                if (cfg.hidePlayersInOwnTerritory && faction != null && !v.sameFaction(faction)) {
                    JsonObject t = view.territoryAt(e.world(), Math.floorDiv((int) Math.floor(e.x()), 32),
                            Math.floorDiv((int) Math.floor(e.z()), 32));
                    if (t != null && faction.equalsIgnoreCase(Json.str(t, "tag"))) {
                        continue;
                    }
                }
            }
            JsonObject row = new JsonObject();
            row.addProperty("name", e.name());
            row.addProperty("faction", faction);
            row.addProperty("lastSeen", e.at());
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
        if (links.isHidden(uuid)) {
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

    /** Whether this viewer must not see the player's name or position. Admins and the player themself always may. */
    private boolean hiddenFrom(Viewer v, UUID uuid) {
        if (uuid == null || v.admin() || uuid.equals(v.uuid())) {
            return false;
        }
        return isHiddenPlayer(uuid);
    }

    private JsonObject playerRow(Viewer v, AtlasConfig cfg, DominionSnapshot.View view, String uuidKey, String name,
                                 String world, double x, double y, double z, JsonObject snap,
                                 Float hp, Float maxHp, boolean factionLimited, boolean located) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidKey);
        } catch (IllegalArgumentException e) {
            return null;
        }
        boolean self = v.uuid() != null && v.uuid().equals(uuid);
        boolean hidden = isHiddenPlayer(uuid) || Json.bool(snap, "hidden");
        String faction = snap != null ? Json.str(snap, "faction") : view.factionOfPlayer.get(uuidKey);
        if (faction != null && faction.isBlank()) {
            faction = null;
        }
        if (!v.admin() && !self) {
            if (hidden) {
                return null;
            }
            if (factionLimited && !v.sameFaction(faction)) {
                return null;
            }
            if (located && cfg.hidePlayersInOwnTerritory && faction != null && !v.sameFaction(faction)) {
                JsonObject t = view.territoryAt(world, Math.floorDiv((int) Math.floor(x), 32), Math.floorDiv((int) Math.floor(z), 32));
                if (t != null && faction.equalsIgnoreCase(Json.str(t, "tag"))) {
                    return null;
                }
            }
        }
        JsonObject row = new JsonObject();
        row.addProperty("uuid", uuidKey);
        row.addProperty("name", name);
        row.addProperty("world", world);
        if (!located) {
            // Online but without a known position. Every viewer, admins included, gets null coordinates.
            row.add("x", JsonNull.INSTANCE);
            row.add("y", JsonNull.INSTANCE);
            row.add("z", JsonNull.INSTANCE);
            row.addProperty("located", false);
            if (v.admin()) {
                row.addProperty("hidden", hidden);
            }
        } else if (v.admin()) {
            row.addProperty("x", Math.round(x * 100) / 100.0);
            row.addProperty("y", Math.round(y * 100) / 100.0);
            row.addProperty("z", Math.round(z * 100) / 100.0);
            row.addProperty("hidden", hidden);
        } else if (!self && y < cfg.hideBelowY) {
            // Below the hideBelowY line the player counts as underground. Non admins get no position.
            row.add("x", JsonNull.INSTANCE);
            row.add("y", JsonNull.INSTANCE);
            row.add("z", JsonNull.INSTANCE);
            row.addProperty("underground", true);
        } else {
            row.addProperty("x", Math.round(x));
            row.addProperty("y", Math.round(y));
            row.addProperty("z", Math.round(z));
        }
        row.addProperty("faction", faction);
        String color = snap != null ? Json.str(snap, "color") : null;
        if (color == null && faction != null) {
            color = Json.str(view.faction(faction), "color");
        }
        row.addProperty("color", color);
        if ((cfg.showPlayerHealth || v.admin()) && hp != null) {
            row.addProperty("health", Math.round(hp * 10) / 10.0);
            row.addProperty("maxHealth", maxHp == null ? null : Math.round(maxHp * 10) / 10.0);
        }
        if (self) {
            row.addProperty("self", true);
        }
        return row;
    }

    // ---- worlds ------------------------------------------------------------

    public JsonObject worldsPayload(Viewer v) {
        JsonObject out = new JsonObject();
        out.addProperty("title", config.publicTitle);
        out.addProperty("standalone", !snapshot.present());
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
        boolean showCounts = v.canSee(config, "players") && (config.showPlayers || v.admin());
        MapModSettings.Settings rules = mapRules();
        Universe u = Universe.get();
        if (u != null) {
            for (World w : u.getWorlds().values()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", w.getName());
                LiveState.WorldInfo info = live.worlds().get(w.getName());
                LiveState.WorldKind kind = info == null || info.kind() == null ? LiveState.WorldKind.plain(w.getName()) : info.kind();
                o.addProperty("kind", kind.kind());
                o.addProperty("displayName", kind.displayName() == null ? w.getName() : kind.displayName());
                o.addProperty("hidden", kind.transientWorld() && !config.showPortalWorlds && !v.admin());
                o.add("portal", kind.portal() == null ? JsonNull.INSTANCE : kind.portal().deepCopy());
                o.addProperty("players", showCounts ? w.getPlayerCount() : 0);
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
                    t.addProperty("dayProgress", info.dayProgress());
                    o.add("time", t);
                } else {
                    o.add("time", JsonNull.INSTANCE);
                }
                o.addProperty("mapEnabled", info != null && info.mapEnabled());
                ExploredIndex ex = tiles.explored(w.getName());
                o.addProperty("exploredChunks", ex.size());
                int[] b = ex.bounds();
                if (b != null) {
                    JsonObject bo = new JsonObject();
                    bo.addProperty("minX", b[0] * 32);
                    bo.addProperty("minZ", b[1] * 32);
                    bo.addProperty("maxX", b[2] * 32 + 32);
                    bo.addProperty("maxZ", b[3] * 32 + 32);
                    o.add("bounds", bo);
                }
                if (rules != null && rules.border() != null) {
                    JsonObject bd = new JsonObject();
                    bd.addProperty("shape", "circle");
                    bd.addProperty("x", rules.border().x());
                    bd.addProperty("z", rules.border().z());
                    bd.addProperty("radius", rules.border().radius());
                    o.add("border", bd);
                } else {
                    o.add("border", JsonNull.INSTANCE);
                }
                worlds.add(o);
            }
        }
        out.add("worlds", worlds);
        return out;
    }

    // ---- territories and points --------------------------------------------

    public JsonObject territoriesPayload(Viewer v, String world) {
        JsonObject out = new JsonObject();
        DominionSnapshot.View view = snapshot.view();
        out.addProperty("world", world);
        out.addProperty("at", view.at());
        JsonArray list = new JsonArray();
        JsonArray sieges = new JsonArray();
        out.add("territories", list);
        out.add("sieges", sieges);
        if (!v.canSee(config, "territories")) {
            out.addProperty("hidden", true);
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
            JsonObject copy = t.deepCopy();
            // The client walks these as [cx, cz] pairs, so anything else is dropped here.
            copy.add("chunks", cleanChunks(Json.arr(t, "chunks")));
            copy.add("breached", cleanChunks(Json.arr(t, "breached")));
            copy.add("zones", cleanZones(Json.arr(t, "zones")));
            JsonObject f = view.faction(Json.str(t, "tag"));
            if (f != null) {
                copy.addProperty("power", Json.num(f, "power", 0));
                copy.addProperty("maxPower", Json.num(f, "maxPower", 0));
                copy.addProperty("claims", Json.num(f, "claims", 0));
            }
            list.add(copy);
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
            sieges.add(s.deepCopy());
        }
        return out;
    }

    /** Named zones inside a territory, kept only when they have a name and at least one chunk. */
    private static JsonArray cleanZones(JsonArray in) {
        JsonArray out = new JsonArray();
        for (JsonElement e : in) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject z = e.getAsJsonObject();
            String name = Json.str(z, "name");
            JsonArray chunks = cleanChunks(Json.arr(z, "chunks"));
            if (name == null || name.isBlank() || chunks.isEmpty()) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", Json.str(z, "id"));
            o.addProperty("name", name);
            o.add("chunks", chunks);
            out.add(o);
        }
        return out;
    }

    private static JsonArray cleanChunks(JsonArray in) {
        JsonArray out = new JsonArray();
        for (JsonElement e : in) {
            int[] c = DominionSnapshot.chunkOf(e);
            if (c != null) {
                JsonArray pair = new JsonArray();
                pair.add(c[0]);
                pair.add(c[1]);
                out.add(pair);
            }
        }
        return out;
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
                boolean eventPoint = isEventKind(kind);
                if (eventPoint ? !seeEvents : !seePoints) {
                    continue;
                }
                String fac = Json.str(p, "faction");
                boolean ownerless = fac == null || fac.isBlank();
                // Hills, outposts and envoys are server events that everyone may contest.
                if (!eventPoint && limited && !ownerless && !v.sameFaction(fac)) {
                    continue;
                }
                JsonObject copy = p.deepCopy();
                if (eventPoint) {
                    copy.addProperty("event", true);
                    mergeEventStatus(copy, kind, events);
                }
                if (!v.admin()) {
                    copy.addProperty("x", Math.round(Json.num(p, "x", 0)));
                    copy.addProperty("z", Math.round(Json.num(p, "z", 0)));
                    copy.remove("y");
                }
                points.add(copy);
            }
        }
        if (v.canSee(config, "zones")) {
            Universe u = Universe.get();
            World w = u == null ? null : u.getWorld(world);
            if (w != null) {
                try {
                    Map<String, MapMarker> poi = w.getWorldMapManager().getPointsOfInterest();
                    MapModSettings.Settings rules = mapRules();
                    if (poi != null && (rules == null || !rules.hideAllPoi())) {
                        for (Map.Entry<String, MapMarker> e : new ArrayList<>(poi.entrySet())) {
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
                            z.addProperty("icon", m.markerImage);
                            zones.add(z);
                        }
                    }
                } catch (RuntimeException ignored) {
                    // World map disabled or not generated yet.
                }
            }
        }
        return out;
    }

    static boolean isEventKind(String kind) {
        if (kind == null) {
            return false;
        }
        String k = kind.toLowerCase(Locale.ROOT);
        return k.equals("hill") || k.equals("koth") || k.equals("outpost") || k.equals("envoy");
    }

    /** Copies the holder, control and status lines of the matching hill or outpost onto a point. */
    private static void mergeEventStatus(JsonObject point, String kind, JsonObject events) {
        if (events == null) {
            return;
        }
        String k = kind.toLowerCase(Locale.ROOT);
        String list = k.equals("outpost") ? "outposts" : k.equals("envoy") ? null : "koths";
        if (list == null) {
            return;
        }
        String label = Json.str(point, "label");
        String id = Json.str(point, "id");
        for (JsonElement e : Json.arr(events, list)) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            String name = Json.str(o, "name");
            if (name == null || !(name.equalsIgnoreCase(label) || name.equalsIgnoreCase(id)
                    || (id != null && id.toLowerCase(Locale.ROOT).endsWith(":" + name.toLowerCase(Locale.ROOT))))) {
                continue;
            }
            for (String f : new String[]{"holder", "control", "status"}) {
                String val = Json.str(o, f);
                if (val != null && !point.has(f)) {
                    point.addProperty(f, val);
                }
            }
            return;
        }
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

    private static final String[] FACTION_FIELDS = {"id", "tag", "name", "color", "leader", "members", "online",
            "claims", "power", "maxPower", "value", "rank", "kills", "deaths", "siegesWon", "siegesLost", "level",
            "points", "relations", "createdAt", "description"};

    private JsonObject factionRow(Viewer v, DominionSnapshot.View view, JsonObject f) {
        JsonObject o = new JsonObject();
        for (String k : FACTION_FIELDS) {
            JsonElement e = f.get(k);
            if (e != null) {
                o.add(k, e.deepCopy());
            }
        }
        if (o.has("members") && o.get("members").isJsonArray()) {
            o.addProperty("memberCount", o.getAsJsonArray("members").size());
            if (!v.admin() && !v.sameFaction(Json.str(f, "tag"))) {
                o.remove("members");
                o.addProperty("members", f.getAsJsonArray("members").size());
            }
        }
        if (v.admin() || v.sameFaction(Json.str(f, "tag"))) {
            JsonElement bank = f.get("bank");
            if (bank != null) {
                o.add("bank", bank.deepCopy());
            }
        }
        JsonObject nation = v.canSee(config, "nations") ? view.nationOf(f) : null;
        o.add("nation", nation == null ? JsonNull.INSTANCE : nation);
        return o;
    }

    public JsonObject factionsPayload(Viewer v) {
        JsonObject out = new JsonObject();
        JsonArray list = new JsonArray();
        out.add("factions", list);
        out.addProperty("at", snapshot.view().at());
        if (!v.canSee(config, "factions")) {
            out.addProperty("hidden", true);
            return out;
        }
        boolean limited = v.factionLimited(config, "factions");
        List<JsonObject> rows = new ArrayList<>();
        for (JsonElement e : snapshot.view().factions()) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject f = e.getAsJsonObject();
            if (limited && !v.sameFaction(Json.str(f, "tag"))) {
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
        if (f == null || (v.factionLimited(config, "factions") && !v.sameFaction(tag))) {
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
                o.addProperty("world", Json.str(p, "world"));
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
                    sieges.add(s.deepCopy());
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
        JsonArray list = new JsonArray();
        JsonArray independent = new JsonArray();
        out.add("nations", list);
        out.add("independent", independent);
        DominionSnapshot.View view = snapshot.view();
        out.addProperty("at", view.at());
        if (!v.canSee(config, "nations")) {
            out.addProperty("hidden", true);
            return out;
        }
        Map<String, JsonObject> nations = new java.util.LinkedHashMap<>();
        for (JsonObject listed : view.nations) {
            JsonObject o = new JsonObject();
            String tag = Json.str(listed, "tag");
            String name = Json.str(listed, "name");
            o.addProperty("tag", tag);
            o.addProperty("name", name == null || name.isBlank() ? tag : name);
            String color = Json.str(listed, "color");
            if (color != null && !color.isBlank()) {
                o.addProperty("color", color);
            }
            String id = Json.str(listed, "id");
            if (id != null && !id.isBlank()) {
                o.addProperty("id", id);
            }
            JsonElement members = listed.get("members");
            if (members != null && members.isJsonArray()) {
                o.addProperty("memberCount", members.getAsJsonArray().size());
            }
            o.add("factions", new JsonArray());
            o.addProperty("value", 0.0);
            o.addProperty("claims", 0.0);
            nations.put(tag.trim().toUpperCase(Locale.ROOT), o);
        }
        boolean showFactions = v.canSee(config, "factions");
        boolean limited = showFactions && v.factionLimited(config, "factions");
        List<JsonObject> rows = new ArrayList<>();
        if (showFactions) {
            for (JsonElement e : view.factions()) {
                if (e.isJsonObject()) {
                    rows.add(e.getAsJsonObject());
                }
            }
        }
        rows.sort((a, b) -> Double.compare(Json.num(b, "value", 0), Json.num(a, "value", 0)));
        for (JsonObject f : rows) {
            if (limited && !v.sameFaction(Json.str(f, "tag"))) {
                continue;
            }
            JsonObject brief = new JsonObject();
            for (String k : new String[]{"tag", "name", "color", "value", "claims", "power"}) {
                JsonElement x = f.get(k);
                if (x != null) {
                    brief.add(k, x.deepCopy());
                }
            }
            brief.addProperty("members", f.get("members") != null && f.get("members").isJsonArray()
                    ? f.getAsJsonArray("members").size() : (long) Json.num(f, "members", 0));
            JsonObject nation = view.nationOf(f);
            if (nation == null) {
                independent.add(brief);
                continue;
            }
            String key = Json.str(nation, "tag").toUpperCase(Locale.ROOT);
            JsonObject n = nations.computeIfAbsent(key, k -> {
                JsonObject o = nation.deepCopy();
                o.add("factions", new JsonArray());
                o.addProperty("value", 0.0);
                o.addProperty("claims", 0.0);
                return o;
            });
            n.getAsJsonArray("factions").add(brief);
            n.addProperty("value", Json.num(n, "value", 0) + Json.num(f, "value", 0));
            n.addProperty("claims", Json.num(n, "claims", 0) + Json.num(f, "claims", 0));
        }
        List<JsonObject> sorted = new ArrayList<>(nations.values());
        sorted.sort((a, b) -> Double.compare(Json.num(b, "value", 0), Json.num(a, "value", 0)));
        sorted.forEach(list::add);
        return out;
    }

    /**
     * The events summary for the stats panel. Hills and outposts keep their name, status, holder and
     * control lines, envoys are counted, bounties and the season are passed through.
     */
    private JsonElement eventsSummary(Viewer v, JsonObject events) {
        if (events == null || !v.canSee(config, "events")) {
            return JsonNull.INSTANCE;
        }
        JsonObject out = new JsonObject();
        if (events.has("enabled")) {
            out.addProperty("enabled", Json.bool(events, "enabled"));
        }
        out.add("koths", namedRows(Json.arr(events, "koths"), "status", "holder", "endsAt"));
        out.add("outposts", namedRows(Json.arr(events, "outposts"), "holder", "control", "status"));
        JsonArray envoys = new JsonArray();
        for (JsonElement e : Json.arr(events, "envoys")) {
            if (e.isJsonPrimitive()) {
                envoys.add(e.getAsString());
            } else if (e.isJsonObject()) {
                String text = Json.str(e.getAsJsonObject(), "name");
                if (text == null) {
                    text = Json.str(e.getAsJsonObject(), "status");
                }
                if (text != null) {
                    envoys.add(text);
                }
            }
        }
        out.add("envoys", envoys);
        out.addProperty("bounties", (long) Json.num(events, "bounties", 0));
        JsonElement season = events.get("season");
        if (season == null || season.isJsonNull()) {
            out.add("season", JsonNull.INSTANCE);
        } else if (season.isJsonPrimitive()) {
            out.addProperty("season", season.getAsString());
        } else if (season.isJsonObject()) {
            String status = Json.str(season.getAsJsonObject(), "status");
            out.addProperty("season", status != null ? status : Json.str(season.getAsJsonObject(), "phase"));
            double ends = Json.num(season.getAsJsonObject(), "endsAt", 0);
            if (ends > 0) {
                out.addProperty("seasonEndsAt", (long) ends);
            }
        }
        return out;
    }

    private static JsonArray namedRows(JsonArray in, String... keys) {
        JsonArray out = new JsonArray();
        for (JsonElement e : in) {
            JsonObject o = new JsonObject();
            if (e.isJsonPrimitive()) {
                o.addProperty("name", e.getAsString());
            } else if (e.isJsonObject()) {
                String name = Json.str(e.getAsJsonObject(), "name");
                if (name == null) {
                    continue;
                }
                o.addProperty("name", name);
                for (String k : keys) {
                    JsonElement x = e.getAsJsonObject().get(k);
                    if (x != null && x.isJsonPrimitive()) {
                        o.add(k, x.deepCopy());
                    }
                }
            } else {
                continue;
            }
            out.add(o);
        }
        return out;
    }

    public JsonObject statsPayload(Viewer v) {
        JsonObject out = new JsonObject();
        if (!v.canSee(config, "stats")) {
            out.addProperty("hidden", true);
            return out;
        }
        long now = System.currentTimeMillis();
        DominionSnapshot.View view = snapshot.view();
        Universe u = Universe.get();
        out.addProperty("at", now);
        out.addProperty("online", u == null ? 0 : u.getPlayerCount());
        out.addProperty("worlds", u == null ? 0 : u.getWorlds().size());
        out.addProperty("uptimeMs", now - startedAt);
        JsonObject snap = new JsonObject();
        snap.addProperty("present", snapshot.present());
        snap.addProperty("at", snapshot.present() ? view.at() : 0);
        out.add("snapshot", snap);
        if (snapshot.present()) {
            JsonArray factions = view.factions();
            int claims = 0;
            List<JsonObject> rows = new ArrayList<>();
            for (JsonElement e : factions) {
                if (e.isJsonObject()) {
                    rows.add(e.getAsJsonObject());
                    claims += (int) Json.num(e.getAsJsonObject(), "claims", 0);
                }
            }
            JsonObject fs = new JsonObject();
            fs.addProperty("count", rows.size());
            fs.addProperty("claims", claims);
            boolean showFactions = v.canSee(config, "factions") && !v.factionLimited(config, "factions");
            if (showFactions) {
                fs.add("topValue", top(rows, "value"));
                fs.add("topPower", top(rows, "power"));
                fs.add("topKills", top(rows, "kills"));
            }
            out.add("factions", fs);
            JsonObject grace = Json.obj(view.root, "grace");
            out.add("grace", grace == null ? JsonNull.INSTANCE : grace.deepCopy());
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
            out.add("events", eventsSummary(v, view.events()));
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

    private static JsonArray top(List<JsonObject> rows, String key) {
        List<JsonObject> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> Double.compare(Json.num(b, key, 0), Json.num(a, key, 0)));
        JsonArray out = new JsonArray();
        for (int i = 0; i < Math.min(5, sorted.size()); i++) {
            JsonObject f = sorted.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("tag", Json.str(f, "tag"));
            o.addProperty("name", Json.str(f, "name"));
            o.addProperty("color", Json.str(f, "color"));
            o.addProperty(key, Json.num(f, key, 0));
            out.add(o);
        }
        return out;
    }

    public JsonObject changesPayload(Viewer v, String world, long since) {
        long now = System.currentTimeMillis();
        long floor = now - HeatTracker.RETENTION_MS;
        long from = Math.max(floor, Math.min(since, now));
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
        if (v.canSee(config, "heat")) {
            JsonObject h = new JsonObject();
            h.addProperty("bucketMs", HeatTracker.BUCKET_MS);
            JsonArray rows = heat.query(world, from);
            if (v.factionLimited(config, "heat")) {
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
            h.add("chunks", rows);
            out.add("heat", h);
        } else {
            out.add("heat", JsonNull.INSTANCE);
        }
        return out;
    }

    // ---- feed and chat -----------------------------------------------------

    public void addFeed(JsonObject line) {
        synchronized (feed) {
            feed.addLast(line);
            while (feed.size() > FEED_KEEP) {
                feed.removeFirst();
            }
        }
        sse.broadcastAll("feed", Json.GSON.toJson(line), c -> c.viewer().canSee(config, "feed"));
    }

    public JsonArray recentFeed() {
        JsonArray a = new JsonArray();
        synchronized (feed) {
            feed.forEach(a::add);
        }
        return a;
    }

    /** Adds a chat line captured from a chat event. The tag is the sender's faction from the snapshot. */
    public void addChat(UUID sender, String name, String text) {
        addChat(name, factionOf(sender), text, System.currentTimeMillis());
    }

    /** Adds a chat line with its own time. The tag is the sender's faction tag, null when there is none. */
    public void addChat(String name, String tag, String text, long at) {
        JsonObject line = new JsonObject();
        line.addProperty("at", at);
        line.addProperty("name", name);
        line.addProperty("tag", tag == null || tag.isBlank() ? null : tag);
        line.addProperty("text", text);
        synchronized (chat) {
            chat.addLast(line);
            while (chat.size() > CHAT_KEEP) {
                chat.removeFirst();
            }
        }
        sse.broadcastAll("chat", Json.GSON.toJson(line), c -> c.viewer().canSee(config, "chat"));
    }

    public JsonArray recentChat() {
        JsonArray a = new JsonArray();
        synchronized (chat) {
            chat.forEach(a::add);
        }
        return a;
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
                    feed.addLast(lines.get(i).getAsJsonObject());
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
        JsonArray list = new JsonArray();
        out.add("markers", list);
        if (!v.canSee(config, "markers")) {
            out.addProperty("hidden", true);
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
            list.add(c.json());
        }
        return out;
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
     * Reads shared markers of every world and the personal markers of players with an open stream on
     * the world threads, and sends markers events for the sets that changed. The comparison and the
     * sending run on the given executor, which is the Atlas scheduler.
     */
    public void pollMarkers(java.util.concurrent.Executor executor) {
        engine.pollShared(executor, world -> {
            JsonObject o = new JsonObject();
            o.addProperty("world", world);
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

    private JsonObject worldEventJson(EngineData.ActiveEvent e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id().toString());
        o.addProperty("world", e.world());
        o.addProperty("name", e.name());
        o.addProperty("displayName", e.displayName());
        o.addProperty("startedAt", e.startedAt());
        o.addProperty("x", e.x());
        o.addProperty("z", e.z());
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
            out.addProperty("hidden", true);
            return out;
        }
        for (EngineData.ActiveEvent e : engine.activeEvents(world)) {
            list.add(worldEventJson(e));
        }
        return out;
    }

    /**
     * Announces a world event start or end over the event stream and in the Atlas feed. Events without a
     * location are not announced.
     */
    void worldEventChanged(EngineData.ActiveEvent e, String state, long at) {
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
        o.addProperty("x", e.x());
        o.addProperty("z", e.z());
        o.addProperty("at", at);
        sse.broadcastAll("worldevent", Json.GSON.toJson(o), c -> c.viewer().canSee(config, "worldevents"));
        String where = worldLabel(e.world());
        feedLine("worldevent", "start".equals(state)
                ? e.displayName() + " began in " + where + "."
                : e.displayName() + " ended in " + where + ".", at);
    }

    /** The display name of a world for feed lines. */
    private String worldLabel(String world) {
        LiveState.WorldInfo info = world == null ? null : live.worlds().get(world);
        if (info != null && info.kind() != null && info.kind().displayName() != null) {
            return info.kind().displayName();
        }
        return world == null ? "the world" : world;
    }

    private void feedLine(String type, String text, long at) {
        JsonObject line = new JsonObject();
        line.addProperty("at", at);
        line.addProperty("type", type);
        line.addProperty("text", text);
        line.addProperty("source", "atlas");
        addFeed(line);
    }

    /** The name of a kill party as this viewer may see it. Hidden players are A player to non admins. */
    private String partyName(Viewer v, KillLog.Party p) {
        if (p.player() && hiddenFrom(v, p.uuid())) {
            return "A player";
        }
        return p.name();
    }

    private JsonObject partyJson(Viewer v, KillLog.Party p) {
        JsonObject o = new JsonObject();
        o.addProperty("name", partyName(v, p));
        o.addProperty("type", p.type());
        o.addProperty("player", p.player());
        if (!p.player()) {
            o.addProperty("boss", p.boss());
        } else {
            o.addProperty("boss", false);
        }
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
        JsonObject o = new JsonObject();
        o.addProperty("at", k.at());
        o.addProperty("world", k.world());
        o.add("victim", partyJson(v, k.victim()));
        o.add("killer", k.killer() == null ? JsonNull.INSTANCE : partyJson(v, k.killer()));
        o.addProperty("cause", k.cause());
        o.addProperty("ranged", k.ranged());
        return o;
    }

    public JsonArray killLog(Viewer v, int limit) {
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

    public JsonObject killsPayload(Viewer v, int limit) {
        JsonObject out = new JsonObject();
        out.add("kills", killLog(v, Math.min(KillLog.KEEP, Math.max(1, limit))));
        if (!v.canSee(config, "kills")) {
            out.addProperty("hidden", true);
        }
        return out;
    }

    /** Records a death from the death system, sends it to browsers and adds boss kills and player deaths to the feed. */
    public void addKill(KillLog.Kill k) {
        kills.add(k);
        sse.broadcast("kill", c -> c.viewer().canSee(config, "kills"), c -> {
            JsonObject o = killJson(c.viewer(), k);
            return o == null ? null : Json.GSON.toJson(o);
        });
        String where = worldLabel(k.world());
        KillLog.Party victim = k.victim();
        KillLog.Party killer = k.killer();
        if (!victim.player() && victim.boss() && killer != null) {
            feedLine("boss", feedName(killer) + " defeated " + victim.name() + " in " + where + ".", k.at());
        } else if (victim.player()) {
            String text;
            if (killer != null && killer.player()) {
                text = feedName(victim) + " was slain by " + feedName(killer) + " in " + where + ".";
            } else if (killer != null) {
                text = feedName(victim) + " was slain by " + (killer.boss() ? "" : "a ") + killer.name() + " in " + where + ".";
            } else if (k.cause() != null) {
                text = feedName(victim) + " died from " + k.cause().replace('_', ' ').toLowerCase(Locale.ROOT) + " in " + where + ".";
            } else {
                text = feedName(victim) + " died in " + where + ".";
            }
            feedLine("death", text, k.at());
        }
    }

    /** Feed text is shared by every viewer, so hidden players are always A player there. */
    private String feedName(KillLog.Party p) {
        if (p.player() && isHiddenPlayer(p.uuid())) {
            return "A player";
        }
        return p.name();
    }

    /**
     * Active boss bars. EncounterBossBarState keeps its target, name and hard mode flag in private
     * fields with no getters in 0.7.0, so the list stays empty instead of reading private state.
     */
    public JsonObject bossesPayload(Viewer v) {
        JsonObject out = new JsonObject();
        out.add("bosses", new JsonArray());
        if (!v.canSee(config, "events")) {
            out.addProperty("hidden", true);
        }
        return out;
    }

    /** The Feats export unchanged plus available and updatedAt, with hidden players renamed for non admins. */
    public JsonObject leaderboardPayload(Viewer v) {
        JsonObject out = new JsonObject();
        if (!v.canSee(config, "leaderboard")) {
            out.addProperty("available", false);
            out.addProperty("hidden", true);
            return out;
        }
        JsonObject root = leaderboard.root();
        if (root == null) {
            out.addProperty("available", false);
            return out;
        }
        out = root.deepCopy();
        out.addProperty("available", true);
        out.addProperty("updatedAt", leaderboard.updatedAt());
        if (!v.admin()) {
            for (String list : new String[]{"players", "recent"}) {
                for (JsonElement e : Json.arr(out, list)) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = e.getAsJsonObject();
                    String raw = Json.str(row, "uuid");
                    UUID uuid;
                    try {
                        uuid = raw == null ? null : UUID.fromString(raw);
                    } catch (IllegalArgumentException ex) {
                        uuid = null;
                    }
                    if (hiddenFrom(v, uuid)) {
                        row.addProperty("name", "A player");
                        row.add("uuid", JsonNull.INSTANCE);
                    }
                }
            }
        }
        return out;
    }

    /** Records where a player was when they left. Called from the disconnect event. */
    public void recordLastSeen(UUID uuid, String name, String world, double x, double y, double z) {
        if (uuid == null || world == null) {
            return;
        }
        lastSeen.record(new LastSeen.Entry(uuid, name, System.currentTimeMillis(), world, x, y, z));
    }
}
