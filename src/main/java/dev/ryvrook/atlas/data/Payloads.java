package dev.ryvrook.atlas.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Turns Dominion snapshot and Feats export objects into the API version 1 shapes. Every output is built
 * from an explicit field list with one JSON type per field, and every listed key is present, with null when
 * the source has no value. Nothing here touches the engine, so the mapping can be tested with plain JSON.
 */
public final class Payloads {
    /** The name every viewer but admins and the player themself sees for a hidden player. */
    public static final String HIDDEN_NAME = "A player";

    private Payloads() {
    }

    // ---- value helpers --------------------------------------------------------

    /** A string value, or null when the key is missing, not a primitive or blank. */
    public static String text(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        String s = e.getAsString();
        return s.isBlank() ? null : s;
    }

    /** A number value, or null when the key is missing or not a number. */
    public static Double number(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isNumber()) {
            double d = p.getAsDouble();
            return Double.isFinite(d) ? d : null;
        }
        if (p.isString()) {
            try {
                double d = Double.parseDouble(p.getAsString().trim());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /** A whole number value, or null. */
    public static Long whole(JsonObject o, String key) {
        Double d = number(o, key);
        return d == null ? null : Math.round(d);
    }

    /** A whole number value, or the default. */
    public static long whole(JsonObject o, String key, long def) {
        Long v = whole(o, key);
        return v == null ? def : v;
    }

    /** A number value, or the default. */
    public static double number(JsonObject o, String key, double def) {
        Double v = number(o, key);
        return v == null ? def : v;
    }

    /** An epoch millisecond time, or null when missing or not positive. */
    public static Long time(JsonObject o, String key) {
        Long v = whole(o, key);
        return v == null || v <= 0 ? null : v;
    }

    /** A boolean value, false when missing. */
    public static boolean flag(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        return p.isBoolean() ? p.getAsBoolean() : "true".equalsIgnoreCase(p.getAsString());
    }

    /** A boolean value, or null when missing. */
    public static Boolean flagOrNull(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        return flag(o, key);
    }

    static JsonArray array(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    static JsonObject object(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static void put(JsonObject o, String key, String v) {
        if (v == null) {
            o.add(key, JsonNull.INSTANCE);
        } else {
            o.addProperty(key, v);
        }
    }

    public static void put(JsonObject o, String key, Number v) {
        if (v == null) {
            o.add(key, JsonNull.INSTANCE);
        } else {
            o.addProperty(key, v);
        }
    }

    public static void put(JsonObject o, String key, Boolean v) {
        if (v == null) {
            o.add(key, JsonNull.INSTANCE);
        } else {
            o.addProperty(key, v);
        }
    }

    public static void put(JsonObject o, String key, JsonElement v) {
        o.add(key, v == null ? JsonNull.INSTANCE : v);
    }

    /** Parses a uuid, or null. */
    public static UUID uuid(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The error body {ok: false, error}. */
    public static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        return o;
    }

    /**
     * One Kill of the killlog and kill events. The parties come already under the hidden rule, and x
     * and z already as the viewer may see them, null when unknown or withheld.
     */
    public static JsonObject kill(long at, String world, JsonElement victim, JsonElement killer, String cause,
                                  boolean ranged, Integer x, Integer z) {
        JsonObject o = new JsonObject();
        o.addProperty("at", at);
        put(o, "world", world);
        o.add("victim", victim);
        o.add("killer", killer == null ? JsonNull.INSTANCE : killer);
        put(o, "cause", cause);
        o.addProperty("ranged", ranged);
        o.addProperty("x", x);
        o.addProperty("z", z);
        return o;
    }

    // ---- hidden rule ----------------------------------------------------------

    /**
     * True when a place must be stored as unknown when an event is recorded, because the live map would
     * not show the player there. That is below the hideBelowY line, under a crouching or invisible
     * hide rule, or in the territory of their own faction while hidePlayersInOwnTerritory is on. y is
     * never stored, so these rules cannot be applied later.
     */
    public static boolean placeWithheld(double y, int hideBelowY, boolean autoHidden, boolean inOwnTerritory) {
        return !Double.isFinite(y) || y < hideBelowY || autoHidden || inOwnTerritory;
    }

    /**
     * True when a viewer must not see a player's name. Admins and the player themself always may, and a
     * player with no uuid cannot be hidden. isHidden is the Atlas hide list plus the Dominion flag.
     */
    public static boolean hiddenFrom(boolean viewerAdmin, UUID viewer, UUID player, Predicate<UUID> isHidden) {
        if (player == null || viewerAdmin || player.equals(viewer)) {
            return false;
        }
        return isHidden.test(player);
    }

    /**
     * Writes uuid and name keys for a named player under the hidden rule. A hidden player becomes
     * "A player" with a null uuid. hidden tells whether this viewer must not see the player.
     */
    public static void person(JsonObject o, String uuidKey, String nameKey, UUID uuid, String name, boolean hidden) {
        if (hidden) {
            o.add(uuidKey, JsonNull.INSTANCE);
            o.addProperty(nameKey, HIDDEN_NAME);
        } else {
            put(o, uuidKey, uuid == null ? null : uuid.toString());
            put(o, nameKey, name);
        }
    }

    // ---- chunks ---------------------------------------------------------------

    /** [cx, cz] pairs, dropping anything that is not two numbers. */
    public static JsonArray chunks(JsonArray in) {
        JsonArray out = new JsonArray();
        for (JsonElement e : in) {
            int[] c = DominionSnapshot.chunkOf(e);
            if (c != null) {
                out.add(pair(c[0], c[1]));
            }
        }
        return out;
    }

    public static JsonArray pair(long a, long b) {
        JsonArray p = new JsonArray();
        p.add(a);
        p.add(b);
        return p;
    }

    // ---- Dominion -------------------------------------------------------------

    /** A nation reference {tag, name, color}, or null. */
    public static JsonElement nationRef(JsonObject n) {
        if (n == null) {
            return JsonNull.INSTANCE;
        }
        String tag = text(n, "tag");
        String name = text(n, "name");
        if (tag == null && name == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.addProperty("tag", tag == null ? name : tag);
        o.addProperty("name", name == null ? tag : name);
        put(o, "color", text(n, "color"));
        return o;
    }

    /** The relations object {TAG: ALLY|TRUCE|ENEMY}, dropping anything else. */
    static JsonObject relations(JsonObject f) {
        JsonObject out = new JsonObject();
        JsonObject in = object(f, "relations");
        if (in == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : in.entrySet()) {
            if (!e.getValue().isJsonPrimitive()) {
                continue;
            }
            String r = e.getValue().getAsString().trim().toUpperCase(Locale.ROOT);
            if (r.equals("ALLY") || r.equals("TRUCE") || r.equals("ENEMY")) {
                out.addProperty(e.getKey(), r);
            }
        }
        return out;
    }

    /** The member count, from a number or from a members array. */
    public static long memberCount(JsonObject f) {
        JsonElement m = f == null ? null : f.get("members");
        if (m != null && m.isJsonArray()) {
            return m.getAsJsonArray().size();
        }
        Long n = whole(f, "members");
        if (n != null) {
            return n;
        }
        return array(f, "memberIds").size();
    }

    /**
     * One faction row. showBank decides whether the bank is filled in. nation is the resolved nation
     * object, or null when there is none or nations are hidden.
     */
    public static JsonObject faction(JsonObject f, boolean showBank, JsonObject nation) {
        JsonObject o = new JsonObject();
        put(o, "id", text(f, "id"));
        put(o, "tag", text(f, "tag"));
        String tag = text(f, "tag");
        String name = text(f, "name");
        o.addProperty("name", name == null ? (tag == null ? "" : tag) : name);
        put(o, "color", text(f, "color"));
        put(o, "leader", text(f, "leader"));
        o.addProperty("members", memberCount(f));
        o.addProperty("online", whole(f, "online", 0));
        o.addProperty("claims", whole(f, "claims", 0));
        o.addProperty("power", number(f, "power", 0));
        o.addProperty("maxPower", number(f, "maxPower", 0));
        o.addProperty("value", number(f, "value", 0));
        o.addProperty("rank", whole(f, "rank", 0));
        o.addProperty("kills", whole(f, "kills", 0));
        o.addProperty("deaths", whole(f, "deaths", 0));
        o.addProperty("siegesWon", whole(f, "siegesWon", 0));
        o.addProperty("siegesLost", whole(f, "siegesLost", 0));
        o.addProperty("level", whole(f, "level", 0));
        o.addProperty("points", whole(f, "points", 0));
        o.add("relations", relations(f));
        put(o, "createdAt", time(f, "createdAt"));
        put(o, "description", text(f, "description"));
        put(o, "bank", showBank ? number(f, "bank") : null);
        o.add("nation", nationRef(nation));
        return o;
    }

    /** The short faction row used inside nations. */
    public static JsonObject factionBrief(JsonObject f) {
        JsonObject o = new JsonObject();
        String tag = text(f, "tag");
        String name = text(f, "name");
        put(o, "tag", tag);
        o.addProperty("name", name == null ? (tag == null ? "" : tag) : name);
        put(o, "color", text(f, "color"));
        o.addProperty("value", number(f, "value", 0));
        o.addProperty("claims", whole(f, "claims", 0));
        o.addProperty("power", number(f, "power", 0));
        o.addProperty("members", memberCount(f));
        return o;
    }

    /** One siege. */
    public static JsonObject siege(JsonObject s) {
        JsonObject o = new JsonObject();
        put(o, "id", text(s, "id"));
        put(o, "attacker", text(s, "attacker"));
        put(o, "defender", text(s, "defender"));
        put(o, "phase", text(s, "phase"));
        put(o, "world", text(s, "world"));
        JsonElement c = s == null ? null : s.get("chunk");
        int[] chunk = c == null ? null : DominionSnapshot.chunkOf(c);
        o.add("chunk", chunk == null ? JsonNull.INSTANCE : pair(chunk[0], chunk[1]));
        put(o, "endsAt", time(s, "endsAt"));
        return o;
    }

    /** One territory, with power, maxPower and claims from its faction when known. */
    public static JsonObject territory(JsonObject t, JsonObject faction) {
        JsonObject o = new JsonObject();
        put(o, "id", text(t, "id"));
        String tag = text(t, "tag");
        o.addProperty("tag", tag == null ? "" : tag);
        String name = text(t, "name");
        o.addProperty("name", name == null ? (tag == null ? "" : tag) : name);
        put(o, "color", text(t, "color"));
        put(o, "type", text(t, "type"));
        o.addProperty("raidable", flag(t, "raidable"));
        o.add("chunks", chunks(array(t, "chunks")));
        o.add("breached", chunks(array(t, "breached")));
        o.add("zones", zones(array(t, "zones")));
        put(o, "power", faction == null ? null : number(faction, "power"));
        put(o, "maxPower", faction == null ? null : number(faction, "maxPower"));
        put(o, "claims", faction == null ? null : whole(faction, "claims"));
        return o;
    }

    /** Named zones inside a territory, kept only when they have a name and at least one chunk. */
    static JsonArray zones(JsonArray in) {
        JsonArray out = new JsonArray();
        for (JsonElement e : in) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject z = e.getAsJsonObject();
            String name = text(z, "name");
            JsonArray chunks = chunks(array(z, "chunks"));
            if (name == null || chunks.isEmpty()) {
                continue;
            }
            JsonObject o = new JsonObject();
            put(o, "id", text(z, "id"));
            o.addProperty("name", name);
            o.add("chunks", chunks);
            out.add(o);
        }
        return out;
    }

    /** True for the point kinds that are server events, hills, outposts and envoys. */
    public static boolean isEventKind(String kind) {
        if (kind == null) {
            return false;
        }
        String k = kind.toLowerCase(Locale.ROOT);
        return k.equals("hill") || k.equals("koth") || k.equals("outpost") || k.equals("envoy");
    }

    /**
     * One point. Admins get the height, everyone else null and whole block x and z. Event points get the
     * holder, control and status lines of the matching hill or outpost.
     */
    public static JsonObject point(JsonObject p, boolean admin, JsonObject events) {
        JsonObject o = new JsonObject();
        String kind = text(p, "kind");
        String id = text(p, "id");
        o.addProperty("id", id == null ? "" : id);
        o.addProperty("kind", kind == null ? "" : kind);
        put(o, "label", text(p, "label"));
        double x = number(p, "x", 0);
        double z = number(p, "z", 0);
        Double y = number(p, "y");
        o.addProperty("x", Math.round(x));
        put(o, "y", admin && y != null ? (Number) Math.round(y) : null);
        o.addProperty("z", Math.round(z));
        put(o, "color", text(p, "color"));
        put(o, "faction", text(p, "faction"));
        put(o, "expiresAt", time(p, "expiresAt"));
        boolean event = isEventKind(kind);
        o.addProperty("event", event);
        JsonObject status = event ? eventStatus(p, kind, events) : null;
        put(o, "holder", status == null ? null : text(status, "holder"));
        put(o, "control", status == null ? null : text(status, "control"));
        put(o, "status", status == null ? null : text(status, "status"));
        return o;
    }

    /** The hill or outpost row of the events object that matches a point by label or id, or null. */
    static JsonObject eventStatus(JsonObject point, String kind, JsonObject events) {
        if (events == null || kind == null) {
            return null;
        }
        String k = kind.toLowerCase(Locale.ROOT);
        String list = k.equals("outpost") ? "outposts" : k.equals("envoy") ? null : "koths";
        if (list == null) {
            return null;
        }
        String label = text(point, "label");
        String id = text(point, "id");
        for (JsonElement e : array(events, list)) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            String name = text(o, "name");
            if (name != null && (name.equalsIgnoreCase(label) || name.equalsIgnoreCase(id)
                    || (id != null && id.toLowerCase(Locale.ROOT).endsWith(":" + name.toLowerCase(Locale.ROOT))))) {
                return o;
            }
        }
        return null;
    }

    /** One hill or outpost row of the stats events summary. */
    static JsonObject eventRow(JsonElement e) {
        JsonObject src = e.isJsonObject() ? e.getAsJsonObject() : null;
        String name = src != null ? text(src, "name") : e.isJsonPrimitive() ? e.getAsString() : null;
        if (name == null || name.isBlank()) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        put(o, "status", text(src, "status"));
        put(o, "holder", text(src, "holder"));
        put(o, "control", text(src, "control"));
        put(o, "endsAt", time(src, "endsAt"));
        return o;
    }

    /** The events summary for the stats panel. */
    public static JsonObject events(JsonObject events) {
        JsonObject out = new JsonObject();
        put(out, "enabled", flagOrNull(events, "enabled"));
        JsonArray koths = new JsonArray();
        for (JsonElement e : array(events, "koths")) {
            JsonObject r = eventRow(e);
            if (r != null) {
                koths.add(r);
            }
        }
        out.add("koths", koths);
        JsonArray outposts = new JsonArray();
        for (JsonElement e : array(events, "outposts")) {
            JsonObject r = eventRow(e);
            if (r != null) {
                outposts.add(r);
            }
        }
        out.add("outposts", outposts);
        JsonArray envoys = new JsonArray();
        for (JsonElement e : array(events, "envoys")) {
            String t = e.isJsonPrimitive() ? e.getAsString()
                    : e.isJsonObject() ? (text(e.getAsJsonObject(), "name") != null ? text(e.getAsJsonObject(), "name")
                    : text(e.getAsJsonObject(), "status")) : null;
            if (t != null && !t.isBlank()) {
                envoys.add(t);
            }
        }
        out.add("envoys", envoys);
        out.addProperty("bounties", whole(events, "bounties", 0));
        JsonElement season = events == null ? null : events.get("season");
        String seasonText = null;
        Long seasonEnds = null;
        if (season != null && season.isJsonPrimitive()) {
            seasonText = season.getAsString();
        } else if (season != null && season.isJsonObject()) {
            JsonObject so = season.getAsJsonObject();
            seasonText = text(so, "status") != null ? text(so, "status") : text(so, "phase");
            seasonEnds = time(so, "endsAt");
        }
        put(out, "season", seasonText == null || seasonText.isBlank() ? null : seasonText);
        put(out, "seasonEndsAt", seasonEnds);
        return out;
    }

    /** A grace object {active, endsAt}, or null. */
    public static JsonElement grace(JsonObject grace) {
        if (grace == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.addProperty("active", flag(grace, "active"));
        put(o, "endsAt", time(grace, "endsAt"));
        return o;
    }

    /** A top five list by a key, each row {tag, name, color, amount}. */
    public static JsonArray top(List<JsonObject> rows, String key) {
        List<JsonObject> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> Double.compare(number(b, key, 0), number(a, key, 0)));
        JsonArray out = new JsonArray();
        for (int i = 0; i < Math.min(5, sorted.size()); i++) {
            JsonObject f = sorted.get(i);
            JsonObject o = new JsonObject();
            String tag = text(f, "tag");
            o.addProperty("tag", tag == null ? "" : tag);
            String name = text(f, "name");
            o.addProperty("name", name == null ? (tag == null ? "" : tag) : name);
            put(o, "color", text(f, "color"));
            o.addProperty("amount", number(f, key, 0));
            out.add(o);
        }
        return out;
    }

    /**
     * A feed line {at, type, text, source, world}. Lines from the snapshot have source dominion. world names
     * the world the line is about, or null when it is about no world in particular. Null without text.
     */
    public static JsonObject feedLine(JsonObject raw) {
        String text = text(raw, "text");
        if (text == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        Long at = time(raw, "at");
        o.addProperty("at", at == null ? System.currentTimeMillis() : at);
        String type = text(raw, "type");
        o.addProperty("type", type == null ? "other" : type);
        o.addProperty("text", text);
        o.addProperty("source", "atlas".equals(text(raw, "source")) ? "atlas" : "dominion");
        put(o, "world", text(raw, "world"));
        return o;
    }

    // ---- Feats ----------------------------------------------------------------

    /** The leaderboard when no export is present or readable. */
    public static JsonObject emptyLeaderboard() {
        JsonObject out = new JsonObject();
        out.addProperty("available", false);
        out.add("updatedAt", JsonNull.INSTANCE);
        out.addProperty("totalFeats", 0);
        out.addProperty("totalPoints", 0);
        out.add("online", JsonNull.INSTANCE);
        out.addProperty("rarityPlayers", 0);
        out.add("rarity", new JsonObject());
        out.add("players", new JsonArray());
        out.add("recent", new JsonArray());
        out.add("milestones", new JsonArray());
        out.add("firsts", new JsonArray());
        return out;
    }

    /**
     * Maps the Feats export to the leaderboard shape. hidden says, for a player uuid, whether this
     * viewer must not see that player, and such players become "A player" with a null uuid everywhere.
     */
    public static JsonObject leaderboard(JsonObject root, long updatedAt, Predicate<UUID> hidden) {
        return leaderboard(root, updatedAt, hidden, null, false);
    }

    /** What a hidden feat is called for a viewer who has not earned it. */
    public static final String HIDDEN_FEAT_NAME = "A hidden feat";

    /**
     * The ids of hidden feats, from the hidden flag Feats writes on its recent unlocks. Milestone and first
     * records carry no flag of their own, so a hidden feat is only known once it shows up in the recent list.
     */
    public static java.util.Set<String> hiddenFeats(JsonObject root) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (JsonElement e : array(root, "recent")) {
            if (e.isJsonObject() && flag(e.getAsJsonObject(), "hidden")) {
                String feat = text(e.getAsJsonObject(), "feat");
                if (feat != null) {
                    out.add(feat);
                }
            }
        }
        return out;
    }

    /**
     * Same as {@link #leaderboard(JsonObject, long, Predicate)} for one viewer. A hidden feat keeps its name
     * only for admins and for a viewer who earned it, as far as the recent list and the firsts show.
     * Everyone else sees {@link #HIDDEN_FEAT_NAME}, the id {@code hidden}, no category and no rarity.
     */
    public static JsonObject leaderboard(JsonObject root, long updatedAt, Predicate<UUID> hidden, UUID viewer,
                                         boolean admin) {
        if (root == null) {
            return emptyLeaderboard();
        }
        java.util.Set<String> secret = admin ? java.util.Set.of() : hiddenFeats(root);
        java.util.Set<String> owned = new java.util.HashSet<>();
        if (viewer != null && !secret.isEmpty()) {
            String me = viewer.toString();
            for (JsonElement e : array(root, "recent")) {
                if (e.isJsonObject() && me.equalsIgnoreCase(text(e.getAsJsonObject(), "uuid"))) {
                    owned.add(text(e.getAsJsonObject(), "feat"));
                }
            }
            JsonObject firsts = object(root, "firsts");
            if (firsts != null) {
                for (Map.Entry<String, JsonElement> e : firsts.entrySet()) {
                    if (e.getValue().isJsonObject() && me.equalsIgnoreCase(text(e.getValue().getAsJsonObject(), "by"))) {
                        owned.add(e.getKey());
                    }
                }
            }
        }
        Predicate<String> masked = feat -> feat != null && secret.contains(feat) && !owned.contains(feat);
        JsonObject out = new JsonObject();
        out.addProperty("available", true);
        put(out, "updatedAt", updatedAt > 0 ? updatedAt : time(root, "updatedAt"));
        out.addProperty("totalFeats", whole(root, "totalFeats", 0));
        out.addProperty("totalPoints", whole(root, "totalPoints", 0));
        put(out, "online", whole(root, "online"));
        out.addProperty("rarityPlayers", whole(root, "rarityPlayers", 0));
        JsonObject rarity = new JsonObject();
        JsonObject rin = object(root, "rarity");
        if (rin != null) {
            for (Map.Entry<String, JsonElement> e : rin.entrySet()) {
                Double v = number(rin, e.getKey());
                if (v != null && !masked.test(e.getKey())) {
                    rarity.addProperty(e.getKey(), Math.max(0, Math.min(1, v)));
                }
            }
        }
        out.add("rarity", rarity);
        JsonArray players = new JsonArray();
        int rank = 0;
        for (JsonElement e : array(root, "players")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject r = e.getAsJsonObject();
            String name = text(r, "name");
            UUID id = uuid(text(r, "uuid"));
            if (name == null && id == null) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("rank", ++rank);
            person(o, "uuid", "name", id, name == null ? "" : name, hidden.test(id));
            o.addProperty("points", whole(r, "points", 0));
            o.addProperty("unlocked", whole(r, "unlocked", 0));
            put(o, "lastUnlockAt", time(r, "lastUnlockAt"));
            players.add(o);
        }
        out.add("players", players);
        JsonArray recent = new JsonArray();
        for (JsonElement e : array(root, "recent")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject r = e.getAsJsonObject();
            String feat = text(r, "feat");
            Long at = time(r, "at");
            if (feat == null || at == null) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("at", at);
            UUID id = uuid(text(r, "uuid"));
            String name = text(r, "name");
            person(o, "uuid", "name", id, name == null ? "" : name, hidden.test(id));
            boolean mask = masked.test(feat);
            o.addProperty("feat", mask ? "hidden" : feat);
            String featName = text(r, "featName");
            o.addProperty("featName", mask ? HIDDEN_FEAT_NAME : featName == null ? feat : featName);
            String tier = text(r, "tier");
            o.addProperty("tier", tier == null ? "" : tier);
            o.addProperty("points", whole(r, "points", 0));
            String category = text(r, "category");
            o.addProperty("category", mask || category == null ? "" : category);
            o.addProperty("hiddenFeat", flag(r, "hidden"));
            put(o, "rarity", !mask && rarity.has(feat) ? (Number) rarity.get(feat).getAsDouble() : null);
            recent.add(o);
        }
        out.add("recent", recent);
        out.add("milestones", records(object(root, "milestones"), hidden, masked));
        out.add("firsts", records(object(root, "firsts"), hidden, masked));
        return out;
    }

    /** Milestone or first records keyed by feat id, as a list oldest first. */
    static JsonArray records(JsonObject in, Predicate<UUID> hidden) {
        return records(in, hidden, f -> false);
    }

    static JsonArray records(JsonObject in, Predicate<UUID> hidden, Predicate<String> masked) {
        List<JsonObject> rows = new ArrayList<>();
        if (in != null) {
            for (Map.Entry<String, JsonElement> e : in.entrySet()) {
                if (!e.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject r = e.getValue().getAsJsonObject();
                Long at = time(r, "at");
                if (at == null) {
                    continue;
                }
                JsonObject o = new JsonObject();
                boolean mask = masked.test(e.getKey());
                o.addProperty("feat", mask ? "hidden" : e.getKey());
                String featName = text(r, "featName");
                o.addProperty("featName", mask ? HIDDEN_FEAT_NAME : featName == null ? e.getKey() : featName);
                String tier = text(r, "tier");
                o.addProperty("tier", tier == null ? "" : tier);
                o.addProperty("at", at);
                UUID by = uuid(text(r, "by"));
                String byName = text(r, "byName");
                if (by == null && byName == null) {
                    o.add("uuid", JsonNull.INSTANCE);
                    o.add("name", JsonNull.INSTANCE);
                } else {
                    person(o, "uuid", "name", by, byName, hidden.test(by));
                }
                rows.add(o);
            }
        }
        rows.sort((a, b) -> Long.compare(a.get("at").getAsLong(), b.get("at").getAsLong()));
        JsonArray out = new JsonArray();
        rows.forEach(out::add);
        return out;
    }
}
