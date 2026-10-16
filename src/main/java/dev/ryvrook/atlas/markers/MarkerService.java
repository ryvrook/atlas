package dev.ryvrook.atlas.markers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.api.Marker;
import dev.ryvrook.atlas.api.MarkerEvent;
import dev.ryvrook.atlas.api.MarkerLayer;
import dev.ryvrook.atlas.api.PointMarker;
import dev.ryvrook.atlas.api.Tier;
import dev.ryvrook.atlas.auth.Viewer;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Marker layers from three sources. Plugins add layers through the API. JSON files in the markers folder
 * each hold one layer and are watched by modification time. admin.json in the same folder holds the
 * layer the /atlas marker commands edit. Every change ends in a layers event naming the changed layers.
 */
public final class MarkerService {
    public static final String FOLDER = "markers";
    public static final String ADMIN_FILE = "admin";
    /** Plugin id and layer id of the admin layer. */
    public static final String ADMIN_PLUGIN = "atlas";
    public static final String ADMIN_LAYER = "admin";
    /**
     * The live siege layer Dominion keeps through the API. The Dominion snapshot already gives every siege a
     * pin with its phase and countdown, so the layer is left out of the browser while the snapshot carries
     * sieges, and shown only when it does not.
     */
    public static final String DOMINION_SIEGES = "Ryvrook:Dominion:sieges";
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final long FLUSH_DELAY_MS = 300;

    private final Atlas atlas;
    private final Path dir;
    private final ApiImpl api;
    private final MarkerEvents events = new MarkerEvents();
    private final Map<String, MarkerLayer> apiLayers = new ConcurrentHashMap<>();
    /** Drop folder layers by file name without .json. */
    private final Map<String, FileLayer> files = new ConcurrentHashMap<>();
    private final Set<String> badNamesLogged = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> apiHidden = new ConcurrentHashMap<>();
    /** Changed layer keys per world, waiting for the next layers event. Guarded by this map. */
    private final Map<String, Set<String>> pending = new HashMap<>();
    private boolean flushScheduled;
    /** The tier and worlds each layer had at the last layers event, so viewers learn about layers that went away. */
    private final Map<String, Tier> lastTier = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> lastWorlds = new ConcurrentHashMap<>();
    private volatile boolean running;

    private static final class FileLayer {
        volatile MarkerLayer layer;
        FileTime modified;
        long size = -1;
        String error;
    }

    public MarkerService(Atlas atlas, Path dataDir) {
        this.atlas = atlas;
        this.dir = dataDir.resolve(FOLDER);
        this.api = new ApiImpl(this);
    }

    /** The Atlas this service belongs to, for the API calls that are not about markers. */
    Atlas atlas() {
        return atlas;
    }

    public Path folder() {
        return dir;
    }

    /**
     * Reads the drop folder and publishes the API. Runs the enable hooks of other plugins. Marker listeners
     * run on the scheduler from now on.
     */
    public void start(Executor scheduler) {
        if (running) {
            return;
        }
        running = true;
        events.start(scheduler);
        poll();
        api.up();
    }

    /** Withdraws the API, runs the disable hooks of other plugins and drops the marker listeners. */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        api.down();
        events.stop();
    }

    // ---- marker events ----------------------------------------------------------

    /** Registers a listener for {@link dev.ryvrook.atlas.api.AtlasApi#onMarker}. The handle removes it again. */
    public Runnable onMarker(Consumer<MarkerEvent> listener) {
        return events.add(listener);
    }

    /**
     * Hands a marker change to the listeners of other plugins. Called where the marker.add and marker.remove
     * timeline events are written. Returns at once and never runs a listener on the caller's thread.
     */
    public void fire(MarkerEvent event) {
        events.fire(event);
    }

    /** The layer name events carry for the markers players place on the in game map. */
    public static final String SHARED_LAYER = "shared";

    // ---- API layers -----------------------------------------------------------

    MarkerLayer apiLayer(String pluginId, String layerId) {
        MarkerLayer probe = ApiImpl.detached(pluginId, layerId);
        return apiLayers.computeIfAbsent(probe.key(), k -> api.attached(pluginId, layerId));
    }

    boolean removeApiLayer(String pluginId, String layerId) {
        if (pluginId == null || layerId == null) {
            return false;
        }
        MarkerLayer gone = apiLayers.remove(pluginId + ":" + layerId);
        if (gone == null) {
            return false;
        }
        markChanged(gone.key(), gone.worlds());
        return true;
    }

    /** Every layer, API layers first, then files in name order, then the admin layer. */
    public List<MarkerLayer> allLayers() {
        List<MarkerLayer> out = new ArrayList<>(apiLayers.values());
        out.sort(Comparator.comparing(MarkerLayer::key));
        List<String> names = new ArrayList<>(files.keySet());
        names.sort(null);
        MarkerLayer admin = null;
        for (String n : names) {
            MarkerLayer l = files.get(n).layer;
            if (l == null) {
                continue;
            }
            if (ADMIN_FILE.equals(n)) {
                admin = l;
            } else {
                out.add(l);
            }
        }
        if (admin != null) {
            out.add(admin);
        }
        return out;
    }

    /** True when the browser should not get this layer because Atlas draws the same things itself. */
    boolean shadowed(MarkerLayer l) {
        return DOMINION_SIEGES.equals(l.key()) && atlas.snapshotDrawsSieges();
    }

    /** Tells browsers to fetch an API layer again, as when it starts or stops being shadowed. */
    public void refreshLayer(String key) {
        MarkerLayer l = key == null ? null : apiLayers.get(key);
        if (l != null) {
            markChanged(key, l.worlds());
        }
    }

    private static String sourceOf(MarkerLayer l) {
        if (ADMIN_PLUGIN.equals(l.pluginId())) {
            return "admin";
        }
        return "file".equals(l.pluginId()) ? "file" : "api";
    }

    /** Called by API layers on every change. A null world means every world the layer is or was in. */
    void changed(MarkerLayer layer, String world) {
        markChanged(layer.key(), world == null ? layer.worlds() : Set.of(world));
    }

    // ---- hidden players ---------------------------------------------------------

    void setApiHidden(UUID player, boolean hidden, String reason) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        if (hidden) {
            String r = reason == null || reason.isBlank() ? "api" : reason.strip();
            apiHidden.put(player, r.length() > 80 ? r.substring(0, 80) : r);
        } else {
            apiHidden.remove(player);
        }
    }

    boolean isHidden(UUID player) {
        return atlas.isHiddenPlayer(player);
    }

    /** The reason another plugin gave for hiding a player, or null when no plugin hides them. */
    public String apiHiddenReason(UUID player) {
        return player == null ? null : apiHidden.get(player);
    }

    /** The players hidden through the API, as a copy. */
    public java.util.Set<UUID> apiHiddenPlayers() {
        return java.util.Set.copyOf(apiHidden.keySet());
    }

    // ---- drop folder ------------------------------------------------------------

    /**
     * Checks the markers folder. New and changed files are parsed, removed files drop their layer. A file
     * that does not parse keeps its last good layer and is logged once until it changes. Runs on the Atlas
     * scheduler every five seconds.
     */
    public synchronized void poll() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return;
        }
        Set<String> seen = new HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) {
                String file = p.getFileName().toString();
                String name = file.substring(0, file.length() - 5);
                if (!FILE_NAME.matcher(name).matches()) {
                    if (badNamesLogged.add(file)) {
                        log(Level.WARNING, "Atlas ignores the marker file %s because a name may only hold letters, digits and _ . -", file);
                    }
                    continue;
                }
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                seen.add(name);
                load(name, p);
            }
        } catch (IOException e) {
            return;
        }
        for (String name : new ArrayList<>(files.keySet())) {
            if (!seen.contains(name)) {
                FileLayer gone = files.remove(name);
                if (gone != null && gone.layer != null) {
                    markChanged(gone.layer.key(), gone.layer.worlds());
                }
            }
        }
    }

    private void load(String name, Path p) {
        FileLayer fl = files.computeIfAbsent(name, k -> new FileLayer());
        FileTime mod;
        long size;
        try {
            mod = Files.getLastModifiedTime(p);
            size = Files.size(p);
        } catch (IOException e) {
            return;
        }
        if (mod.equals(fl.modified) && size == fl.size) {
            return;
        }
        fl.modified = mod;
        fl.size = size;
        String error;
        try {
            if (size > LayerFile.MAX_BYTES) {
                throw new LayerFile.Invalid("it is larger than 2 MB");
            }
            String text = Files.readString(p, StandardCharsets.UTF_8);
            boolean admin = ADMIN_FILE.equals(name);
            MarkerLayer next = LayerFile.parse(admin ? ADMIN_PLUGIN : "file", admin ? ADMIN_LAYER : name, text);
            swap(fl, next);
            if (fl.error != null) {
                log(Level.INFO, "Atlas reads the marker file %s again", p.getFileName());
            }
            fl.error = null;
            return;
        } catch (LayerFile.Invalid e) {
            error = e.getMessage();
        } catch (IOException | RuntimeException e) {
            error = e.getClass().getSimpleName() + " " + e.getMessage();
        }
        fl.error = error;
        log(Level.WARNING, "Atlas ignores the marker file %s because %s. The last good copy stays in use and this "
                + "is logged once until the file changes", p.getFileName(), error);
    }

    private void swap(FileLayer fl, MarkerLayer next) {
        MarkerLayer prev = fl.layer;
        fl.layer = next;
        Set<String> worlds = new HashSet<>(next.worlds());
        if (prev != null) {
            worlds.addAll(prev.worlds());
        }
        markChanged(next.key(), worlds);
    }

    // ---- admin layer ------------------------------------------------------------

    /** Thrown when an admin marker change cannot be made. The message is a sentence for the command. */
    public static final class AdminProblem extends Exception {
        public AdminProblem(String message) {
            super(message);
        }
    }

    /** A fresh copy of the admin layer to change, from admin.json as last read. */
    private MarkerLayer adminCopy() throws AdminProblem {
        FileLayer fl = files.get(ADMIN_FILE);
        if (fl != null && fl.error != null) {
            throw new AdminProblem("admin.json in the markers folder has an error, " + fl.error
                    + ". Fix the file first so Atlas does not overwrite it.");
        }
        MarkerLayer cur = fl == null ? null : fl.layer;
        if (cur == null) {
            MarkerLayer fresh = ApiImpl.detached(ADMIN_PLUGIN, ADMIN_LAYER);
            fresh.label("Server markers");
            return fresh;
        }
        try {
            return LayerFile.parse(ADMIN_PLUGIN, ADMIN_LAYER, LayerFile.write(cur));
        } catch (LayerFile.Invalid e) {
            throw new AdminProblem("the admin layer could not be copied, " + e.getMessage());
        }
    }

    /** Adds or replaces a point on the admin layer and saves admin.json. */
    public synchronized void adminPoint(String world, String id, double x, double z, String label) throws AdminProblem {
        MarkerLayer copy = adminCopy();
        try {
            copy.point(world, id, x, z).label(label);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new AdminProblem(capital(e.getMessage()) + ".");
        }
        saveAdmin(copy);
    }

    /** Adds or replaces a rectangle on the admin layer and saves admin.json. */
    public synchronized void adminArea(String world, String id, double x1, double z1, double x2, double z2, String label)
            throws AdminProblem {
        if (x1 == x2 || z1 == z2) {
            throw new AdminProblem("The two corners must differ in x and in z.");
        }
        MarkerLayer copy = adminCopy();
        double minX = Math.min(x1, x2);
        double maxX = Math.max(x1, x2);
        double minZ = Math.min(z1, z2);
        double maxZ = Math.max(z1, z2);
        try {
            copy.polygon(world, id, List.of(new double[]{minX, minZ}, new double[]{maxX, minZ},
                    new double[]{maxX, maxZ}, new double[]{minX, maxZ})).label(label);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new AdminProblem(capital(e.getMessage()) + ".");
        }
        saveAdmin(copy);
    }

    /**
     * Removes a marker from the admin layer. Gives back the x and z it stood at, or the centre of an area, or
     * null when it did not exist.
     */
    public synchronized double[] adminRemove(String world, String id) throws AdminProblem {
        MarkerLayer copy = adminCopy();
        Marker<?> gone = copy.get(world, id);
        if (gone == null || !copy.remove(world, id)) {
            return null;
        }
        saveAdmin(copy);
        return centre(gone);
    }

    /** The position of a marker, or the centre of its points. 0, 0 when it has neither. */
    static double[] centre(Marker<?> m) {
        if (m instanceof PointMarker p) {
            return new double[]{p.x(), p.z()};
        }
        if (m instanceof dev.ryvrook.atlas.api.CircleMarker c) {
            return new double[]{c.x(), c.z()};
        }
        if (m instanceof dev.ryvrook.atlas.api.PolygonMarker poly && !poly.points().isEmpty()) {
            double x = 0;
            double z = 0;
            for (double[] pt : poly.points()) {
                x += pt[0];
                z += pt[1];
            }
            return new double[]{x / poly.points().size(), z / poly.points().size()};
        }
        return new double[]{0, 0};
    }

    /**
     * True when changes to this layer may go to the timeline event log. Only public layers do, so the
     * labels of a linked, faction or admin layer never reach the shared events feed.
     */
    public static boolean timelineVisible(MarkerLayer layer) {
        return layer == null || layer.tier() == Tier.PUBLIC;
    }

    /** True when admin marker changes may go to the timeline, see {@link #timelineVisible(MarkerLayer)}. */
    public boolean adminTimelineVisible() {
        FileLayer fl = files.get(ADMIN_FILE);
        return timelineVisible(fl == null ? null : fl.layer);
    }

    /** The admin markers of a world, as short lines for the command. */
    public List<String> adminList(String world) {
        FileLayer fl = files.get(ADMIN_FILE);
        MarkerLayer l = fl == null ? null : fl.layer;
        List<String> out = new ArrayList<>();
        if (l == null) {
            return out;
        }
        for (Marker<?> m : l.markers(world)) {
            String where;
            if (m instanceof PointMarker p) {
                where = "at " + Math.round(p.x()) + " " + Math.round(p.z());
            } else {
                where = "as " + m.kind();
            }
            out.add(m.id() + " " + where + (m.label() == null ? "" : ", " + m.label()));
        }
        return out;
    }

    private void saveAdmin(MarkerLayer next) throws AdminProblem {
        Path file = dir.resolve(ADMIN_FILE + ".json");
        try {
            Json.writeAtomic(file, LayerFile.write(next));
        } catch (IOException e) {
            throw new AdminProblem("Atlas could not write admin.json, " + e.getMessage() + ".");
        }
        FileLayer fl = files.computeIfAbsent(ADMIN_FILE, k -> new FileLayer());
        try {
            fl.modified = Files.getLastModifiedTime(file);
            fl.size = Files.size(file);
        } catch (IOException ignored) {
            // The next poll reads it again, which is harmless.
        }
        fl.error = null;
        swap(fl, next);
    }

    private static String capital(String s) {
        if (s == null || s.isEmpty()) {
            return "That did not work";
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- worlds -----------------------------------------------------------------

    /** Drops the API markers of a portal or instance world that went away, so its memory is freed. */
    public void forgetWorld(String world) {
        for (MarkerLayer l : apiLayers.values()) {
            l.clear(world);
        }
        lastWorlds.values().forEach(s -> s.remove(world));
        synchronized (pending) {
            pending.remove(world);
        }
    }

    // ---- payload ------------------------------------------------------------------

    /** The api/layers/{world} body {world, at, layers}. The caller checks the world and the layers setting. */
    public JsonObject layersPayload(Viewer v, String world) {
        AtlasConfig cfg = atlas.config();
        JsonObject out = new JsonObject();
        out.addProperty("world", world);
        out.addProperty("at", System.currentTimeMillis());
        List<MarkerLayer> all = allLayers();
        all.sort(Comparator.comparingInt((MarkerLayer l) -> -l.priority()).thenComparing(MarkerLayer::key));
        JsonArray list = new JsonArray();
        for (MarkerLayer l : all) {
            if (shadowed(l)) {
                continue;
            }
            JsonObject o = LayerJson.layer(v, cfg, l, world, sourceOf(l));
            if (o != null) {
                list.add(o);
            }
        }
        out.add("layers", list);
        return out;
    }

    // ---- layers event ---------------------------------------------------------------

    private void markChanged(String key, Set<String> worlds) {
        Set<String> all = new HashSet<>(worlds);
        Set<String> before = lastWorlds.get(key);
        if (before != null) {
            all.addAll(before);
        }
        if (all.isEmpty()) {
            return;
        }
        boolean schedule;
        synchronized (pending) {
            for (String w : all) {
                pending.computeIfAbsent(w, k -> new HashSet<>()).add(key);
            }
            schedule = !flushScheduled;
            flushScheduled = true;
        }
        if (schedule) {
            Executor later = CompletableFuture.delayedExecutor(FLUSH_DELAY_MS, TimeUnit.MILLISECONDS, atlas::runLater);
            later.execute(this::flush);
        }
    }

    /** Sends one layers event per world with the changed layers each viewer may see now or saw before. */
    void flush() {
        Map<String, Set<String>> batch;
        synchronized (pending) {
            batch = new LinkedHashMap<>(pending);
            pending.clear();
            flushScheduled = false;
        }
        Map<String, MarkerLayer> byKey = new HashMap<>();
        for (MarkerLayer l : allLayers()) {
            byKey.put(l.key(), l);
        }
        AtlasConfig cfg = atlas.config();
        for (Map.Entry<String, Set<String>> e : batch.entrySet()) {
            String world = e.getKey();
            List<String> keys = new ArrayList<>(e.getValue());
            keys.sort(null);
            atlas.sse().broadcast("layers", c -> true, c -> {
                Viewer v = c.viewer();
                JsonArray ids = new JsonArray();
                for (String k : keys) {
                    MarkerLayer l = byKey.get(k);
                    Tier before = lastTier.get(k);
                    if ((l != null && LayerJson.canSee(v, cfg, l.tier())) || (before != null && LayerJson.canSee(v, cfg, before))) {
                        ids.add(k);
                    }
                }
                if (ids.isEmpty()) {
                    return null;
                }
                JsonObject o = new JsonObject();
                o.addProperty("world", world);
                o.add("layers", ids);
                return Json.GSON.toJson(o);
            });
        }
        Set<String> touched = new HashSet<>();
        batch.values().forEach(touched::addAll);
        for (String k : touched) {
            MarkerLayer l = byKey.get(k);
            if (l == null) {
                lastTier.remove(k);
                lastWorlds.remove(k);
            } else {
                lastTier.put(k, l.tier());
                lastWorlds.put(k, ConcurrentHashMap.newKeySet());
                lastWorlds.get(k).addAll(l.worlds());
            }
        }
    }

    private static void log(Level level, String format, Object... args) {
        try {
            com.hypixel.hytale.logger.HytaleLogger.get("Atlas").at(level).log(String.format(Locale.ROOT, format, args));
        } catch (RuntimeException | LinkageError ignored) {
            // No logging backend, as in unit tests.
        }
    }
}
