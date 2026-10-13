package dev.ryvrook.atlas.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.atlas.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads the snapshot file Dominion exports. Atlas never links against Dominion, it only polls
 * this file and reloads it when the modification time changes. When the file is missing every
 * accessor returns empty data and Atlas runs in standalone mode.
 */
public final class DominionSnapshot {
    /** Parsed view of one snapshot. Immutable once published. */
    public static final class View {
        public final JsonObject root;
        public final long loadedAt;
        public final Map<String, JsonObject> worlds;
        public final Map<String, JsonObject> factionsByTag;
        public final Map<String, String> factionOfPlayer;
        /**
         * Lower case uuid to a copy of the player row with world added. x, y and z may be null or
         * missing, since the exporter only writes positions when its owner opts in. Read them with
         * Json.num and a NaN default, never as plain numbers.
         */
        public final Map<String, JsonObject> playerByUuid;
        /** world name to chunk key to territory object. */
        public final Map<String, Map<Long, JsonObject>> claims;
        /** True when at least one faction lists its member uuids, which makes membership authoritative. */
        public final boolean membersListed;
        /** Upper case nation tag to the nation object from an optional top level nations list. */
        public final Map<String, JsonObject> nationsByTag;
        /** Nation id to the nation object from the same list. */
        public final Map<String, JsonObject> nationsById;
        /** Upper case faction tag to its nation, from the members of each listed nation. */
        public final Map<String, JsonObject> nationOfFaction;
        /** The listed nations in snapshot order, empty when the snapshot has no nations list. */
        public final List<JsonObject> nations;
        /** True when the snapshot carries a top level chat array, which makes it the source of public chat. */
        public final boolean hasChat;
        /**
         * A content hash per section the browser fetches. Player rows inside the worlds are left out,
         * so players moving never change the territories or points hash.
         */
        public final Map<String, Long> sectionHashes;

        View(JsonObject root, long loadedAt) {
            this.root = root;
            this.loadedAt = loadedAt;
            Map<String, JsonObject> w = new HashMap<>();
            Map<String, String> fop = new HashMap<>();
            Map<String, JsonObject> pbu = new HashMap<>();
            Map<String, Map<Long, JsonObject>> cl = new HashMap<>();
            for (JsonElement e : Json.arr(root, "worlds")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject wo = e.getAsJsonObject();
                String name = Json.str(wo, "name");
                if (name == null) {
                    continue;
                }
                w.put(name, wo);
                for (JsonElement pe : Json.arr(wo, "players")) {
                    if (!pe.isJsonObject()) {
                        continue;
                    }
                    JsonObject p = pe.getAsJsonObject();
                    String uuid = Json.str(p, "uuid");
                    if (uuid == null) {
                        continue;
                    }
                    String key = uuid.toLowerCase(Locale.ROOT);
                    JsonObject copy = p.deepCopy();
                    copy.addProperty("world", name);
                    pbu.put(key, copy);
                    String fac = Json.str(p, "faction");
                    if (fac != null && !fac.isBlank()) {
                        fop.put(key, fac);
                    }
                }
                Map<Long, JsonObject> worldClaims = new HashMap<>();
                for (JsonElement te : Json.arr(wo, "territories")) {
                    if (!te.isJsonObject()) {
                        continue;
                    }
                    JsonObject t = te.getAsJsonObject();
                    for (JsonElement ce : Json.arr(t, "chunks")) {
                        int[] c = chunkOf(ce);
                        if (c != null) {
                            worldClaims.put(chunkKey(c[0], c[1]), t);
                        }
                    }
                }
                cl.put(name, worldClaims);
            }
            Map<String, JsonObject> fbt = new HashMap<>();
            boolean listed = false;
            for (JsonElement e : Json.arr(root, "factions")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject f = e.getAsJsonObject();
                String tag = Json.str(f, "tag");
                if (tag != null) {
                    fbt.put(tag.toUpperCase(Locale.ROOT), f);
                }
                if (tag == null) {
                    continue;
                }
                // Dominion lists member uuids under memberIds. Older files may put a list under members.
                JsonElement ids = f.get("memberIds");
                if (ids == null || !ids.isJsonArray()) {
                    JsonElement members = f.get("members");
                    ids = members != null && members.isJsonArray() ? members : null;
                }
                if (ids != null) {
                    listed = true;
                    for (JsonElement m : ids.getAsJsonArray()) {
                        String uuid = m.isJsonPrimitive() ? m.getAsString()
                                : m.isJsonObject() ? Json.str(m.getAsJsonObject(), "uuid") : null;
                        if (uuid != null && !uuid.isBlank()) {
                            // Member lists are authoritative over the faction on a world player row.
                            fop.put(uuid.trim().toLowerCase(Locale.ROOT), tag);
                        }
                    }
                }
            }
            this.worlds = Collections.unmodifiableMap(w);
            this.factionsByTag = Collections.unmodifiableMap(fbt);
            this.factionOfPlayer = Collections.unmodifiableMap(fop);
            this.playerByUuid = Collections.unmodifiableMap(pbu);
            this.claims = Collections.unmodifiableMap(cl);
            this.membersListed = listed;
            // The top level nations list is optional. Each entry has id, tag, name, color and members
            // as faction tags. Entries without a tag are skipped.
            Map<String, JsonObject> nbt = new HashMap<>();
            Map<String, JsonObject> nbi = new HashMap<>();
            Map<String, JsonObject> nof = new HashMap<>();
            List<JsonObject> nl = new ArrayList<>();
            JsonElement nationsEl = root.get("nations");
            if (nationsEl != null && nationsEl.isJsonArray()) {
                for (JsonElement e : nationsEl.getAsJsonArray()) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject n = e.getAsJsonObject();
                    String tag = Json.str(n, "tag");
                    if (tag == null || tag.isBlank()) {
                        continue;
                    }
                    nbt.put(tag.trim().toUpperCase(Locale.ROOT), n);
                    String id = Json.str(n, "id");
                    if (id != null && !id.isBlank()) {
                        nbi.put(id.trim().toLowerCase(Locale.ROOT), n);
                    }
                    JsonElement members = n.get("members");
                    if (members != null && members.isJsonArray()) {
                        for (JsonElement m : members.getAsJsonArray()) {
                            String ft = m.isJsonPrimitive() ? m.getAsString()
                                    : m.isJsonObject() ? Json.str(m.getAsJsonObject(), "tag") : null;
                            if (ft != null && !ft.isBlank()) {
                                nof.put(ft.trim().toUpperCase(Locale.ROOT), n);
                            }
                        }
                    }
                    nl.add(n);
                }
            }
            this.nationsByTag = Collections.unmodifiableMap(nbt);
            this.nationsById = Collections.unmodifiableMap(nbi);
            this.nationOfFaction = Collections.unmodifiableMap(nof);
            this.nations = Collections.unmodifiableList(nl);
            JsonElement chatEl = root.get("chat");
            this.hasChat = chatEl != null && chatEl.isJsonArray();
            this.sectionHashes = Collections.unmodifiableMap(hashSections(root));
        }

        private static Map<String, Long> hashSections(JsonObject root) {
            JsonArray territories = new JsonArray();
            JsonArray points = new JsonArray();
            for (JsonElement e : Json.arr(root, "worlds")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject wo = e.getAsJsonObject();
                JsonArray t = new JsonArray();
                t.add(String.valueOf(Json.str(wo, "name")));
                t.add(Json.arr(wo, "territories"));
                territories.add(t);
                JsonArray p = new JsonArray();
                p.add(String.valueOf(Json.str(wo, "name")));
                p.add(Json.arr(wo, "points"));
                points.add(p);
            }
            territories.add(Json.arr(root, "sieges"));
            JsonElement events = root.get("events");
            points.add(events == null ? new JsonObject() : events);
            JsonArray nationParts = new JsonArray();
            nationParts.add(Json.arr(root, "nations"));
            for (JsonElement e : Json.arr(root, "factions")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject f = e.getAsJsonObject();
                JsonObject brief = new JsonObject();
                for (String k : new String[]{"tag", "name", "color", "nation", "value", "claims", "power", "members"}) {
                    JsonElement x = f.get(k);
                    if (x != null) {
                        brief.add(k, x);
                    }
                }
                nationParts.add(brief);
            }
            JsonArray eventParts = new JsonArray();
            eventParts.add(events == null ? new JsonObject() : events);
            JsonElement grace = root.get("grace");
            eventParts.add(grace == null ? new JsonObject() : grace);
            eventParts.add(Json.bool(root, "eotw"));
            Map<String, Long> out = new java.util.LinkedHashMap<>();
            out.put("territories", hash(territories));
            out.put("points", hash(points));
            out.put("factions", hash(Json.arr(root, "factions")));
            out.put("nations", hash(nationParts));
            out.put("events", hash(eventParts));
            out.put("feed", hash(Json.arr(root, "feed")));
            out.put("chat", hash(Json.arr(root, "chat")));
            return out;
        }

        public long at() {
            return (long) Json.num(root, "at", loadedAt);
        }

        public JsonObject faction(String tag) {
            return tag == null ? null : factionsByTag.get(tag.toUpperCase(Locale.ROOT));
        }

        public JsonObject territoryAt(String world, int cx, int cz) {
            Map<Long, JsonObject> m = claims.get(world);
            return m == null ? null : m.get(chunkKey(cx, cz));
        }

        public JsonArray worldArray(String world, String key) {
            JsonObject w = worlds.get(world);
            return w == null ? new JsonArray() : Json.arr(w, key);
        }

        public JsonArray factions() {
            return Json.arr(root, "factions");
        }

        public JsonArray sieges() {
            return Json.arr(root, "sieges");
        }

        public JsonArray feed() {
            return Json.arr(root, "feed");
        }

        /** Recent public chat lines with at, name, tag and text, oldest first, or an empty array. */
        public JsonArray chat() {
            return Json.arr(root, "chat");
        }

        /** The events object Dominion writes for hills, outposts, envoys, bounties and the season, or null. */
        public JsonObject events() {
            return Json.obj(root, "events");
        }

        /**
         * The nation of a faction as an object with tag, name, color and id, or null. Dominion may write
         * the nation on the faction as a tag, an id or an object, or only list the faction among the
         * members of a top level nation. Fields missing on the faction are filled from the list.
         */
        public JsonObject nationOf(JsonObject faction) {
            if (faction == null) {
                return null;
            }
            JsonElement n = faction.get("nation");
            String tag = null;
            String name = null;
            String color = null;
            String id = null;
            if (n != null && n.isJsonPrimitive()) {
                String ref = n.getAsString().trim();
                JsonObject listed = listedNation(ref);
                if (listed != null) {
                    tag = Json.str(listed, "tag");
                } else if (!ref.isEmpty()) {
                    tag = ref;
                }
            } else if (n != null && n.isJsonObject()) {
                JsonObject o = n.getAsJsonObject();
                tag = Json.str(o, "tag");
                name = Json.str(o, "name");
                color = Json.str(o, "color");
                id = Json.str(o, "id");
            }
            JsonObject listed = tag != null && !tag.isBlank() ? nationsByTag.get(tag.trim().toUpperCase(Locale.ROOT)) : null;
            if (listed == null && id != null && !id.isBlank()) {
                listed = nationsById.get(id.trim().toLowerCase(Locale.ROOT));
            }
            if (listed == null && (n == null || n.isJsonNull()) && Json.str(faction, "tag") != null) {
                listed = nationOfFaction.get(Json.str(faction, "tag").trim().toUpperCase(Locale.ROOT));
            }
            if (listed != null) {
                tag = blank(tag) ? Json.str(listed, "tag") : tag;
                name = blank(name) ? Json.str(listed, "name") : name;
                color = blank(color) ? Json.str(listed, "color") : color;
                id = blank(id) ? Json.str(listed, "id") : id;
            }
            if (blank(tag) && blank(name)) {
                return null;
            }
            JsonObject out = new JsonObject();
            out.addProperty("tag", blank(tag) ? name : tag);
            out.addProperty("name", blank(name) ? tag : name);
            if (!blank(color)) {
                out.addProperty("color", color);
            }
            if (!blank(id)) {
                out.addProperty("id", id);
            }
            return out;
        }

        /** A listed nation by tag or by id, or null. */
        private JsonObject listedNation(String ref) {
            if (ref == null || ref.isBlank()) {
                return null;
            }
            JsonObject byTag = nationsByTag.get(ref.toUpperCase(Locale.ROOT));
            return byTag != null ? byTag : nationsById.get(ref.toLowerCase(Locale.ROOT));
        }

        private static boolean blank(String s) {
            return s == null || s.isBlank();
        }
    }

    private static final View EMPTY = new View(new JsonObject(), 0);

    private volatile Path path;
    private volatile View view = EMPTY;
    private volatile boolean present;
    private FileTime lastModified;
    private long lastSize = -1;
    private long lastFeedAt;
    private long lastChatAt;
    private volatile String lastError;
    private volatile long lastErrorAt;

    public DominionSnapshot(Path path) {
        this.path = path;
    }

    public void setPath(Path path) {
        synchronized (this) {
            this.path = path;
            this.lastModified = null;
            this.lastSize = -1;
        }
    }

    public Path path() {
        return path;
    }

    public View view() {
        return view;
    }

    public boolean present() {
        return present;
    }

    public String lastError() {
        return lastError;
    }

    /** When the last parse failure happened, or zero. */
    public long lastErrorAt() {
        return lastErrorAt;
    }

    /**
     * Checks the file and reloads it when it changed. Returns true when a new snapshot was
     * published. New feed lines are handed to the consumer in order.
     */
    public boolean poll(Consumer<JsonObject> newFeedLine) {
        return poll(newFeedLine, null);
    }

    /**
     * Same as {@link #poll(Consumer)} and also hands chat lines newer than the last one seen to the
     * chat consumer in order. Chat lines from the first load are handed over too, so the map shows
     * recent chat after Atlas starts. Each line is handed over once because its at is remembered.
     */
    public synchronized boolean poll(Consumer<JsonObject> newFeedLine, Consumer<JsonObject> newChatLine) {
        Path p = path;
        try {
            if (p == null || !Files.isRegularFile(p)) {
                if (present) {
                    present = false;
                    view = EMPTY;
                    lastModified = null;
                    lastSize = -1;
                    return true;
                }
                return false;
            }
            FileTime mod = Files.getLastModifiedTime(p);
            long size = Files.size(p);
            if (present && mod.equals(lastModified) && size == lastSize) {
                return false;
            }
            String text = Files.readString(p, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                lastError = "the root is not a JSON object";
                lastErrorAt = System.currentTimeMillis();
                return false;
            }
            View next = new View(parsed.getAsJsonObject(), System.currentTimeMillis());
            boolean firstLoad = !present;
            lastModified = mod;
            lastSize = size;
            view = next;
            present = true;
            lastError = null;
            List<JsonObject> fresh = new ArrayList<>();
            long newest = lastFeedAt;
            for (JsonElement e : next.feed()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                long at = (long) Json.num(e.getAsJsonObject(), "at", 0);
                if (at > lastFeedAt) {
                    fresh.add(e.getAsJsonObject());
                    newest = Math.max(newest, at);
                }
            }
            lastFeedAt = newest;
            if (!firstLoad && newFeedLine != null) {
                fresh.sort((a, b) -> Double.compare(Json.num(a, "at", 0), Json.num(b, "at", 0)));
                fresh.forEach(newFeedLine);
            }
            List<JsonObject> chat = new ArrayList<>();
            long newestChat = lastChatAt;
            for (JsonElement e : next.chat()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                long at = (long) Json.num(e.getAsJsonObject(), "at", 0);
                if (at > lastChatAt) {
                    chat.add(e.getAsJsonObject());
                    newestChat = Math.max(newestChat, at);
                }
            }
            lastChatAt = newestChat;
            if (newChatLine != null) {
                // A stable sort keeps snapshot order for lines that share a timestamp.
                chat.sort((a, b) -> Double.compare(Json.num(a, "at", 0), Json.num(b, "at", 0)));
                chat.forEach(newChatLine);
            }
            return true;
        } catch (Exception e) {
            // A half written file parses badly. Keep the previous view and try again next poll.
            lastError = e.getClass().getSimpleName() + " " + e.getMessage();
            lastErrorAt = System.currentTimeMillis();
            return false;
        }
    }

    /** A 64 bit FNV-1a hash of the JSON text of an element. */
    static long hash(JsonElement e) {
        String text = e == null ? "null" : e.toString();
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            h ^= text.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** Reads a [cx, cz] pair, or returns null when the entry is not two numbers. */
    public static int[] chunkOf(JsonElement e) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 2) {
            return null;
        }
        JsonArray a = e.getAsJsonArray();
        if (!a.get(0).isJsonPrimitive() || !a.get(1).isJsonPrimitive()
                || !a.get(0).getAsJsonPrimitive().isNumber() || !a.get(1).getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt()};
    }

    public static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
