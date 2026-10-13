package dev.ryvrook.atlas.data;

import com.google.gson.JsonObject;
import com.hypixel.hytale.builtin.adventure.worldevents.WorldEventManager;
import com.hypixel.hytale.builtin.adventure.worldevents.context.LocationContext;
import com.hypixel.hytale.builtin.adventure.worldevents.event.WorldEvent;
import com.hypixel.hytale.builtin.adventure.worldevents.event.WorldEventAsset;
import com.hypixel.hytale.builtin.teleport.TeleportPlugin;
import com.hypixel.hytale.builtin.teleport.Warp;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.worldmap.CompassAlertComponent;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarkerComponent;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarkerIconSize;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.meta.state.BlockMapMarkersResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerOverride;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.user.UserMapMarker;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.worldstore.WorldMarkersResource;
import dev.ryvrook.atlas.util.Text;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Engine map markers and world events, read on each world's own thread and cached.
 *
 * <p>Markers. WorldMapManager.getMarkerProviders() holds one provider per marker source, each with
 * update(World, Player, MarkersCollector). Providers are written for one player at a time. The 0.7.0
 * server classes show that POIMarkerProvider, SharedMarkersProvider, TeleportPlugin$WarpMarkerProvider and
 * PortalMarkerProvider never touch the player argument, so only those are called, with a null
 * player. SpawnMarkerProvider calls player.getUuid(), ObjectiveMarkerProvider and the block marker
 * provider read per player data, and the death, respawn, personal and other players providers are
 * per player by design, so all of those are skipped. Block markers such as breach markers are read
 * from BlockMapMarkersResource instead, keeping only the ones that need no discovery. Name and icon
 * overrides from getMarkerOverridesView() are applied by the name translation key, as
 * MarkersCollectorImpl does.
 *
 * <p>Points of interest and warps. A map mod may swap the poi and warp providers for its own, which
 * the whitelist then skips. So points of interest are read from WorldMapManager.getPointsOfInterest()
 * and warps from TeleportPlugin.getWarps(), and those direct reads win over any provider marker with
 * the same id.
 *
 * <p>Shared markers. Player made markers that everyone sees in game live in the world's chunk store
 * resource WorldMarkersResource, the same store SharedMarkersProvider reads. They are read from that
 * resource directly, so a map mod that replaces the engine providers does not hide them. Provider
 * markers with the id of a shared marker are dropped so nothing is listed twice.
 *
 * <p>World events. WorldEventManager is an entity store resource. forEach visits each active
 * WorldEvent, whose uuid and asset fields give the id and asset name. The position is the first
 * LocationContext in the event's context map, when it has one.
 */
public final class EngineData {
    public static final long MARKER_TTL_MS = 10_000;

    public static final String SOURCE_POI = "poi";
    public static final String SOURCE_WARP = "warp";
    public static final String SOURCE_BLOCK = "block";
    public static final String SOURCE_OTHER = "other";
    private static final String WARP_PREFIX = "Warp-";

    public record MarkerSet(long at, List<Found> markers, List<SharedMarker> shared) {
    }

    /**
     * One collected marker. source is poi, warp, block or other. direct is true for markers read from an
     * engine store and false for markers that came through a provider. nameKeys holds the raw name
     * text, id and image path, which hidden names are matched against. For block markers it holds
     * the name key, id and icon in that order.
     */
    public record Found(JsonObject json, String source, boolean direct, List<String> nameKeys) {

        public int x() {
            return json.get("x").getAsInt();
        }

        public int z() {
            return json.get("z").getAsInt();
        }
    }

    /**
     * One player made marker from the world store. Name, tint and creator may be null. The tint is
     * "#rrggbb". The icon is the image name without path or extension, such as UserB.
     */
    public record SharedMarker(String id, int x, int z, String name, String icon, String tint,
                               UUID byUuid, String byName) {
    }

    /** The shared markers of a world as last read, with a hash of everything the browser shows. */
    public record SharedSet(long at, List<SharedMarker> markers, long hash) {
    }

    public record ActiveEvent(String world, UUID id, String name, String displayName, long startedAt, Integer x, Integer z) {
    }

    /** Receives world event starts and ends. Called on the world thread. */
    public interface EventListener {
        void started(ActiveEvent e);

        void ended(ActiveEvent e, long at);
    }

    private static final Map<String, String> PROVIDERS = Map.of(
            "com.hypixel.hytale.server.core.universe.world.worldmap.markers.providers.POIMarkerProvider", "poi",
            "com.hypixel.hytale.server.core.universe.world.worldmap.markers.providers.SharedMarkersProvider", "engine",
            "com.hypixel.hytale.builtin.teleport.TeleportPlugin$WarpMarkerProvider", "engine",
            "com.hypixel.hytale.builtin.portals.integrations.PortalMarkerProvider", "engine");

    private final Map<String, MarkerSet> markers = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<MarkerSet>> markerJobs = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, ActiveEvent>> events = new ConcurrentHashMap<>();
    private final Map<String, SharedSet> shared = new ConcurrentHashMap<>();
    /**
     * The shared hash each world was last announced with. Only pollShared reads and writes it, so a
     * fresh read made for a request can never swallow a change before the poll announces it.
     */
    private final Map<String, Long> sharedAnnounced = new ConcurrentHashMap<>();
    /** The hash of the engine markers (warps, block markers, portal markers and the rest) each world was last announced with. */
    private final Map<String, Long> markersAnnounced = new ConcurrentHashMap<>();
    /** Worlds with a marker poll queued on their thread, so a slow world never piles up polls. */
    private final Set<String> markerPollInFlight = ConcurrentHashMap.newKeySet();
    /** Worlds with an event poll queued on their thread. */
    private final Set<String> eventPollInFlight = ConcurrentHashMap.newKeySet();
    private volatile EventListener listener = new EventListener() {
        @Override
        public void started(ActiveEvent e) {
        }

        @Override
        public void ended(ActiveEvent e, long at) {
        }
    };

    public void onEvents(EventListener l) {
        this.listener = l;
    }

    // ---- markers -------------------------------------------------------------

    /**
     * Cached markers of a world. When the cache is older than ten seconds a refresh is queued on the
     * world thread. The returned future completes at once with the cached set when one exists.
     */
    public CompletableFuture<MarkerSet> markers(World world) {
        String name = world.getName();
        MarkerSet cached = markers.get(name);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < MARKER_TTL_MS) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<MarkerSet> mine = new CompletableFuture<>();
        CompletableFuture<MarkerSet> running = markerJobs.putIfAbsent(name, mine);
        CompletableFuture<MarkerSet> job = running != null ? running : mine;
        if (running == null) {
            try {
                world.execute(() -> {
                    try {
                        List<SharedMarker> own = readShared(world);
                        long at = System.currentTimeMillis();
                        shared.put(name, new SharedSet(at, own, sharedHash(own)));
                        MarkerSet set = new MarkerSet(at, collect(world, own), own);
                        markers.put(name, set);
                        mine.complete(set);
                    } catch (Throwable t) {
                        mine.complete(new MarkerSet(System.currentTimeMillis(), List.of(), List.of()));
                    } finally {
                        markerJobs.remove(name, mine);
                    }
                });
            } catch (RuntimeException e) {
                markerJobs.remove(name, mine);
                mine.complete(new MarkerSet(System.currentTimeMillis(), List.of(), List.of()));
            }
        }
        return cached != null ? CompletableFuture.completedFuture(cached) : job;
    }

    @SuppressWarnings("deprecation")
    private static final class Collector implements MarkersCollector {
        final Map<String, MapMarker> found = new LinkedHashMap<>();
        final Map<String, String> kinds = new HashMap<>();
        String kind = "engine";

        @Override
        public void add(MapMarker m) {
            put(m);
        }

        @Override
        public void addIgnoreViewDistance(MapMarker m) {
            put(m);
        }

        private void put(MapMarker m) {
            if (m == null || m.transform == null || m.transform.position == null) {
                return;
            }
            String id = m.id != null ? m.id : kind + found.size();
            found.putIfAbsent(id, m);
            kinds.putIfAbsent(id, kind);
        }

        @Override
        public Predicate<PlayerRef> getPlayerMapFilter() {
            return p -> true;
        }

        @Override
        public boolean isInViewDistance(double x, double z) {
            return true;
        }
    }

    /**
     * Runs on the world thread. Points of interest come straight from WorldMapManager and warps from
     * the teleport plugin's warp list, so a mod that swaps the engine providers for its own does not
     * hide them. The whitelisted providers come after, and any marker whose id is already listed, or
     * belongs to a shared marker, is left out.
     */
    private static List<Found> collect(World world, List<SharedMarker> sharedMarkers) {
        List<Found> out = new ArrayList<>();
        WorldMapManager wmm = world.getWorldMapManager();
        if (wmm == null) {
            return out;
        }
        Map<String, MapMarkerOverride> overrides;
        try {
            overrides = wmm.getMarkerOverridesView();
        } catch (RuntimeException e) {
            overrides = Map.of();
        }
        Set<String> taken = new java.util.HashSet<>();
        for (SharedMarker m : sharedMarkers) {
            taken.add(m.id());
        }
        Map<String, Found> direct = new LinkedHashMap<>();
        try {
            Map<String, MapMarker> poi = wmm.getPointsOfInterest();
            if (poi != null) {
                for (Map.Entry<String, MapMarker> e : poi.entrySet()) {
                    MapMarker m = e.getValue();
                    if (m == null || m.transform == null || m.transform.position == null) {
                        continue;
                    }
                    String id = m.id != null ? m.id : e.getKey();
                    if (taken.contains(id) || direct.containsKey(id)) {
                        continue;
                    }
                    JsonObject o = markerJson(id, "poi", m, overrides);
                    direct.put(id, new Found(o, SOURCE_POI, true, nameKeys(m)));
                }
            }
        } catch (RuntimeException ignored) {
            // No points of interest on this world yet.
        }
        try {
            for (MapMarker m : warpMarkers(world)) {
                if (taken.contains(m.id) || direct.containsKey(m.id)) {
                    continue;
                }
                direct.put(m.id, new Found(markerJson(m.id, "engine", m, overrides), SOURCE_WARP, true, nameKeys(m)));
            }
        } catch (RuntimeException | LinkageError ignored) {
            // No teleport plugin on this server.
        }
        out.addAll(direct.values());
        taken.addAll(direct.keySet());

        Collector c = new Collector();
        Map<String, WorldMapManager.MarkerProvider> providers;
        try {
            providers = new LinkedHashMap<>(wmm.getMarkerProviders());
        } catch (RuntimeException e) {
            providers = Map.of();
        }
        for (WorldMapManager.MarkerProvider p : providers.values()) {
            String kind = p == null ? null : PROVIDERS.get(p.getClass().getName());
            if (kind == null) {
                continue;
            }
            c.kind = kind;
            try {
                p.update(world, null, c);
            } catch (RuntimeException ignored) {
                // A provider that needs a player after all. Its markers are left out.
            }
        }
        for (Map.Entry<String, MapMarker> e : c.found.entrySet()) {
            if (taken.contains(e.getKey())) {
                continue;
            }
            String kind = c.kinds.get(e.getKey());
            JsonObject o = markerJson(e.getKey(), kind, e.getValue(), overrides);
            if (o != null) {
                String source = "poi".equals(kind) ? SOURCE_POI
                        : e.getKey().startsWith(WARP_PREFIX) ? SOURCE_WARP : SOURCE_OTHER;
                out.add(new Found(o, source, false, nameKeys(e.getValue())));
            }
        }
        try {
            BlockMapMarkersResource res = BlockMapMarkersResource.of(world);
            if (res != null && res.getMarkers() != null) {
                res.getMarkers().forEach((x, y, z, data) -> {
                    if (data == null || data.getMarkerId() == null || data.isDiscoverable()) {
                        return;
                    }
                    JsonObject o = new JsonObject();
                    o.addProperty("id", data.getMarkerId());
                    o.addProperty("kind", "engine");
                    o.addProperty("name", data.getName() == null ? "Marker" : Text.translateOrPretty(data.getName()));
                    o.addProperty("x", x);
                    o.addProperty("y", y);
                    o.addProperty("z", z);
                    o.addProperty("icon", Text.imageName(data.getIcon()));
                    o.addProperty("major", data.getIconSize() == MapMarkerIconSize.Major);
                    var alert = data.getAlert();
                    o.add("alert", alert == null ? com.google.gson.JsonNull.INSTANCE : alertJson(alert.toComponent()));
                    // Name key, id and icon, the fields hidden names are matched against for block markers.
                    out.add(new Found(o, SOURCE_BLOCK, true, java.util.Arrays.asList(data.getName(), data.getMarkerId(), data.getIcon())));
                });
            }
        } catch (RuntimeException | LinkageError ignored) {
            // No block markers on this world.
        }
        return out;
    }

    /**
     * The warps of a world as the engine draws them, built the way TeleportPlugin$WarpMarkerProvider
     * does. Empty when the world map hides warps or the teleport plugin is not loaded. Warp is
     * deprecated in 0.7.0 but still the type the teleport plugin's own marker provider reads.
     */
    @SuppressWarnings("deprecation")
    private static List<MapMarker> warpMarkers(World world) {
        List<MapMarker> out = new ArrayList<>();
        TeleportPlugin tp = TeleportPlugin.get();
        if (tp == null) {
            return out;
        }
        try {
            if (!world.getGameplayConfig().getWorldMapConfig().isDisplayWarps()) {
                return out;
            }
        } catch (RuntimeException e) {
            // No gameplay config, show the warps like a default world would.
        }
        Map<String, Warp> warps = tp.getWarps();
        if (warps == null) {
            return out;
        }
        String name = world.getName();
        for (Warp w : new ArrayList<>(warps.values())) {
            if (w == null || w.getId() == null || !name.equals(w.getWorld()) || w.getTransform() == null
                    || w.getTransform().getPosition() == null) {
                continue;
            }
            out.add(new MapMarkerBuilder(WARP_PREFIX + w.getId(), "Warp.png", w.getTransform())
                    .withName(Message.translation("server.map.warp").param("warpName", w.getId()))
                    .build());
        }
        return out;
    }

    /** The raw name text, id and image of a marker, the values a hidden name is compared with. */
    private static List<String> nameKeys(MapMarker m) {
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
        return keys;
    }

    private static JsonObject markerJson(String id, String kind, MapMarker m, Map<String, MapMarkerOverride> overrides) {
        String icon = m.markerImage;
        String name = Text.plain(m.name);
        if (m.name != null && m.name.messageId != null && overrides != null) {
            MapMarkerOverride ov = overrides.get(m.name.messageId);
            if (ov != null) {
                if (ov.icon() != null) {
                    icon = ov.icon();
                }
                if (ov.name() != null) {
                    name = Text.translateOrPretty(ov.name());
                }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("kind", kind);
        o.addProperty("name", name == null || name.isBlank() ? Text.pretty(id) : name);
        o.addProperty("x", Math.round(m.transform.position.x));
        o.addProperty("y", Math.round(m.transform.position.y));
        o.addProperty("z", Math.round(m.transform.position.z));
        o.addProperty("icon", Text.imageName(icon));
        o.addProperty("major", m.iconSize == MapMarkerIconSize.Major);
        JsonObject alert = null;
        if (m.components != null) {
            for (MapMarkerComponent comp : m.components) {
                if (comp instanceof CompassAlertComponent a) {
                    alert = alertJson(a);
                    break;
                }
            }
        }
        o.add("alert", alert == null ? com.google.gson.JsonNull.INSTANCE : alert);
        return o;
    }

    private static JsonObject alertJson(CompassAlertComponent a) {
        if (a == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("icon", Text.imageName(a.image));
        o.addProperty("durationSeconds", a.durationSeconds);
        o.addProperty("timeoutSeconds", a.timeoutSeconds);
        o.addProperty("tint", a.tint == null ? null
                : String.format(Locale.ROOT, "#%02x%02x%02x", a.tint.red & 0xFF, a.tint.green & 0xFF, a.tint.blue & 0xFF));
        return o;
    }

    // ---- shared markers ------------------------------------------------------

    /**
     * Reads the player made markers of a world from WorldMarkersResource, exactly as
     * SharedMarkersProvider.update does. Runs on the world thread. Empty when the world has no store.
     */
    static List<SharedMarker> readShared(World world) {
        List<SharedMarker> out = new ArrayList<>();
        try {
            WorldMarkersResource res = world.getChunkStore().getStore().getResource(WorldMarkersResource.getResourceType());
            if (res == null || res.getUserMapMarkers() == null) {
                return out;
            }
            for (UserMapMarker m : res.getUserMapMarkers()) {
                SharedMarker s = sharedOf(m);
                if (s != null) {
                    out.add(s);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // No marker store on this world.
        }
        return out;
    }

    /** Turns an engine user marker into plain values, or null when it has no id. */
    public static SharedMarker sharedOf(UserMapMarker m) {
        if (m == null || m.getId() == null) {
            return null;
        }
        String name = m.getName();
        if (name != null && name.isBlank()) {
            name = null;
        }
        String byName = m.getCreatedByName();
        if (byName != null && byName.isBlank()) {
            byName = null;
        }
        return new SharedMarker(m.getId(), Math.round(m.getX()), Math.round(m.getZ()), name,
                Text.imageName(m.getIcon()), hex(m.getColorTint()), m.getCreatedByUuid(), byName);
    }

    /** A protocol colour as "#rrggbb", or null. */
    public static String hex(com.hypixel.hytale.protocol.Color c) {
        return c == null ? null : String.format(Locale.ROOT, "#%02x%02x%02x", c.red & 0xFF, c.green & 0xFF, c.blue & 0xFF);
    }

    /** Hash of id, position, name, icon, tint and creator of every marker, in store order. */
    public static long sharedHash(List<SharedMarker> list) {
        long h = 1125899906842597L;
        for (SharedMarker m : list) {
            h = 31 * h + java.util.Objects.hash(m.id(), m.x(), m.z(), m.name(), m.icon(), m.tint(), m.byUuid(), m.byName());
        }
        return h;
    }

    /** The shared markers of a world as last read, or null before the first read. */
    public SharedSet shared(String world) {
        return shared.get(world);
    }

    /**
     * Reads the shared markers and the engine markers of every world on its own thread and hands the name
     * of each world whose markers changed since the previous read to the listener, on the given executor.
     * Shared markers, warps, block markers and portal markers all count. The first read of a world only
     * records it. A world whose previous poll has not run yet is skipped.
     */
    public void pollShared(java.util.concurrent.Executor executor, java.util.function.Consumer<String> changed) {
        Universe u = Universe.get();
        if (u == null) {
            return;
        }
        for (World w : u.getWorlds().values()) {
            String name = w.getName();
            if (!markerPollInFlight.add(name)) {
                continue;
            }
            CompletableFuture<MarkerSet> read = new CompletableFuture<>();
            try {
                w.execute(() -> {
                    try {
                        List<SharedMarker> own = readShared(w);
                        read.complete(new MarkerSet(System.currentTimeMillis(), collect(w, own), own));
                    } catch (Throwable t) {
                        read.complete(null);
                    } finally {
                        markerPollInFlight.remove(name);
                    }
                });
            } catch (RuntimeException e) {
                markerPollInFlight.remove(name);
                continue;
            }
            read.thenAcceptAsync(set -> {
                if (set == null || !u.getWorlds().containsKey(name)) {
                    return;
                }
                SharedSet next = new SharedSet(set.at(), set.shared(), sharedHash(set.shared()));
                shared.put(name, next);
                markers.put(name, set);
                long engineHash = markersHash(set.markers());
                Long prevShared = sharedAnnounced.put(name, next.hash());
                Long prevEngine = markersAnnounced.put(name, engineHash);
                boolean sharedChanged = prevShared != null && prevShared != next.hash();
                boolean engineChanged = prevEngine != null && prevEngine != engineHash;
                if (sharedChanged || engineChanged) {
                    changed.accept(name);
                }
            }, executor);
        }
    }

    /** Hash of the JSON of every engine marker, in collection order. */
    static long markersHash(List<Found> list) {
        long h = 1125899906842597L;
        for (Found f : list) {
            h = 31 * h + f.json().toString().hashCode();
        }
        return h;
    }

    // ---- world events --------------------------------------------------------

    /** Queues a poll of the active world events on every world thread. */
    public void pollEvents() {
        Universe u = Universe.get();
        if (u == null) {
            return;
        }
        for (World w : u.getWorlds().values()) {
            String name = w.getName();
            if (!eventPollInFlight.add(name)) {
                continue;
            }
            try {
                w.execute(() -> {
                    try {
                        pollWorld(w);
                    } finally {
                        eventPollInFlight.remove(name);
                    }
                });
            } catch (RuntimeException ignored) {
                // World is shutting down.
                eventPollInFlight.remove(name);
            }
        }
    }

    private void pollWorld(World world) {
        String name = world.getName();
        Map<UUID, ActiveEvent> now = new LinkedHashMap<>();
        Map<UUID, ActiveEvent> prev = events.get(name);
        long at = System.currentTimeMillis();
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            WorldEventManager mgr = store.getResource(WorldEventManager.getResourceType());
            if (mgr == null) {
                return;
            }
            mgr.forEach(now, (ev, into) -> {
                if (ev == null || ev.uuid == null || ev.asset == null) {
                    return;
                }
                ActiveEvent old = prev == null ? null : prev.get(ev.uuid);
                into.put(ev.uuid, old != null ? old : describe(name, ev, at));
            });
        } catch (RuntimeException | LinkageError e) {
            return;
        }
        events.put(name, now);
        if (prev == null) {
            // First poll after start. Events already running are listed but not announced.
            return;
        }
        for (ActiveEvent e : now.values()) {
            if (!prev.containsKey(e.id())) {
                listener.started(e);
            }
        }
        for (ActiveEvent e : prev.values()) {
            if (!now.containsKey(e.id())) {
                listener.ended(e, at);
            }
        }
    }

    private static ActiveEvent describe(String world, WorldEvent ev, long at) {
        String display = null;
        try {
            WorldEventAsset asset = WorldEventAsset.getAssetOrUnknown(ev.asset);
            if (asset != null && asset.name() != null) {
                display = Text.translate(asset.name());
            }
        } catch (RuntimeException ignored) {
            // Unknown asset.
        }
        if (display == null || display.isBlank()) {
            display = Text.pretty(ev.asset);
        }
        int[] pos = null;
        try {
            int[][] found = {null};
            ev.context.forEach(LocationContext.class, found, null, (loc, f, unused) -> {
                if (f[0] == null && loc != null && loc.location() != null) {
                    Vector3i v = loc.location();
                    f[0] = new int[]{v.x, v.z};
                }
            });
            pos = found[0];
        } catch (RuntimeException ignored) {
            // No location in this event.
        }
        return new ActiveEvent(world, ev.uuid, ev.asset, display, at, pos == null ? null : pos[0], pos == null ? null : pos[1]);
    }

    /** Active events, optionally of one world, oldest first. */
    public List<ActiveEvent> activeEvents(String world) {
        List<ActiveEvent> out = new ArrayList<>();
        for (Map.Entry<String, Map<UUID, ActiveEvent>> e : events.entrySet()) {
            if (world != null && !world.equals(e.getKey())) {
                continue;
            }
            out.addAll(e.getValue().values());
        }
        out.sort((a, b) -> Long.compare(a.startedAt(), b.startedAt()));
        return out;
    }

    /** Forgets a removed world and returns the events that were active in it. */
    public List<ActiveEvent> dropWorld(String world) {
        forgetMarkers(world);
        Map<UUID, ActiveEvent> gone = events.remove(world);
        eventPollInFlight.remove(world);
        return gone == null ? List.of() : new ArrayList<>(gone.values());
    }

    /**
     * Forgets the cached markers of a removed world. A marker read still queued on its thread is completed
     * with an empty set so no request waits for a world that is gone.
     */
    public void forgetMarkers(String world) {
        markers.remove(world);
        shared.remove(world);
        sharedAnnounced.remove(world);
        markersAnnounced.remove(world);
        markerPollInFlight.remove(world);
        CompletableFuture<MarkerSet> job = markerJobs.remove(world);
        if (job != null) {
            job.complete(new MarkerSet(System.currentTimeMillis(), List.of(), List.of()));
        }
    }
}
