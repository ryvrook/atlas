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

    public String bind = "0.0.0.0";
    public int port = 8123;
    public String publicTitle = "Atlas";
    /** Address printed by /atlas and sent to browsers. Empty means the owner has not set one. */
    public String publicUrl = "";
    /** Prefix stripped from request paths when a reverse proxy mounts the map under a sub path, such as /map. */
    public String basePath = "";
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
        if (cfg.trustedProxies == null) {
            cfg.trustedProxies = new ArrayList<>();
        }
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
        if (trustedProxies != null) {
            for (String p : trustedProxies) {
                if (p == null) {
                    throw new InvalidConfigException("trustedProxies holds a null entry");
                }
            }
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
