package dev.ryvrook.atlas.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Reads the stats file the separate Chronicle plugin exports. Atlas never links against Chronicle. Like
 * the Feats export it watches the file's modification time and size and parses it again when either
 * changes. A missing file means no Chronicle tab.
 *
 * <p>The file holds {@code Version}, {@code updatedAt}, {@code online}, {@code chronicleVersion}, the
 * server totals under {@code server} and the top ten of each board under {@code boards}. Nothing from it
 * reaches browsers except the fields {@link #payload} lists.
 */
public final class ChronicleExport {
    /** The file version Atlas reads. Another version is still read and logged once. */
    public static final int VERSION = 1;
    /** Rows sent per board. */
    public static final int TOP = 10;

    /** Label and unit of a stat Atlas knows. unit is count, seconds, metres or hitPoints. */
    record Stat(String label, String unit) {
    }

    private static final Map<String, Stat> STATS = new LinkedHashMap<>();
    /** Totals shown as tiles, in this order. */
    static final List<String> HEADLINE = List.of("players_known", "playtime", "kills", "deaths", "blocks_broken",
            "blocks_placed", "distance", "crafted");
    /** Boards listed first, in this order. The rest follow in the order of the file. */
    static final List<String> BOARD_ORDER = List.of("playtime", "kills", "blocks_broken", "blocks_placed", "distance",
            "deaths", "crafted", "kills_boss", "ore_mined", "fish_caught");

    static {
        stat("players_known", "Players ever seen", "count");
        stat("playtime", "Time played", "seconds");
        stat("playtime_active", "Active time", "seconds");
        stat("joins", "Sessions", "count");
        stat("days_active", "Days played", "count");
        stat("distance", "Distance travelled", "metres");
        stat("jumps", "Jumps", "count");
        stat("fall_distance", "Distance fallen", "metres");
        stat("blocks_broken", "Blocks broken", "count");
        stat("ore_mined", "Ore mined", "count");
        stat("wood_chopped", "Logs chopped", "count");
        stat("blocks_placed", "Blocks placed", "count");
        stat("crafted", "Items crafted", "count");
        stat("gained", "Items gained", "count");
        stat("picked_up", "Items picked up", "count");
        stat("fish_caught", "Fish caught", "count");
        stat("harvested", "Crops harvested", "count");
        stat("food_eaten", "Meals eaten", "count");
        stat("potions_drunk", "Potions drunk", "count");
        stat("items_dropped", "Items dropped", "count");
        stat("kills", "Kills", "count");
        stat("kills_ranged", "Ranged kills", "count");
        stat("kills_player", "Player kills", "count");
        stat("kills_boss", "Boss kills", "count");
        stat("deaths", "Deaths", "count");
        stat("damage_dealt", "Damage dealt", "hitPoints");
        stat("damage_taken", "Damage taken", "hitPoints");
        stat("boss_damage", "Damage to bosses", "hitPoints");
        stat("shots_fired", "Shots fired", "count");
        stat("ability_casts", "Abilities cast", "count");
        stat("respawns", "Respawns", "count");
        stat("zones_discovered", "Zones discovered", "count");
        stat("portals_entered", "Portals entered", "count");
        stat("portal_types", "Portal types found", "count");
        stat("memories", "Memories recorded", "count");
        stat("chat_sent", "Chat messages sent", "count");
        stat("use:chest", "Chests opened", "count");
        stat("use:bench", "Benches used", "count");
        stat("use:bed", "Beds used", "count");
        stat("feat_unlocks", "Feats unlocked", "count");
        stat("boss_fights", "Boss fights joined", "count");
    }

    private static void stat(String key, String label, String unit) {
        STATS.put(key, new Stat(label, unit));
    }

    private volatile Path path;
    private volatile JsonObject root;
    private volatile long updatedAt;
    private FileTime lastModified;
    private long lastSize = -1;
    private volatile String lastError;
    /** True while a read failure has been logged and no good read has followed. */
    private boolean errorLogged;
    private boolean versionWarned;

    public ChronicleExport(Path path) {
        this.path = path;
    }

    public synchronized void setPath(Path p) {
        if (!p.equals(path)) {
            path = p;
            lastModified = null;
            lastSize = -1;
        }
    }

    public Path path() {
        return path;
    }

    /** The parsed export, or null when the file is missing. Never modified after it is published. */
    public JsonObject root() {
        return root;
    }

    /** True while a readable export is present. */
    public boolean available() {
        return root != null;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public String lastError() {
        return lastError;
    }

    /** Checks the file and reloads it when it changed. Returns true when the published data changed. */
    public synchronized boolean poll() {
        Path p = path;
        try {
            if (p == null || !Files.isRegularFile(p)) {
                lastModified = null;
                lastSize = -1;
                if (root != null) {
                    root = null;
                    updatedAt = System.currentTimeMillis();
                    return true;
                }
                return false;
            }
            FileTime mod = Files.getLastModifiedTime(p);
            long size = Files.size(p);
            if (mod.equals(lastModified) && size == lastSize) {
                return false;
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
            lastModified = mod;
            lastSize = size;
            if (!parsed.isJsonObject()) {
                // The same broken file is not parsed again until it changes.
                fail(p, "the root is not a JSON object");
                return false;
            }
            JsonObject o = parsed.getAsJsonObject();
            Long version = Payloads.whole(o, "Version");
            if (version != null && version != VERSION && !versionWarned) {
                versionWarned = true;
                log("Atlas reads Chronicle stats version " + VERSION + " but " + p + " has version " + version
                        + ". It is read anyway and this is logged once.");
            }
            root = o;
            updatedAt = Payloads.whole(o, "updatedAt", mod.toMillis());
            lastError = null;
            errorLogged = false;
            return true;
        } catch (Exception e) {
            // Chronicle writes through a temporary file, so this is rare. Try again on the next poll.
            fail(p, e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    /** The answer when no export is present or the viewer may not see it. url is the dashboard address or null. */
    public static JsonObject empty(String url) {
        JsonObject out = new JsonObject();
        out.addProperty("available", false);
        out.add("updatedAt", JsonNull.INSTANCE);
        out.add("online", JsonNull.INSTANCE);
        out.add("chronicleVersion", JsonNull.INSTANCE);
        Payloads.put(out, "url", url);
        out.add("totals", new JsonArray());
        out.add("boards", new JsonArray());
        return out;
    }

    /**
     * Maps the export to the api/chronicle shape. hidden says, for a player uuid, whether this viewer must
     * not see that player, and such players become "A player" with a null uuid. Rows Chronicle already
     * hid stay "A player". url is the dashboard address, or null to leave the link out.
     */
    public static JsonObject payload(JsonObject root, long updatedAt, String url, Predicate<UUID> hidden) {
        if (root == null) {
            return empty(url);
        }
        JsonObject out = new JsonObject();
        out.addProperty("available", true);
        Payloads.put(out, "updatedAt", updatedAt > 0 ? (Long) updatedAt : Payloads.time(root, "updatedAt"));
        Payloads.put(out, "online", Payloads.whole(root, "online"));
        Payloads.put(out, "chronicleVersion", Payloads.text(root, "chronicleVersion"));
        Payloads.put(out, "url", url);
        JsonArray totals = new JsonArray();
        JsonObject server = Payloads.object(root, "server");
        if (server != null) {
            List<String> keys = ordered(server.keySet(), HEADLINE);
            for (String key : keys) {
                Double v = Payloads.number(server, key);
                if (v == null) {
                    continue;
                }
                JsonObject t = new JsonObject();
                describe(t, key);
                t.addProperty("value", value(v));
                t.addProperty("headline", HEADLINE.contains(key));
                totals.add(t);
            }
        }
        out.add("totals", totals);
        JsonArray boards = new JsonArray();
        JsonObject in = Payloads.object(root, "boards");
        if (in != null) {
            for (String key : ordered(in.keySet(), BOARD_ORDER)) {
                JsonElement list = in.get(key);
                if (list == null || !list.isJsonArray()) {
                    continue;
                }
                JsonArray rows = new JsonArray();
                for (JsonElement e : list.getAsJsonArray()) {
                    if (rows.size() >= TOP) {
                        break;
                    }
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject r = e.getAsJsonObject();
                    UUID id = Payloads.uuid(Payloads.text(r, "uuid"));
                    String name = Payloads.text(r, "name");
                    Double v = Payloads.number(r, "value");
                    if ((id == null && name == null) || v == null) {
                        continue;
                    }
                    JsonObject row = new JsonObject();
                    row.addProperty("rank", rows.size() + 1);
                    Payloads.person(row, "uuid", "name", id, name == null ? Payloads.HIDDEN_NAME : name,
                            id != null && hidden.test(id));
                    row.addProperty("value", value(v));
                    rows.add(row);
                }
                if (rows.isEmpty()) {
                    continue;
                }
                JsonObject b = new JsonObject();
                describe(b, key);
                b.add("rows", rows);
                boards.add(b);
            }
        }
        out.add("boards", boards);
        return out;
    }

    /** The keys with the preferred ones first, in their order, then the rest in the order given. */
    static List<String> ordered(java.util.Collection<String> keys, List<String> first) {
        List<String> out = new ArrayList<>();
        for (String k : first) {
            if (keys.contains(k)) {
                out.add(k);
            }
        }
        for (String k : keys) {
            if (!out.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }

    private static void describe(JsonObject o, String key) {
        Stat s = STATS.get(key);
        o.addProperty("key", key);
        o.addProperty("label", s != null ? s.label() : label(key));
        o.addProperty("unit", s != null ? s.unit() : "count");
    }

    /** A label for a stat Atlas does not know, its key with spaces and a capital first letter. */
    static String label(String key) {
        String s = key.replace(':', ' ').replace('_', ' ').replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) {
            return key;
        }
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** Whole numbers stay whole in the JSON, others keep two decimals. */
    private static Number value(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 9.0e15) {
            return (long) v;
        }
        return Math.round(v * 100) / 100.0;
    }

    /** Records a read failure and logs it once until a good read follows. */
    private void fail(Path p, String reason) {
        lastError = reason;
        if (errorLogged) {
            return;
        }
        errorLogged = true;
        log("Atlas could not read the Chronicle stats at " + p + " because " + reason
                + ". The last good copy stays in use and this is logged once until the file reads again.");
    }

    private static void log(String message) {
        try {
            com.hypixel.hytale.logger.HytaleLogger.get("Atlas").at(java.util.logging.Level.WARNING).log(message);
        } catch (RuntimeException | LinkageError ignored) {
            // No logging backend, as in unit tests.
        }
    }
}
