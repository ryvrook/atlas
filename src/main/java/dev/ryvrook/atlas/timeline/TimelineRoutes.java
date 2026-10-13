package dev.ryvrook.atlas.timeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.auth.Viewer;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.data.LastSeen;
import dev.ryvrook.atlas.data.LiveState;
import dev.ryvrook.atlas.data.Payloads;
import dev.ryvrook.atlas.util.Json;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The timeline routes under api/timeline. JSON answers come back as Atlas.ApiResult, day tiles as
 * a Tile. Visibility is applied here at serve time. Tiles and changed follow the terrain layer,
 * the day list, stats and events follow the timeline layer, events also follow the layer of their
 * kind, and hidden players are renamed and lose their place for viewers who are not admins.
 */
public final class TimelineRoutes {
    /** Longest range of the merged events route. */
    static final long MAX_RANGE_MS = 31L * 24 * 60 * 60 * 1000;
    static final int MAX_ITEMS = 5_000;
    static final int DEFAULT_ITEMS = 1_000;
    private static final long HIDDEN_NAMES_MS = 10_000;

    private final Atlas atlas;
    private final Supplier<Timeline> timeline;
    private volatile Set<String> hiddenNames = Set.of();
    private volatile long hiddenNamesAt;

    /** A day tile. status 200 with png and etag, 404 when the tile did not exist that day. */
    public record Tile(int status, byte[] png, String etag, boolean past) {
    }

    public TimelineRoutes(Atlas atlas, Supplier<Timeline> timeline) {
        this.atlas = atlas;
        this.timeline = timeline;
    }

    private AtlasConfig cfg() {
        return atlas.config();
    }

    private static Atlas.ApiResult error(int status, String message) {
        return new Atlas.ApiResult(status, Payloads.error(message));
    }

    /** True for api/timeline/{world}/{date}/tiles/{zoom}/{x}/{z}.png, which ApiServer serves as an image. */
    public static boolean isTile(List<String> parts) {
        return parts.size() == 7 && "timeline".equals(parts.get(0)) && "tiles".equals(parts.get(3));
    }

    /** Checks shared by every route, or null when the request may go on. */
    private Atlas.ApiResult gate(Viewer v, String world, String layer) {
        Timeline t = timeline.get();
        if (t == null || !cfg().timelineEnabled()) {
            return error(404, "The timeline is turned off on this server.");
        }
        if (world == null || !cfg().worldEnabled(world) || atlas.hiddenWorld(v, world) || !known(t, world)) {
            return error(404, "There is no such world.");
        }
        if (!v.canSee(cfg(), layer)) {
            return error(403, "This layer is not visible to you.");
        }
        return null;
    }

    private boolean known(Timeline t, String world) {
        return atlas.live().worlds().containsKey(world) || java.nio.file.Files.isDirectory(t.store().worldDir(world));
    }

    /** Answers a JSON timeline route, or null when the path is not one. */
    public Atlas.ApiResult handle(Viewer v, List<String> parts, Map<String, String> query) {
        if (parts.isEmpty() || !"timeline".equals(parts.get(0)) || isTile(parts)) {
            return null;
        }
        if (parts.size() < 2) {
            return error(404, "Not found.");
        }
        String world = parts.get(1);
        if (parts.size() == 2) {
            Atlas.ApiResult g = gate(v, world, "timeline");
            return g != null ? g : new Atlas.ApiResult(200, days(world));
        }
        if (parts.size() == 3 && "events".equals(parts.get(2))) {
            Atlas.ApiResult g = gate(v, world, "timeline");
            return g != null ? g : range(v, world, query);
        }
        if (parts.size() != 4) {
            return error(404, "Not found.");
        }
        String date = parts.get(2);
        if (!TimelineStore.validDate(date)) {
            return error(400, "The date must look like 2026-01-31.");
        }
        String what = parts.get(3);
        Atlas.ApiResult g = gate(v, world, "changed".equals(what) ? "terrain" : "timeline");
        if (g != null) {
            return g;
        }
        Timeline t = timeline.get();
        return switch (what) {
            case "changed" -> {
                Manifest m = t.store().manifest(world, date);
                if (m == null) {
                    yield error(404, "There is no snapshot of that day.");
                }
                JsonObject o = new JsonObject();
                o.addProperty("world", world);
                o.addProperty("date", date);
                o.addProperty("previous", m.previous());
                o.addProperty("first", m.previous() == null);
                JsonArray rows = new JsonArray();
                for (String k : m.changed()) {
                    int[] p = Manifest.parseKey(k);
                    if (p != null) {
                        JsonArray row = new JsonArray();
                        row.add(p[0]);
                        row.add(p[1]);
                        row.add(p[2]);
                        rows.add(row);
                    }
                }
                o.add("tiles", rows);
                yield new Atlas.ApiResult(200, o);
            }
            case "stats" -> {
                JsonObject s = t.store().stats(world, date);
                yield s == null ? error(404, "There are no stats for that day.") : new Atlas.ApiResult(200, stats(v, s, world, date));
            }
            case "events" -> {
                if (!t.store().dates(world).contains(date)) {
                    yield error(404, "There are no events for that day.");
                }
                JsonObject o = new JsonObject();
                o.addProperty("world", world);
                o.addProperty("date", date);
                List<EventLog.Line> lines = new ArrayList<>(t.events().read(world, date));
                lines.sort(java.util.Comparator.comparingLong(EventLog.Line::at));
                boolean dropped = false;
                JsonArray items = new JsonArray();
                for (EventLog.Line l : lines) {
                    if (EventLog.DROPPED.equals(l.kind())) {
                        dropped = true;
                    }
                    JsonObject e = event(v, l);
                    if (e != null) {
                        items.add(e);
                    }
                }
                o.addProperty("dropped", dropped);
                o.add("items", items);
                yield new Atlas.ApiResult(200, o);
            }
            default -> error(404, "Not found.");
        };
    }

    /** A day tile as a Tile, or an Atlas.ApiResult when a check failed (403, 404 or 400 with a JSON body). */
    public Object tile(Viewer v, List<String> parts) {
        String world = parts.get(1);
        String date = parts.get(2);
        Atlas.ApiResult g = gate(v, world, "terrain");
        if (g != null) {
            return g;
        }
        if (!TimelineStore.validDate(date)) {
            return error(400, "The date must look like 2026-01-31.");
        }
        String last = parts.get(6);
        if (last.endsWith(".png")) {
            last = last.substring(0, last.length() - 4);
        }
        int zoom;
        int x;
        int z;
        try {
            zoom = Integer.parseInt(parts.get(4));
            x = Integer.parseInt(parts.get(5));
            z = Integer.parseInt(last);
        } catch (NumberFormatException e) {
            return error(400, "Tile coordinates must be whole numbers.");
        }
        Timeline t = timeline.get();
        boolean past = date.compareTo(t.today()) < 0;
        Manifest m = t.store().manifest(world, date);
        String h = m == null ? null : m.tiles().get(Manifest.key(zoom, x, z));
        if (h == null) {
            return new Tile(404, null, null, past);
        }
        try {
            byte[] png = t.store().blobs().read(h);
            return png == null ? new Tile(404, null, null, past) : new Tile(200, png, "\"" + h.substring(0, 32) + "\"", past);
        } catch (IOException e) {
            return new Tile(404, null, null, past);
        }
    }

    // ---- day list ----------------------------------------------------------

    private JsonObject days(String world) {
        Timeline t = timeline.get();
        TimelineStore store = t.store();
        JsonObject o = new JsonObject();
        o.addProperty("world", world);
        o.addProperty("enabled", cfg().timelineEnabled());
        o.addProperty("hour", cfg().timelineHour());
        long next = t.nextRunAt();
        o.addProperty("nextRunAt", next > 0 ? (Long) next : null);
        o.addProperty("retainDays", cfg().timelineRetainDays());
        o.addProperty("today", t.today());
        JsonArray days = new JsonArray();
        for (String date : store.dates(world)) {
            JsonObject d = new JsonObject();
            boolean snap = store.hasSnapshot(world, date);
            JsonObject sum = snap ? store.summary(world, date) : null;
            d.addProperty("date", date);
            d.addProperty("snapshot", snap);
            d.addProperty("pruned", store.tombstone(world, date));
            long takenAt = sum == null ? 0 : (long) Json.num(sum, "takenAt", 0);
            d.addProperty("takenAt", takenAt > 0 ? (Long) takenAt : null);
            d.addProperty("tiles", sum == null ? 0 : (int) Json.num(sum, "tiles", 0));
            d.addProperty("changed", sum == null ? 0 : (int) Json.num(sum, "changed", 0));
            d.addProperty("partial", sum != null && Json.bool(sum, "partial"));
            d.addProperty("events", t.events().count(world, date));
            d.addProperty("stats", store.hasStats(world, date));
            days.add(d);
        }
        o.add("days", days);
        return o;
    }

    // ---- stats -------------------------------------------------------------

    private JsonObject stats(Viewer v, JsonObject stored, String world, String date) {
        JsonObject o = new JsonObject();
        o.addProperty("world", world);
        o.addProperty("date", date);
        o.addProperty("takenAt", (long) Json.num(stored, "takenAt", 0));
        o.addProperty("windowSeconds", (long) Json.num(stored, "windowSeconds", ActivityTracker.WINDOW_MS / 1000));
        for (String k : List.of("exploredChunks", "tiles", "changed", "playersSeen", "peakOnline", "onlineNow", "kills",
                "deaths", "blocksBroken", "blocksPlaced", "worldEvents", "events")) {
            o.addProperty(k, (long) Json.num(stored, k, 0));
        }
        JsonObject f = Json.obj(stored, "feats");
        if (f == null || !v.canSee(cfg(), "leaderboard")) {
            o.add("feats", com.google.gson.JsonNull.INSTANCE);
            return o;
        }
        JsonObject feats = new JsonObject();
        long updated = (long) Json.num(f, "updatedAt", 0);
        feats.addProperty("updatedAt", updated > 0 ? (Long) updated : null);
        for (String k : List.of("totalFeats", "totalPoints", "players", "rarityCount", "milestones", "firsts")) {
            feats.addProperty(k, (long) Json.num(f, k, 0));
        }
        JsonArray top = new JsonArray();
        for (JsonElement e : Json.arr(f, "top")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject r = e.getAsJsonObject();
            UUID id = uuid(Json.str(r, "uuid"));
            boolean hidden = hiddenFrom(v, id);
            JsonObject row = new JsonObject();
            row.addProperty("rank", (int) Json.num(r, "rank", top.size() + 1));
            row.addProperty("uuid", hidden || id == null ? null : id.toString());
            String name = Json.str(r, "name");
            row.addProperty("name", hidden ? Payloads.HIDDEN_NAME : name == null ? "" : name);
            row.addProperty("points", (int) Json.num(r, "points", 0));
            row.addProperty("unlocked", (int) Json.num(r, "unlocked", 0));
            top.add(row);
        }
        feats.add("top", top);
        o.add("feats", feats);
        return o;
    }

    // ---- events ------------------------------------------------------------

    private Atlas.ApiResult range(Viewer v, String world, Map<String, String> query) {
        long now = System.currentTimeMillis();
        long to;
        long from;
        int limit;
        try {
            to = query.containsKey("to") && !query.get("to").isBlank() ? Long.parseLong(query.get("to").trim()) : now;
            from = query.containsKey("from") && !query.get("from").isBlank() ? Long.parseLong(query.get("from").trim())
                    : to - 24L * 60 * 60 * 1000;
            limit = query.containsKey("limit") && !query.get("limit").isBlank() ? Integer.parseInt(query.get("limit").trim())
                    : DEFAULT_ITEMS;
        } catch (NumberFormatException e) {
            return error(400, "from, to and limit must be whole numbers, from and to in epoch milliseconds.");
        }
        if (from > to) {
            return error(400, "from must not be after to.");
        }
        from = Math.max(from, to - MAX_RANGE_MS);
        limit = Math.max(1, Math.min(MAX_ITEMS, limit));
        List<String> kinds = new ArrayList<>();
        String k = query.get("kind");
        if (k != null) {
            for (String s : k.split(",")) {
                if (!s.isBlank()) {
                    kinds.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        Timeline t = timeline.get();
        String firstDate = t.events().dateOf(from);
        String lastDate = t.events().dateOf(to);
        List<JsonObject> rows = new ArrayList<>();
        for (String date : t.store().dates(world)) {
            if (date.compareTo(firstDate) < 0 || date.compareTo(lastDate) > 0) {
                continue;
            }
            for (EventLog.Line l : t.events().read(world, date)) {
                if (l.at() < from || l.at() > to || !kindMatches(kinds, l.kind())) {
                    continue;
                }
                JsonObject e = event(v, l);
                if (e != null) {
                    rows.add(e);
                }
            }
        }
        rows.sort((a, b) -> Long.compare(a.get("at").getAsLong(), b.get("at").getAsLong()));
        boolean truncated = rows.size() > limit;
        if (truncated) {
            rows = rows.subList(rows.size() - limit, rows.size());
        }
        JsonObject o = new JsonObject();
        o.addProperty("world", world);
        o.addProperty("from", from);
        o.addProperty("to", to);
        o.addProperty("truncated", truncated);
        JsonArray items = new JsonArray();
        rows.forEach(items::add);
        o.add("items", items);
        return new Atlas.ApiResult(200, o);
    }

    static boolean kindMatches(List<String> kinds, String kind) {
        if (kinds.isEmpty()) {
            return true;
        }
        for (String k : kinds) {
            if (kind.equals(k) || kind.startsWith(k + ".")) {
                return true;
            }
        }
        return false;
    }

    /** The visibility layer an event kind follows, or null for kinds everyone with the timeline may see. */
    static String layerOf(String kind) {
        if (kind.equals("snapshot") || kind.equals(EventLog.DROPPED)) {
            return null;
        }
        if (kind.equals("kill") || kind.equals("death")) {
            return "kills";
        }
        if (kind.startsWith("worldevent")) {
            return "worldevents";
        }
        if (kind.startsWith("feats")) {
            return "leaderboard";
        }
        if (kind.startsWith("marker")) {
            return "markers";
        }
        return "feed";
    }

    /** Kinds whose place is where a player stood. Non admins get it snapped to the chunk corner. */
    static boolean personal(String kind) {
        return kind.equals("join") || kind.equals("leave") || kind.equals("kill") || kind.equals("death");
    }

    /** One event as the viewer may see it, or null when its layer is hidden from the viewer. */
    JsonObject event(Viewer v, EventLog.Line l) {
        String layer = layerOf(l.kind());
        if (layer != null && !v.canSee(cfg(), layer)) {
            return null;
        }
        UUID id = uuid(l.uuid());
        if (id == null && l.who() != null && !v.admin()) {
            id = atlas.lastSeen().byName(l.who());
        }
        boolean self = id != null && id.equals(v.uuid());
        boolean hidden = !v.admin() && !self && (hiddenFrom(v, id) || hiddenNames().contains(lower(l.who())));
        Integer x = l.x();
        Integer z = l.z();
        if (hidden && personal(l.kind())) {
            x = null;
            z = null;
        } else if (!v.admin() && !self && personal(l.kind()) && x != null && z != null) {
            x = Math.floorDiv(x, 32) * 32;
            z = Math.floorDiv(z, 32) * 32;
        }
        String who = l.who();
        String text = l.text();
        if (!v.admin()) {
            if (hidden && who != null) {
                text = replaceName(text, who);
                who = Payloads.HIDDEN_NAME;
            }
            for (String n : hiddenNamesOriginal()) {
                if (self && n.equalsIgnoreCase(v.name())) {
                    continue;
                }
                text = replaceName(text, n);
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("at", l.at());
        o.addProperty("kind", l.kind());
        o.addProperty("world", l.world());
        o.addProperty("x", x);
        o.addProperty("z", z);
        o.addProperty("who", who);
        o.addProperty("text", text);
        return o;
    }

    private boolean hiddenFrom(Viewer v, UUID id) {
        return id != null && !v.admin() && !id.equals(v.uuid()) && atlas.isHiddenPlayer(id);
    }

    static String replaceName(String text, String name) {
        if (text == null || name == null || name.isBlank()) {
            return text;
        }
        Pattern p = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        return m.find() ? m.replaceAll(Matcher.quoteReplacement(Payloads.HIDDEN_NAME)) : text;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private volatile List<String> hiddenOriginal = List.of();

    private List<String> hiddenNamesOriginal() {
        hiddenNames();
        return hiddenOriginal;
    }

    /** Names of hidden players known from the last seen list and the live samples, refreshed every ten seconds. */
    private Set<String> hiddenNames() {
        long now = System.currentTimeMillis();
        if (now - hiddenNamesAt < HIDDEN_NAMES_MS) {
            return hiddenNames;
        }
        Set<String> lower = new HashSet<>();
        List<String> original = new ArrayList<>();
        for (LastSeen.Entry e : atlas.lastSeen().since(0)) {
            if (e.name() != null && e.uuid() != null && atlas.isHiddenPlayer(e.uuid()) && lower.add(lower(e.name()))) {
                original.add(e.name());
            }
        }
        for (LiveState.PlayerSample p : atlas.live().players()) {
            if (p.name() != null && p.uuid() != null && atlas.isHiddenPlayer(p.uuid()) && lower.add(lower(p.name()))) {
                original.add(p.name());
            }
        }
        hiddenOriginal = List.copyOf(original);
        hiddenNames = Set.copyOf(lower);
        hiddenNamesAt = now;
        return hiddenNames;
    }

    private static UUID uuid(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Today's date, for cache headers. */
    public String today() {
        Timeline t = timeline.get();
        return t == null ? LocalDate.now().toString() : t.today();
    }
}
