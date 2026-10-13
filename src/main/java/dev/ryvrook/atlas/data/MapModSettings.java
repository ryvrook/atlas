package dev.ryvrook.atlas.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Server wide map settings of the BetterMap mod, read from its own files so Atlas shows what the
 * in game map shows. Only config.json and the waypoint height files are read, never written.
 *
 * <p>config.json is a flat object. The hide switches, the hidden point names, the worlds that track
 * exploration and the circular world border are used here. A key that is missing takes the value the
 * mod itself starts with. Per player files are not read, so a player who overrides a server wide
 * switch in game still gets the server wide rule on the web map.
 *
 * <p>Heights. The engine keeps player made markers without a height. The mod stores the height
 * of shared markers in Data/(world)/global_waypoint_y.json and of personal markers in
 * Data/(world)/(uuid)_waypoint_y.json, each a flat object of marker id to number.
 */
public final class MapModSettings {
    private static final List<String> DEFAULT_TRACKED_WORLDS = List.of("default", "world");
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final int HEIGHT_FILES_MAX = 256;

    /** A circular world border in block coordinates. */
    public record Border(int x, int z, int radius) {
    }

    /** The parsed settings. hiddenPoi holds normalised names. border is null when it is off. */
    public record Settings(boolean hideSpawn, boolean hideAllPoi, boolean hideUnexploredPoi, boolean hideAllWarps,
                           boolean hideUnexploredWarps, boolean hideSharedMarkers, boolean hideDeaths,
                           Set<String> hiddenPoi, List<String> trackedWorlds, Border border) {

        /** True when the mod only shows explored points on this world. */
        public boolean tracks(String world) {
            return trackedWorlds.contains(world);
        }

        /** True when the spawn marker is hidden, by its switch or by "spawn" on the hidden list. */
        public boolean spawnHidden() {
            return hideSpawn || hiddenPoi.contains("spawn");
        }

        /**
         * True when a block map marker is hidden. Its name key, id or icon equal to a hidden entry
         * hides it, and so does a hidden entry contained in the name key or icon, as the mod does for
         * block markers.
         */
        public boolean hiddenBlock(String nameKey, String id, String icon) {
            if (hiddenPoi.isEmpty()) {
                return false;
            }
            if (hiddenByName(java.util.Arrays.asList(nameKey, id, icon))) {
                return true;
            }
            String n = normalise(nameKey);
            String i = normalise(icon);
            for (String h : hiddenPoi) {
                if (n.contains(h) || i.contains(h)) {
                    return true;
                }
            }
            return false;
        }

        /** True when any of the given names, ids or image paths is on the hidden list. */
        public boolean hiddenByName(List<String> keys) {
            if (hiddenPoi.isEmpty() || keys == null) {
                return false;
            }
            for (String k : keys) {
                String n = normalise(k);
                if (!n.isEmpty() && hiddenPoi.contains(n)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Heights(long modified, long size, Map<String, Double> values) {
    }

    private volatile Path dir;
    private volatile Settings settings;
    private long seenModified = Long.MIN_VALUE;
    private long seenSize = -1;
    private final Map<Path, Heights> heights = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Heights> eldest) {
            return size() > HEIGHT_FILES_MAX;
        }
    };

    public MapModSettings(Path dir) {
        this.dir = dir;
    }

    /** The folder of the mod, resolved against the server working directory. */
    public Path dir() {
        return dir;
    }

    public Path configFile() {
        return dir.resolve("config.json");
    }

    /** Points at another folder. The next poll reads it. */
    public synchronized void setDir(Path next) {
        if (!next.equals(dir)) {
            dir = next;
            seenModified = Long.MIN_VALUE;
            seenSize = -1;
            settings = null;
            synchronized (heights) {
                heights.clear();
            }
        }
    }

    /** The settings as last read, or null when the file is absent or unreadable. */
    public Settings current() {
        return settings;
    }

    /**
     * Re-reads config.json when its modification time or size changed since the last call. Returns
     * true when the file went from absent to present.
     */
    public synchronized boolean poll() {
        Path file = configFile();
        long modified;
        long size;
        try {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            if (!a.isRegularFile()) {
                throw new IOException("not a file");
            }
            modified = a.lastModifiedTime().toMillis();
            size = a.size();
        } catch (IOException | RuntimeException e) {
            settings = null;
            seenModified = Long.MIN_VALUE;
            seenSize = -1;
            return false;
        }
        if (modified == seenModified && size == seenSize) {
            return false;
        }
        boolean wasAbsent = settings == null;
        Settings next;
        try {
            next = parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            // A half written file. Keep what was read before and try again on the next poll.
            return false;
        }
        seenModified = modified;
        seenSize = size;
        settings = next;
        return wasAbsent && next != null;
    }

    /** Parses the text of config.json, or returns null when it is not a JSON object. */
    public static Settings parse(String text) {
        JsonElement root = JsonParser.parseString(text);
        if (root == null || !root.isJsonObject()) {
            return null;
        }
        JsonObject o = root.getAsJsonObject();
        Set<String> hidden = new java.util.HashSet<>();
        for (String s : strings(o, "hiddenPoiNames")) {
            String n = normalise(s);
            if (!n.isEmpty()) {
                hidden.add(n);
            }
        }
        // A missing or null list keeps the mod's default, an empty list tracks no world.
        JsonElement worlds = o.get("allowedWorlds");
        List<String> tracked = worlds != null && worlds.isJsonArray() ? strings(o, "allowedWorlds") : DEFAULT_TRACKED_WORLDS;
        Border border = null;
        if (bool(o, "worldBorderEnabled", false)) {
            int radius = integer(o, "worldBorderRadius", 5000);
            if (radius > 0) {
                border = new Border(integer(o, "worldBorderOffsetX", 0), integer(o, "worldBorderOffsetZ", 0), radius);
            }
        }
        return new Settings(
                bool(o, "hideSpawnOnMap", false),
                bool(o, "hideAllPoiOnMap", false),
                bool(o, "hideUnexploredPoiOnMap", true),
                bool(o, "hideAllWarpsOnMap", false),
                bool(o, "hideUnexploredWarpsOnMap", true),
                bool(o, "hideGlobalWaypointsOnMap", false),
                bool(o, "hideDeathMarkerOnMap", false),
                Set.copyOf(hidden), List.copyOf(tracked), border);
    }

    /** Lower case without markup or outer spaces, the form hidden names are compared in. */
    public static String normalise(String s) {
        if (s == null) {
            return "";
        }
        return TAGS.matcher(s).replaceAll("").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : def;
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static int integer(JsonObject o, String key, int def) {
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonPrimitive() ? (int) Math.round(e.getAsDouble()) : def;
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = o.get(key);
        if (e != null && e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (x != null && x.isJsonPrimitive()) {
                    out.add(x.getAsString());
                }
            }
        }
        return out;
    }

    // ---- heights ---------------------------------------------------------------

    /** Heights of the shared markers of a world, by marker id. Empty when there is no file. */
    public Map<String, Double> sharedHeights(String world) {
        Path worldDir = worldDir(world);
        return worldDir == null ? Map.of() : heightsOf(worldDir.resolve("global_waypoint_y.json"));
    }

    /** Heights of the personal markers of one player in a world, by marker id. */
    public Map<String, Double> personalHeights(String world, UUID player) {
        Path worldDir = worldDir(world);
        return worldDir == null || player == null ? Map.of() : heightsOf(worldDir.resolve(player + "_waypoint_y.json"));
    }

    private Path worldDir(String world) {
        if (world == null || world.isEmpty() || world.length() > 100 || world.contains("/") || world.contains("\\")
                || world.contains("..") || world.indexOf('\0') >= 0) {
            return null;
        }
        return dir.resolve("Data").resolve(world);
    }

    private Map<String, Double> heightsOf(Path file) {
        long modified;
        long size;
        try {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            if (!a.isRegularFile() || a.size() > 4_000_000) {
                return Map.of();
            }
            modified = a.lastModifiedTime().toMillis();
            size = a.size();
        } catch (IOException | RuntimeException e) {
            synchronized (heights) {
                heights.remove(file);
            }
            return Map.of();
        }
        synchronized (heights) {
            Heights h = heights.get(file);
            if (h != null && h.modified() == modified && h.size() == size) {
                return h.values();
            }
        }
        Map<String, Double> values = new HashMap<>();
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (root != null && root.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                    JsonElement v = e.getValue();
                    if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                        double d = v.getAsDouble();
                        if (Double.isFinite(d)) {
                            values.put(e.getKey(), d);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
        Map<String, Double> frozen = Map.copyOf(values);
        synchronized (heights) {
            heights.put(file, new Heights(modified, size, frozen));
        }
        return frozen;
    }
}
