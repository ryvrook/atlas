package dev.ryvrook.atlas.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.atlas.util.Json;

import java.io.IOException;
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
 * defaults below and are written back so the file always lists every option.
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

    /** Loads the file, fills gaps with defaults and writes the merged result back. */
    public static AtlasConfig load(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(FILE_NAME);
        AtlasConfig cfg = new AtlasConfig();
        if (Files.exists(file)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonObject()) {
                AtlasConfig read = Json.GSON.fromJson(parsed, AtlasConfig.class);
                if (read != null) {
                    cfg = read;
                }
            }
        }
        Map<String, String> merged = defaultVisibility();
        if (cfg.visibility != null) {
            for (Map.Entry<String, String> e : cfg.visibility.entrySet()) {
                merged.put(e.getKey(), Visibility.parse(e.getValue()).name().toLowerCase(Locale.ROOT));
            }
        }
        cfg.visibility = merged;
        if (cfg.bind == null || cfg.bind.isBlank()) {
            cfg.bind = "0.0.0.0";
        }
        if (cfg.port <= 0 || cfg.port > 65535) {
            cfg.port = 8123;
        }
        if (cfg.tileCacheMb < 4) {
            cfg.tileCacheMb = 4;
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
        cfg.basePath = normalizeBasePath(cfg.basePath);
        if (cfg.betterMapDir == null || cfg.betterMapDir.isBlank()) {
            cfg.betterMapDir = "mods/BetterMap";
        }
        if (cfg.featsExport == null || cfg.featsExport.isBlank()) {
            cfg.featsExport = "mods/Ryvrook_Feats/export/feats.json";
        }
        if (cfg.sseClientsPerIp <= 0) {
            cfg.sseClientsPerIp = 8;
        }
        if (cfg.trustedProxies == null) {
            cfg.trustedProxies = new ArrayList<>();
        }
        if (cfg.adminToken == null || cfg.adminToken.isBlank()) {
            cfg.adminToken = randomToken();
        } else if (cfg.adminToken.trim().length() < MIN_ADMIN_TOKEN) {
            cfg.adminToken = randomToken();
            cfg.adminTokenReplaced = true;
        }
        cfg.save(dataDir);
        return cfg;
    }

    public void save(Path dataDir) throws IOException {
        JsonObject out = Json.GSON.toJsonTree(this).getAsJsonObject();
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
