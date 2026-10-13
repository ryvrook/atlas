package dev.ryvrook.atlas.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.ryvrook.atlas.util.Json;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Settings read from atlas.json in the plugin data directory. Missing keys fall back to the
 * defaults below and are written to the file, which is otherwise left untouched. A file that does
 * not parse, holds a value of the wrong type or a value out of range is rejected as a whole with
 * {@link InvalidConfigException}, and the caller keeps the web server down until it is fixed.
 */
public final class AtlasConfig {
    public static final String FILE_NAME = "atlas.json";
    /** Shortest adminToken accepted. A shorter one is replaced with a new random token. */
    public static final int MIN_ADMIN_TOKEN = 24;
    /** The session cookie name since 1.1.0, the same name Chronicle uses. */
    public static final String DEFAULT_COOKIE = "ryvrook_session";
    /** The session cookie name of 1.0.0 and older, still accepted in 1.1.0. */
    public static final String LEGACY_COOKIE = "atlas_session";
    public static final String DEFAULT_CHRONICLE_EXPORT = "mods/Ryvrook_Chronicle/export/stats.json";
    private static final java.util.regex.Pattern COOKIE_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,32}");
    private static final java.util.regex.Pattern COOKIE_DOMAIN = java.util.regex.Pattern.compile(
            "\\.?[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");

    public String bind = "0.0.0.0";
    public int port = 8123;
    public String publicTitle = "Atlas";
    /** Address printed by /atlas and sent to browsers. Empty means the owner has not set one. */
    public String publicUrl = "";
    /** Prefix stripped from request paths when a reverse proxy mounts the map under a sub path, such as /map. */
    public String basePath = "";
    /** Name of the session cookie, shared with Chronicle. 1 to 32 letters, digits or underscores. */
    public String cookieName = DEFAULT_COOKIE;
    /** Domain attribute of the session cookie. Empty sends none, so the cookie stays with this host. */
    public String cookieDomain = "";
    public boolean showPlayers = true;
    public boolean showPlayerHealth = false;
    public boolean hidePlayersInOwnTerritory = true;
    /** Players below this height count as underground and non admins get no position for them. */
    public int hideBelowY = 0;
    public double playerUpdateHz = 1.0;
    /** List portal and instance worlds in the world picker for non admins. */
    public boolean showPortalWorlds = false;
    /** Path of the Feats leaderboard export, relative to the server working directory. */
    public String featsExport = "mods/Ryvrook_Feats/export/feats.json";
    /** Path of the Chronicle stats export, relative to the server working directory. */
    public String chronicleExport = DEFAULT_CHRONICLE_EXPORT;
    /** Address of the Chronicle dashboard linked from the Chronicle tab. Empty hides the link. */
    public String chronicleUrl = "";
    /** Event stream connections allowed per address. */
    public int sseClientsPerIp = 8;
    /** API requests per minute and address. 0 means unlimited. */
    public int apiRequestsPerMinute = 600;
    /** Tile requests per minute and address. 0 means unlimited. */
    public int tileRequestsPerMinute = 3000;
    /** Chunk images the tile renderer may read per minute across all requests. 0 means unlimited. */
    public int chunkImagesPerMinute = 1200;
    /** Hours after which a disk tile is checked against its chunk images again. 0 disables the check. */
    public int tileMaxAgeHours = 24;
    /** Render explored tiles that have no disk tile yet while nobody is requesting tiles. */
    public boolean preRenderIdle = true;
    /** Show who placed a player made marker. When false the creator is left out everywhere. */
    public boolean showMarkerAuthors = true;
    /** Hide a player from non admins while they crouch. */
    public boolean hidePlayersCrouching = false;
    /** Hide a player from non admins while they are invisible. */
    public boolean hidePlayersInvisible = true;
    public int tileCacheMb = 64;
    public String adminToken = "";
    public String dominionSnapshot = "mods/Ryvrook_Dominion/export/snapshot.json";
    /** Apply the server wide hide rules, world border and marker heights of the BetterMap mod when its files exist. */
    public boolean honorBetterMap = true;
    /** Folder of the BetterMap mod, relative to the server working directory. */
    public String betterMapDir = "mods/BetterMap";
    /**
     * Reverse proxies whose X-Forwarded-For header is believed, as addresses or CIDR ranges.
     * Empty means the direct peer address is always the client.
     */
    public List<String> trustedProxies = new ArrayList<>();
    public Map<String, String> visibility = defaultVisibility();
    /** World opened first in the browser. Empty means the server's default world. */
    public String defaultWorld = "";
    /** Settings per world, keyed by world name. A world not listed uses the defaults of {@link WorldSettings}. */
    public Map<String, WorldSettings> worlds = new LinkedHashMap<>();
    /** Daily snapshots of tiles, stats and events. */
    public Timeline timeline = new Timeline();
    /** Serve isometric views of an area under api/iso. */
    public boolean isoEnabled = true;
    /** Memory for rendered isometric views in MB, at least 4. */
    public int isoCacheMb = 64;
    /** Disk for rendered isometric views in MB. 0 keeps no copy on disk. */
    public int isoDiskMb = 512;
    /** Largest radius in chunks a viewer may ask for, 1 to 16. */
    public int isoMaxRadius = 16;
    /** Isometric renders a single address may start per minute. 0 means unlimited. */
    public int isoRequestsPerMinute = 20;
    /** Browser views one address may hold in the render queue at once, 1 to 16. */
    public int isoViewsPerAddress = 4;
    /** Large browser views one address may hold waiting in the render queue at once, 1 to 4. */
    public int isoLargeViewsPerAddress = 2;
    /** Key places iso.keyPlaces may hold, 1 to 10. */
    public int isoKeyPlaceLimit = 3;
    /** Isometric views taken with the daily timeline snapshot. */
    public Iso iso = new Iso();
    /** True when load replaced a hand set adminToken that was too short. Never written to the file. */
    public transient boolean adminTokenReplaced;
    /** Keys load added to the file, empty when the file already listed every option. Never written. */
    public transient List<String> addedKeys = new ArrayList<>();
    /** True when load found no atlas.json and wrote a new one. Never written. */
    public transient boolean created;
    /** Keys in the file that Atlas does not know, kept as they are. Never written by Atlas. */
    public transient List<String> unknownKeys = new ArrayList<>();
    /** Top level keys Atlas does not know, with their values as the owner wrote them. Written back unchanged. */
    transient JsonObject unknownValues = new JsonObject();

    /** The timeline object of atlas.json. */
    public static final class Timeline {
        /** Take daily snapshots and keep the event log. */
        public boolean enabled = true;
        /** Server local hour of the first snapshot of the day, 0 to 23. */
        public int hour = 4;
        /** Snapshots per day at evenly spaced hours from hour, one of 1, 2, 3, 4, 6, 8, 12 or 24. */
        public int perDay = 4;
        /** Days of snapshots kept. 0 keeps only the newest day. */
        public int retainDays = 90;
        /** Disk the timeline may use in MB. 0 means no cap. */
        public int maxDiskMb = 2048;
        /** Write the daily event log. */
        public boolean events = true;

        static final List<String> KEYS = List.of("enabled", "hour", "perDay", "retainDays", "maxDiskMb", "events");
    }

    /** The iso object of atlas.json. */
    public static final class Iso {
        /** Areas drawn into the timeline with the first snapshot of each day of their world. */
        public List<IsoArea> snapshots = new ArrayList<>();
        /** Also draw every admin marker of the world, when the admin marker layer is public. */
        public boolean snapshotAdminMarkers = false;
        /**
         * The curated places drawn from all four directions with the first snapshot of every day. Replaced as a
         * whole when a command or the Admin tab changes it, never changed in place.
         */
        public volatile List<KeyPlace> keyPlaces = new ArrayList<>();

        static final List<String> KEYS = List.of("snapshots", "snapshotAdminMarkers", "keyPlaces");
    }

    /** One entry of iso.keyPlaces. x and z are blocks, radius is in chunks, name is shown in the browser. */
    public static final class KeyPlace {
        public static final int DEFAULT_RADIUS = 6;
        public static final int MAX_RADIUS = 16;
        public static final int MAX_NAME = 60;

        public String world;
        public int x;
        public int z;
        public int radius = DEFAULT_RADIUS;
        public String label;
        public String name = "";

        static final List<String> KEYS = List.of("world", "x", "z", "radius", "label", "name");

        public KeyPlace() {
        }

        public KeyPlace(String world, int x, int z, int radius, String label, String name) {
            this.world = world;
            this.x = x;
            this.z = z;
            this.radius = radius;
            this.label = label;
            this.name = name == null ? "" : name;
        }

        /** The name shown in the browser, the label when no name was given. */
        public String displayName() {
            return name == null || name.isBlank() ? label : name;
        }
    }

    /**
     * Checks a list of key places and returns the first problem as one sentence, or null when the
     * list is fine. Labels are unique across every world, since commands and routes name a place
     * by its label alone.
     */
    public static String keyPlaceProblem(List<KeyPlace> places, int limit) {
        if (places.size() > limit) {
            return "iso.keyPlaces holds " + places.size() + " places but isoKeyPlaceLimit allows " + limit;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < places.size(); i++) {
            KeyPlace p = places.get(i);
            String key = "iso.keyPlaces[" + i + "]";
            if (p == null) {
                return key + " must be an object";
            }
            if (p.world == null || p.world.isBlank()) {
                return key + ".world must name a world";
            }
            if (!validIsoLabel(p.label)) {
                return key + ".label must be 1 to 40 lower case letters, digits, dashes or underscores";
            }
            if (!seen.add(p.label)) {
                return key + ".label " + p.label + " is used twice";
            }
            if (p.radius < 1 || p.radius > KeyPlace.MAX_RADIUS) {
                return key + ".radius is " + p.radius + " but must be between 1 and " + KeyPlace.MAX_RADIUS;
            }
            if (Math.abs((long) p.x) > 30_000_000L || Math.abs((long) p.z) > 30_000_000L) {
                return key + " lies outside the world";
            }
            if (p.name == null) {
                p.name = "";
            }
            if (!validKeyPlaceName(p.name)) {
                return key + ".name must be at most " + KeyPlace.MAX_NAME + " characters without control characters";
            }
        }
        return null;
    }

    /** A display name of a key place, at most 60 characters and no control characters. Empty is allowed. */
    public static boolean validKeyPlaceName(String s) {
        if (s == null || s.length() > KeyPlace.MAX_NAME) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** One area of iso.snapshots. x and z are blocks, radius is in chunks. */
    public static final class IsoArea {
        public String world;
        public int x;
        public int z;
        public int radius = 4;
        public String label;

        static final List<String> KEYS = List.of("world", "x", "z", "radius", "label");
    }

    /** A label of iso.snapshots, lower case letters, digits, dashes and underscores, 1 to 40 long. */
    public static boolean validIsoLabel(String s) {
        return s != null && s.matches("[a-z0-9_-]{1,40}");
    }

    /** One entry of the worlds object of atlas.json. */
    public static final class WorldSettings {
        /** False hides the world everywhere. It answers 404 and renders nothing. */
        public boolean enabled = true;
        /** Name shown in the browser, null for the name Atlas derives. */
        public String displayName = null;
        /** Hide the world from non admins. Null follows showPortalWorlds for portal and instance worlds. */
        public Boolean hidden = null;
        /** Only this circle is rendered and served, null for no limit. */
        public RenderLimit renderLimit = null;

        static final List<String> KEYS = List.of("enabled", "displayName", "hidden", "renderLimit");

        /** True when the block lies inside the render limit or there is none. */
        public boolean inside(double x, double z) {
            return renderLimit == null || renderLimit.contains(x, z);
        }
    }

    /** A circle in world blocks. */
    public static final class RenderLimit {
        public double x;
        public double z;
        public double radius;

        public boolean contains(double px, double pz) {
            double dx = px - x;
            double dz = pz - z;
            return dx * dx + dz * dz <= radius * radius;
        }

        /** True when any part of the square from min to max (inclusive block bounds) lies inside the circle. */
        public boolean touches(double minX, double minZ, double maxX, double maxZ) {
            double cx = Math.max(minX, Math.min(x, maxX));
            double cz = Math.max(minZ, Math.min(z, maxZ));
            return contains(cx, cz);
        }
    }

    private static final WorldSettings DEFAULT_WORLD = new WorldSettings();

    /** Settings of a world, the defaults when it is not listed. Never null. */
    public WorldSettings world(String name) {
        WorldSettings w = name == null || worlds == null ? null : worlds.get(name);
        return w == null ? DEFAULT_WORLD : w;
    }

    /** False when the worlds object disables this world. */
    public boolean worldEnabled(String name) {
        return world(name).enabled;
    }

    /** Whether daily snapshots and the event log run. */
    public boolean timelineEnabled() {
        return timeline.enabled;
    }

    /** Server local hour of the first snapshot of the day, 0 to 23. */
    public int timelineHour() {
        return timeline.hour;
    }

    /** Snapshots per day, a divisor of 24. */
    public int timelinePerDay() {
        return timeline.perDay;
    }

    /** Days of snapshots kept, 0 or more. */
    public int timelineRetainDays() {
        return timeline.retainDays;
    }

    /** Disk the timeline may use in MB. 0 means no cap. */
    public int timelineMaxDiskMb() {
        return timeline.maxDiskMb;
    }

    /** Whether the daily event log is written. */
    public boolean timelineEvents() {
        return timeline.events;
    }

    /** Whether api/iso serves isometric views. */
    public boolean isoEnabled() {
        return isoEnabled;
    }

    /** Memory for rendered isometric views in MB. */
    public int isoCacheMb() {
        return isoCacheMb;
    }

    /** Disk for rendered isometric views in MB, 0 for none. */
    public int isoDiskMb() {
        return isoDiskMb;
    }

    /** Largest radius in chunks, 1 to 16. */
    public int isoMaxRadius() {
        return isoMaxRadius;
    }

    /** Isometric renders an address may start per minute. 0 means unlimited. */
    public int isoRequestsPerMinute() {
        return isoRequestsPerMinute;
    }

    /** Browser views one address may hold in the render queue at once. */
    public int isoViewsPerAddress() {
        return isoViewsPerAddress;
    }

    /** Large browser views one address may hold waiting in the render queue at once. */
    public int isoLargeViewsPerAddress() {
        return isoLargeViewsPerAddress;
    }

    /** Key places iso.keyPlaces may hold. */
    public int isoKeyPlaceLimit() {
        return isoKeyPlaceLimit;
    }

    /** The key places, never null. The list is not changed in place, a change replaces it. */
    public List<KeyPlace> isoKeyPlaces() {
        List<KeyPlace> l = iso == null ? null : iso.keyPlaces;
        return l == null ? List.of() : l;
    }

    /** The configured daily isometric areas, never null. */
    public List<IsoArea> isoSnapshots() {
        return iso == null || iso.snapshots == null ? List.of() : iso.snapshots;
    }

    /** Whether the daily snapshot also draws every admin marker. */
    public boolean isoSnapshotAdminMarkers() {
        return iso != null && iso.snapshotAdminMarkers;
    }

    /** Thrown when atlas.json cannot be used. The message says why in one line. */
    public static final class InvalidConfigException extends IOException {
        public InvalidConfigException(String message) {
            super(message);
        }

        public InvalidConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static Map<String, String> defaultVisibility() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("terrain", "public");
        v.put("territories", "public");
        v.put("players", "linked");
        v.put("points", "faction");
        v.put("zones", "public");
        v.put("heat", "admin");
        v.put("paths", "admin");
        v.put("chat", "admin");
        v.put("factions", "public");
        v.put("stats", "public");
        v.put("feed", "linked");
        v.put("events", "public");
        v.put("nations", "public");
        v.put("markers", "public");
        v.put("worldevents", "public");
        v.put("kills", "public");
        v.put("leaderboard", "public");
        v.put("lastSeen", "linked");
        v.put("timeline", "public");
        v.put("layers", "public");
        v.put("iso", "public");
        return v;
    }

    /** Visibility level of a layer, with unknown layers treated as admin only. */
    public Visibility visibilityOf(String layer) {
        String raw = visibility == null ? null : visibility.get(layer);
        if (raw == null) {
            raw = defaultVisibility().getOrDefault(layer, "admin");
        }
        return Visibility.parse(raw);
    }

    public double clampedHz() {
        if (Double.isNaN(playerUpdateHz) || playerUpdateHz <= 0) {
            return 1.0;
        }
        return Math.min(10.0, Math.max(0.1, playerUpdateHz));
    }

    /** Chunk images the renderer may read per minute across all requests. 0 means unlimited. */
    public int chunkImagesPerMinute() {
        return chunkImagesPerMinute;
    }

    /** Hours after which a disk tile is checked against its chunk images again. 0 disables the check. */
    public int tileMaxAgeHours() {
        return tileMaxAgeHours;
    }

    /** API requests allowed per minute and address. 0 means unlimited. */
    public int apiRequestsPerMinute() {
        return apiRequestsPerMinute;
    }

    /** Tile requests allowed per minute and address. 0 means unlimited. */
    public int tileRequestsPerMinute() {
        return tileRequestsPerMinute;
    }

    /** Whether idle time renders explored tiles that have no disk tile yet. */
    public boolean preRenderIdle() {
        return preRenderIdle;
    }

    /**
     * Loads the file and fills gaps with defaults. The file is written only when it is missing,
     * when keys were added, or when the adminToken was generated or replaced.
     */
    public static AtlasConfig load(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            AtlasConfig cfg = parse(null);
            cfg.created = true;
            cfg.save(dataDir);
            return cfg;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        AtlasConfig cfg = parse(text);
        if (!cfg.addedKeys.isEmpty()) {
            cfg.save(dataDir);
        }
        return cfg;
    }

    /**
     * Parses the text of atlas.json without touching the disk. Null or blank text gives the
     * defaults with a new adminToken. Records the keys that were added in {@link #addedKeys}.
     */
    public static AtlasConfig parse(String text) throws InvalidConfigException {
        AtlasConfig cfg = new AtlasConfig();
        List<String> added = new ArrayList<>();
        JsonObject root = new JsonObject();
        if (text != null && !text.isBlank()) {
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(text);
            } catch (JsonParseException e) {
                throw new InvalidConfigException("atlas.json is not valid JSON (" + rootMessage(e) + ")", e);
            }
            if (!parsed.isJsonObject()) {
                throw new InvalidConfigException("atlas.json must hold one JSON object");
            }
            root = parsed.getAsJsonObject();
        }
        for (Field f : fields()) {
            JsonElement v = root.get(f.getName());
            if (v == null) {
                added.add(f.getName());
                continue;
            }
            checkType(f, v);
            if (f.getName().equals("timeline") && v.isJsonObject()) {
                for (String k : Timeline.KEYS) {
                    if (!v.getAsJsonObject().has(k)) {
                        added.add("timeline." + k);
                    }
                }
                for (String k : v.getAsJsonObject().keySet()) {
                    if (!Timeline.KEYS.contains(k)) {
                        cfg.unknownKeys.add("timeline." + k);
                    }
                }
            } else if (f.getName().equals("iso") && v.isJsonObject()) {
                for (String k : Iso.KEYS) {
                    if (!v.getAsJsonObject().has(k)) {
                        added.add("iso." + k);
                    }
                }
                for (String k : v.getAsJsonObject().keySet()) {
                    if (!Iso.KEYS.contains(k)) {
                        cfg.unknownKeys.add("iso." + k);
                    }
                }
                JsonElement areas = v.getAsJsonObject().get("snapshots");
                if (areas != null && areas.isJsonArray()) {
                    int n = 0;
                    for (JsonElement a : areas.getAsJsonArray()) {
                        for (String k : a.getAsJsonObject().keySet()) {
                            if (!IsoArea.KEYS.contains(k)) {
                                cfg.unknownKeys.add("iso.snapshots[" + n + "]." + k);
                            }
                        }
                        n++;
                    }
                }
                JsonElement places = v.getAsJsonObject().get("keyPlaces");
                if (places != null && places.isJsonArray()) {
                    int n = 0;
                    for (JsonElement a : places.getAsJsonArray()) {
                        for (String k : a.getAsJsonObject().keySet()) {
                            if (!KeyPlace.KEYS.contains(k)) {
                                cfg.unknownKeys.add("iso.keyPlaces[" + n + "]." + k);
                            }
                        }
                        n++;
                    }
                }
            } else if (f.getName().equals("worlds") && v.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet()) {
                    for (String k : e.getValue().getAsJsonObject().keySet()) {
                        if (!WorldSettings.KEYS.contains(k)) {
                            cfg.unknownKeys.add("worlds." + e.getKey() + "." + k);
                        }
                    }
                    JsonElement limit = e.getValue().getAsJsonObject().get("renderLimit");
                    if (limit != null && limit.isJsonObject()) {
                        for (String k : limit.getAsJsonObject().keySet()) {
                            if (!List.of("x", "z", "radius").contains(k)) {
                                cfg.unknownKeys.add("worlds." + e.getKey() + ".renderLimit." + k);
                            }
                        }
                    }
                }
            }
        }
        for (String key : root.keySet()) {
            if (!knownKey(key)) {
                cfg.unknownKeys.add(key);
                cfg.unknownValues.add(key, root.get(key).deepCopy());
            }
        }
        AtlasConfig read;
        try {
            read = Json.GSON.fromJson(root, AtlasConfig.class);
        } catch (JsonParseException | NumberFormatException e) {
            throw new InvalidConfigException("atlas.json has a value of the wrong type (" + rootMessage(e) + ")", e);
        }
        if (read != null) {
            read.addedKeys = new ArrayList<>();
            read.unknownKeys = cfg.unknownKeys;
            read.unknownValues = cfg.unknownValues;
            cfg = read;
        }
        Map<String, String> merged = defaultVisibility();
        if (cfg.visibility != null) {
            for (Map.Entry<String, String> e : cfg.visibility.entrySet()) {
                Visibility level = Visibility.parseStrict(e.getValue());
                if (level == null) {
                    throw new InvalidConfigException("visibility." + e.getKey() + " is \"" + e.getValue()
                            + "\" but must be public, linked, faction or admin");
                }
                merged.put(e.getKey(), level.name().toLowerCase(Locale.ROOT));
                if (!defaultVisibility().containsKey(e.getKey())) {
                    // A misspelt layer would leave the layer meant at its default, so it is reported.
                    cfg.unknownKeys.add("visibility." + e.getKey());
                }
            }
            for (String layer : defaultVisibility().keySet()) {
                if (!cfg.visibility.containsKey(layer) && !added.contains("visibility")) {
                    added.add("visibility." + layer);
                }
            }
        }
        cfg.visibility = merged;
        cfg.validate();
        if (cfg.bind == null || cfg.bind.isBlank()) {
            cfg.bind = "0.0.0.0";
        }
        if (cfg.publicTitle == null) {
            cfg.publicTitle = "Atlas";
        }
        if (cfg.publicUrl == null) {
            cfg.publicUrl = "";
        }
        if (cfg.dominionSnapshot == null || cfg.dominionSnapshot.isBlank()) {
            cfg.dominionSnapshot = "mods/Ryvrook_Dominion/export/snapshot.json";
        }
        if (cfg.basePath != null && (cfg.basePath.contains("..") || cfg.basePath.contains("?") || cfg.basePath.contains("#"))) {
            throw new InvalidConfigException("basePath must be a plain path such as /map");
        }
        cfg.basePath = normalizeBasePath(cfg.basePath);
        if (cfg.betterMapDir == null || cfg.betterMapDir.isBlank()) {
            cfg.betterMapDir = "mods/BetterMap";
        }
        if (cfg.featsExport == null || cfg.featsExport.isBlank()) {
            cfg.featsExport = "mods/Ryvrook_Feats/export/feats.json";
        }
        if (cfg.chronicleExport == null || cfg.chronicleExport.isBlank()) {
            cfg.chronicleExport = DEFAULT_CHRONICLE_EXPORT;
        }
        cfg.chronicleUrl = cfg.chronicleUrl == null ? "" : cfg.chronicleUrl.trim();
        if (!cfg.chronicleUrl.isEmpty() && !cfg.chronicleUrl.matches("(?i)(https?://|/)[^\\s\"'<>]*")) {
            throw new InvalidConfigException("chronicleUrl must be empty or an address that starts with http://, https:// or /");
        }
        cfg.cookieName = cfg.cookieName == null || cfg.cookieName.isBlank() ? DEFAULT_COOKIE : cfg.cookieName.trim();
        if (!COOKIE_NAME.matcher(cfg.cookieName).matches()) {
            throw new InvalidConfigException("cookieName must be 1 to 32 letters, digits or underscores");
        }
        cfg.cookieDomain = cfg.cookieDomain == null ? "" : cfg.cookieDomain.trim();
        if (!cfg.cookieDomain.isEmpty() && (cfg.cookieDomain.length() > 253 || !COOKIE_DOMAIN.matcher(cfg.cookieDomain).matches())) {
            throw new InvalidConfigException("cookieDomain must be empty or a host name such as example.com");
        }
        if (cfg.trustedProxies == null) {
            cfg.trustedProxies = new ArrayList<>();
        }
        if (cfg.defaultWorld == null) {
            cfg.defaultWorld = "";
        }
        cfg.defaultWorld = cfg.defaultWorld.trim();
        if (cfg.adminToken == null || cfg.adminToken.isBlank()) {
            cfg.adminToken = randomToken();
            if (!added.contains("adminToken")) {
                added.add("adminToken");
            }
        } else if (cfg.adminToken.trim().length() < MIN_ADMIN_TOKEN) {
            cfg.adminToken = randomToken();
            cfg.adminTokenReplaced = true;
            added.add("adminToken");
        }
        cfg.addedKeys = added;
        return cfg;
    }

    /** Rejects values that are out of range. */
    private void validate() throws InvalidConfigException {
        if (port < 1 || port > 65535) {
            throw new InvalidConfigException("port is " + port + " but must be between 1 and 65535");
        }
        if (Double.isNaN(playerUpdateHz) || Double.isInfinite(playerUpdateHz) || playerUpdateHz <= 0) {
            throw new InvalidConfigException("playerUpdateHz must be a number above 0");
        }
        if (tileCacheMb < 4) {
            throw new InvalidConfigException("tileCacheMb is " + tileCacheMb + " but must be at least 4");
        }
        if (sseClientsPerIp < 1) {
            throw new InvalidConfigException("sseClientsPerIp is " + sseClientsPerIp + " but must be at least 1");
        }
        nonNegative("apiRequestsPerMinute", apiRequestsPerMinute);
        nonNegative("tileRequestsPerMinute", tileRequestsPerMinute);
        nonNegative("chunkImagesPerMinute", chunkImagesPerMinute);
        nonNegative("tileMaxAgeHours", tileMaxAgeHours);
        if (timeline == null) {
            throw new InvalidConfigException("timeline must be an object");
        }
        if (timeline.hour < 0 || timeline.hour > 23) {
            throw new InvalidConfigException("timeline.hour is " + timeline.hour + " but must be between 0 and 23");
        }
        if (!dev.ryvrook.atlas.timeline.Slots.PER_DAY.contains(timeline.perDay)) {
            throw new InvalidConfigException("timeline.perDay is " + timeline.perDay
                    + " but must be one of 1, 2, 3, 4, 6, 8, 12 or 24");
        }
        nonNegative("timeline.retainDays", timeline.retainDays);
        nonNegative("timeline.maxDiskMb", timeline.maxDiskMb);
        validateIso();
        if (worlds == null) {
            worlds = new LinkedHashMap<>();
        }
        for (Map.Entry<String, WorldSettings> e : worlds.entrySet()) {
            String key = "worlds." + e.getKey();
            if (e.getKey().isBlank()) {
                throw new InvalidConfigException("worlds holds an entry with an empty name");
            }
            if (e.getValue() == null) {
                throw new InvalidConfigException(key + " must be an object");
            }
            RenderLimit r = e.getValue().renderLimit;
            if (r != null && !(r.radius > 0 && Double.isFinite(r.radius))) {
                throw new InvalidConfigException(key + ".renderLimit.radius must be a number above 0");
            }
        }
        if (trustedProxies != null) {
            for (String p : trustedProxies) {
                if (p == null) {
                    throw new InvalidConfigException("trustedProxies holds a null entry");
                }
            }
        }
    }

    /** Rejects iso values that are out of range, and snapshot areas that cannot be drawn. */
    private void validateIso() throws InvalidConfigException {
        if (isoCacheMb < 4) {
            throw new InvalidConfigException("isoCacheMb is " + isoCacheMb + " but must be at least 4");
        }
        nonNegative("isoDiskMb", isoDiskMb);
        if (isoMaxRadius < 1 || isoMaxRadius > 16) {
            throw new InvalidConfigException("isoMaxRadius is " + isoMaxRadius + " but must be between 1 and 16");
        }
        nonNegative("isoRequestsPerMinute", isoRequestsPerMinute);
        if (isoViewsPerAddress < 1 || isoViewsPerAddress > 16) {
            throw new InvalidConfigException("isoViewsPerAddress is " + isoViewsPerAddress + " but must be between 1 and 16");
        }
        if (isoLargeViewsPerAddress < 1 || isoLargeViewsPerAddress > 4) {
            throw new InvalidConfigException("isoLargeViewsPerAddress is " + isoLargeViewsPerAddress
                    + " but must be between 1 and 4");
        }
        if (isoKeyPlaceLimit < 1 || isoKeyPlaceLimit > 10) {
            throw new InvalidConfigException("isoKeyPlaceLimit is " + isoKeyPlaceLimit + " but must be between 1 and 10");
        }
        if (iso == null) {
            throw new InvalidConfigException("iso must be an object");
        }
        if (iso.snapshots == null) {
            iso.snapshots = new ArrayList<>();
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < iso.snapshots.size(); i++) {
            IsoArea a = iso.snapshots.get(i);
            String key = "iso.snapshots[" + i + "]";
            if (a == null) {
                throw new InvalidConfigException(key + " must be an object");
            }
            if (a.world == null || a.world.isBlank()) {
                throw new InvalidConfigException(key + ".world must name a world");
            }
            if (!validIsoLabel(a.label)) {
                throw new InvalidConfigException(key + ".label must be 1 to 40 lower case letters, digits, dashes or underscores");
            }
            if (!seen.add(a.world + "|" + a.label)) {
                throw new InvalidConfigException(key + ".label " + a.label + " is used twice for the world " + a.world);
            }
            if (a.radius < 1 || a.radius > isoMaxRadius) {
                throw new InvalidConfigException(key + ".radius is " + a.radius + " but must be between 1 and isoMaxRadius ("
                        + isoMaxRadius + ")");
            }
            if (Math.abs((long) a.x) > 30_000_000L || Math.abs((long) a.z) > 30_000_000L) {
                throw new InvalidConfigException(key + " lies outside the world");
            }
        }
        if (iso.keyPlaces == null) {
            iso.keyPlaces = new ArrayList<>();
        }
        String problem = keyPlaceProblem(iso.keyPlaces, isoKeyPlaceLimit);
        if (problem != null) {
            throw new InvalidConfigException(problem);
        }
    }

    private static void nonNegative(String key, int value) throws InvalidConfigException {
        if (value < 0) {
            throw new InvalidConfigException(key + " is " + value + " but must be 0 or more");
        }
    }

    /** Checks that a JSON value fits the type of the field it fills. Null is allowed for strings only. */
    private static void checkType(Field f, JsonElement v) throws InvalidConfigException {
        Class<?> t = f.getType();
        String key = f.getName();
        if (t == Timeline.class) {
            if (!v.isJsonObject()) {
                throw wrongType(key, "an object");
            }
            JsonObject o = v.getAsJsonObject();
            checkValue(o, "enabled", "timeline.enabled", boolean.class, false);
            checkValue(o, "hour", "timeline.hour", int.class, false);
            checkValue(o, "perDay", "timeline.perDay", int.class, false);
            checkValue(o, "retainDays", "timeline.retainDays", int.class, false);
            checkValue(o, "maxDiskMb", "timeline.maxDiskMb", int.class, false);
            checkValue(o, "events", "timeline.events", boolean.class, false);
            return;
        }
        if (t == Iso.class) {
            if (!v.isJsonObject()) {
                throw wrongType(key, "an object");
            }
            JsonObject o = v.getAsJsonObject();
            checkValue(o, "snapshotAdminMarkers", "iso.snapshotAdminMarkers", boolean.class, false);
            JsonElement areas = o.get("snapshots");
            if (areas != null && !areas.isJsonArray()) {
                throw wrongType("iso.snapshots", "a list of objects with world, x, z, radius and label");
            }
            int n = 0;
            for (JsonElement a : areas == null ? new com.google.gson.JsonArray() : areas.getAsJsonArray()) {
                String ak = "iso.snapshots[" + n++ + "]";
                if (!a.isJsonObject()) {
                    throw wrongType(ak, "an object with world, x, z, radius and label");
                }
                JsonObject ao = a.getAsJsonObject();
                for (String c : List.of("world", "x", "z", "label")) {
                    if (!ao.has(c)) {
                        throw new InvalidConfigException(ak + " needs world, x, z and label");
                    }
                }
                checkValue(ao, "world", ak + ".world", String.class, false);
                checkValue(ao, "label", ak + ".label", String.class, false);
                checkValue(ao, "x", ak + ".x", int.class, false);
                checkValue(ao, "z", ak + ".z", int.class, false);
                checkValue(ao, "radius", ak + ".radius", int.class, false);
            }
            JsonElement places = o.get("keyPlaces");
            if (places == null) {
                return;
            }
            if (!places.isJsonArray()) {
                throw wrongType("iso.keyPlaces", "a list of objects with world, x, z, radius, label and name");
            }
            n = 0;
            for (JsonElement a : places.getAsJsonArray()) {
                String ak = "iso.keyPlaces[" + n++ + "]";
                if (!a.isJsonObject()) {
                    throw wrongType(ak, "an object with world, x, z, radius, label and name");
                }
                JsonObject ao = a.getAsJsonObject();
                for (String c : List.of("world", "x", "z", "label")) {
                    if (!ao.has(c)) {
                        throw new InvalidConfigException(ak + " needs world, x, z and label");
                    }
                }
                checkValue(ao, "world", ak + ".world", String.class, false);
                checkValue(ao, "label", ak + ".label", String.class, false);
                checkValue(ao, "name", ak + ".name", String.class, true);
                checkValue(ao, "x", ak + ".x", int.class, false);
                checkValue(ao, "z", ak + ".z", int.class, false);
                checkValue(ao, "radius", ak + ".radius", int.class, false);
            }
            return;
        }
        if (key.equals("worlds")) {
            if (!v.isJsonObject()) {
                throw wrongType(key, "an object of world names and settings");
            }
            for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet()) {
                String wk = "worlds." + e.getKey();
                if (!e.getValue().isJsonObject()) {
                    throw wrongType(wk, "an object");
                }
                JsonObject o = e.getValue().getAsJsonObject();
                checkValue(o, "enabled", wk + ".enabled", boolean.class, false);
                checkValue(o, "displayName", wk + ".displayName", String.class, true);
                checkValue(o, "hidden", wk + ".hidden", boolean.class, true);
                JsonElement limit = o.get("renderLimit");
                if (limit != null && !limit.isJsonNull()) {
                    if (!limit.isJsonObject()) {
                        throw wrongType(wk + ".renderLimit", "an object with x, z and radius, or null");
                    }
                    JsonObject l = limit.getAsJsonObject();
                    for (String c : List.of("x", "z", "radius")) {
                        if (!l.has(c)) {
                            throw new InvalidConfigException(wk + ".renderLimit needs x, z and radius");
                        }
                        checkValue(l, c, wk + ".renderLimit." + c, double.class, false);
                    }
                }
            }
            return;
        }
        if (t == String.class) {
            if (!v.isJsonNull() && !(v.isJsonPrimitive() && v.getAsJsonPrimitive().isString())) {
                throw wrongType(key, "a string");
            }
        } else if (t == int.class) {
            if (!isNumber(v)) {
                throw wrongType(key, "a whole number");
            }
            double d = v.getAsDouble();
            if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                throw wrongType(key, "a whole number");
            }
        } else if (t == double.class) {
            if (!isNumber(v)) {
                throw wrongType(key, "a number");
            }
        } else if (t == boolean.class) {
            if (!(v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean())) {
                throw wrongType(key, "true or false");
            }
        } else if (t == List.class) {
            if (!v.isJsonArray()) {
                throw wrongType(key, "a list of strings");
            }
            for (JsonElement e : v.getAsJsonArray()) {
                if (!(e.isJsonPrimitive() && e.getAsJsonPrimitive().isString())) {
                    throw wrongType(key, "a list of strings");
                }
            }
        } else if (t == Map.class) {
            if (!v.isJsonObject()) {
                throw wrongType(key, "an object of layer names and levels");
            }
            for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet()) {
                JsonElement level = e.getValue();
                if (!(level.isJsonPrimitive() && level.getAsJsonPrimitive().isString())) {
                    throw wrongType(key + "." + e.getKey(), "a string");
                }
            }
        }
    }

    /** Checks one value of a nested object. An absent key is fine, it keeps its default. */
    private static void checkValue(JsonObject o, String name, String key, Class<?> type, boolean nullable)
            throws InvalidConfigException {
        JsonElement v = o.get(name);
        if (v == null) {
            return;
        }
        if (v.isJsonNull()) {
            if (nullable) {
                return;
            }
            throw new InvalidConfigException(key + " must not be null");
        }
        if (type == boolean.class) {
            if (!(v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean())) {
                throw wrongType(key, "true or false");
            }
        } else if (type == String.class) {
            if (!(v.isJsonPrimitive() && v.getAsJsonPrimitive().isString())) {
                throw wrongType(key, "a string");
            }
        } else if (type == int.class) {
            if (!isNumber(v)) {
                throw wrongType(key, "a whole number");
            }
            double d = v.getAsDouble();
            if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                throw wrongType(key, "a whole number");
            }
        } else if (type == double.class) {
            if (!isNumber(v)) {
                throw wrongType(key, "a number");
            }
        }
    }

    private static boolean isNumber(JsonElement v) {
        if (!v.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive p = v.getAsJsonPrimitive();
        if (!p.isNumber()) {
            return false;
        }
        try {
            double d = p.getAsDouble();
            return !Double.isNaN(d) && !Double.isInfinite(d);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static InvalidConfigException wrongType(String key, String expected) {
        return new InvalidConfigException(key + " must be " + expected);
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        if (m == null) {
            return c.getClass().getSimpleName();
        }
        int nl = m.indexOf('\n');
        return (nl < 0 ? m : m.substring(0, nl)).trim();
    }

    /** The fields written to atlas.json, in file order. */
    private static List<Field> fields() {
        List<Field> out = new ArrayList<>();
        for (Field f : AtlasConfig.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) || Modifier.isTransient(m)) {
                continue;
            }
            out.add(f);
        }
        return out;
    }

    private static boolean knownKey(String key) {
        for (Field f : fields()) {
            if (f.getName().equals(key)) {
                return true;
            }
        }
        return false;
    }

    public void save(Path dataDir) throws IOException {
        JsonObject out = Json.GSON.toJsonTree(this).getAsJsonObject();
        // Keys Atlas does not know stay in the file as the owner wrote them, after the known ones.
        if (unknownValues != null) {
            for (Map.Entry<String, JsonElement> e : unknownValues.entrySet()) {
                if (!out.has(e.getKey())) {
                    out.add(e.getKey(), e.getValue());
                }
            }
        }
        Json.writeAtomic(dataDir.resolve(FILE_NAME), Json.PRETTY.toJson(out));
    }

    /** Turns map, /map/ or /map// into /map, and empty or a lone slash into the empty string. */
    public static String normalizeBasePath(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replace('\\', '/');
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        while (s.startsWith("//")) {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            return "";
        }
        if (s.contains("..") || s.contains("?") || s.contains("#")) {
            return "";
        }
        return s.startsWith("/") ? s : "/" + s;
    }

    /** True when publicUrl holds an address. */
    public boolean hasPublicUrl() {
        return publicUrl != null && !publicUrl.isBlank();
    }

    private static String randomToken() {
        SecureRandom rnd = new SecureRandom();
        String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
