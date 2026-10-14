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

    static final String SLOT_FORMAT = "The slot must look like 2026-01-31T16, or a date such as 2026-01-31 for the snapshot of Atlas 1.1 that day or else its last slot.";

    private static Atlas.ApiResult error(int status, String message) {
        return new Atlas.ApiResult(status, Payloads.error(message));
    }

    /** True for api/timeline/{world}/export, which streams a zip and is reached through IsoRoutes. */
    public static boolean isExport(List<String> parts) {
        return parts.size() == 3 && "timeline".equals(parts.get(0)) && "export".equals(parts.get(2));
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
        if (!TimelineStore.validSlot(date)) {
            return error(400, SLOT_FORMAT);
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
                o.addProperty("date", Slots.date(m.date() == null ? date : m.date()));
                o.addProperty("slot", t.store().resolve(world, date));
                // The slot the list is taken against. For an older slot whose named previous slot was pruned
                // that is the newest one still held, and none for the oldest.
                String compared = t.store().comparedWith(world, date);
                o.addProperty("previous", compared);
                o.addProperty("first", compared == null);
                JsonArray rows = new JsonArray();
                // A slot written before tiles were carried over is compared with every tile both slots show.
                for (String k : t.store().changed(world, date)) {
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
                String slot = t.store().resolve(world, date);
                String day = Slots.date(slot);
                if (!t.store().dates(world).contains(day)) {
                    yield error(404, "There are no events for that day.");
                }
                // Events are kept per day. A slot answers the hours of the day that belong to it.
                int[] window = Slots.window(slot, heldSlots(t.store(), world, day));
                JsonObject o = new JsonObject();
                o.addProperty("world", world);
                o.addProperty("date", day);
                o.addProperty("slot", slot);
                JsonArray w = new JsonArray();
                w.add(window[0]);
                w.add(window[1]);
                o.add("window", w);
                List<EventLog.Line> lines = new ArrayList<>(t.events().read(world, day));
                lines.sort(java.util.Comparator.comparingLong(EventLog.Line::at));
                boolean dropped = false;
                JsonArray items = new JsonArray();
                for (EventLog.Line l : lines) {
                    if (EventLog.DROPPED.equals(l.kind())) {
                        dropped = true;
                    }
                    int hour = java.time.Instant.ofEpochMilli(l.at()).atZone(t.zone()).getHour();
                    if (hour < window[0] || hour >= window[1]) {
                        continue;
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
        if (!TimelineStore.validSlot(date)) {
            return error(400, SLOT_FORMAT);
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
        boolean past = Slots.date(date).compareTo(t.today()) < 0;
        // A slot that lacks the tile shows the one the newest earlier slot holds, as the map did then.
        String h = t.store().tileHash(world, date, Manifest.key(zoom, x, z));
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
        o.addProperty("perDay", cfg().timelinePerDay());
        JsonArray hours = new JsonArray();
        for (int h : t.slotHours()) {
            hours.add(h);
        }
        o.add("hours", hours);
        long next = t.nextRunAt();
        o.addProperty("nextRunAt", next > 0 ? (Long) next : null);
        o.addProperty("retainDays", cfg().timelineRetainDays());
        o.addProperty("today", t.today());
        o.addProperty("currentSlot", t.currentSlot());
        JsonArray days = new JsonArray();
        for (String date : store.dates(world)) {
            List<String> held = heldSlots(store, world, date);
            if (held.isEmpty()) {
                // A day with events only.
                JsonObject d = slotRow(world, date, date, null, store, t);
                d.addProperty("events", t.events().count(world, date));
                days.add(d);
                continue;
            }
            int[] counts = held.size() > 1 || Slots.hour(held.get(0)) >= 0 ? hourCounts(t, world, date) : null;
            for (String slot : held) {
                int[] window = Slots.window(slot, held);
                JsonObject d = slotRow(world, date, slot, window, store, t);
                int n = 0;
                if (counts == null) {
                    n = t.events().count(world, date);
                } else {
                    for (int h = window[0]; h < window[1]; h++) {
                        n += counts[h];
                    }
                }
                d.addProperty("events", n);
                days.add(d);
            }
        }
        o.add("days", days);
        return o;
    }

    /**
     * The slots of a day that the day list shows, those with a snapshot or a tombstone. The events
     * route takes its windows from the same list, so each event of the day belongs to exactly one row.
     */
    static List<String> heldSlots(TimelineStore store, String world, String date) {
        List<String> held = new ArrayList<>();
        for (String slot : store.slotsOf(world, date)) {
            if (store.slotHasSnapshot(world, slot) || store.slotPruned(world, slot)) {
                held.add(slot);
            }
        }
        return held;
    }

    /** One slot of the day list. events is added by the caller. */
    private JsonObject slotRow(String world, String date, String slot, int[] window, TimelineStore store, Timeline t) {
        JsonObject d = new JsonObject();
        boolean snap = store.slotHasSnapshot(world, slot);
        JsonObject sum = snap ? store.slotSummary(world, slot) : null;
        d.addProperty("slot", slot);
        d.addProperty("date", date);
        int hour = Slots.hour(slot);
        d.addProperty("hour", hour >= 0 ? (Integer) hour : null);
        int[] w = window == null ? new int[]{0, 24} : window;
        JsonArray wa = new JsonArray();
        wa.add(w[0]);
        wa.add(w[1]);
        d.add("window", wa);
        d.addProperty("snapshot", snap);
        d.addProperty("pruned", store.slotPruned(world, slot));
        long takenAt = sum == null ? 0 : (long) Json.num(sum, "takenAt", 0);
        d.addProperty("takenAt", takenAt > 0 ? (Long) takenAt : null);
        TimelineStore.Counts n = snap ? store.counts(world, slot) : null;
        d.addProperty("tiles", n == null ? 0 : n.tiles());
        d.addProperty("carried", n == null ? 0 : n.carried());
        d.addProperty("changed", n == null ? 0 : n.changed());
        d.addProperty("partial", sum != null && Json.bool(sum, "partial"));
        java.nio.file.Path dir = store.worldDir(world).resolve(slot);
        d.addProperty("stats", snap && java.nio.file.Files.isRegularFile(dir.resolve(TimelineStore.STATS)));
        d.add("isoViews", snap ? dev.ryvrook.atlas.iso.IsoSnapshots.views(world, slot, dir) : new JsonArray());
        return d;
    }

    private record Counts(long modified, long size, int[] hours) {
    }

    private final Map<String, Counts> hourCounts = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Counts> e) {
            return size() > 512;
        }
    });

    /** Events of a day per server local hour, cached while the events file is unchanged. */
    private int[] hourCounts(Timeline t, String world, String date) {
        java.nio.file.Path f = t.store().eventsFile(world, date);
        long modified = -1;
        long size = -1;
        try {
            if (java.nio.file.Files.isRegularFile(f)) {
                modified = java.nio.file.Files.getLastModifiedTime(f).toMillis();
                size = java.nio.file.Files.size(f);
            }
        } catch (IOException ignored) {
            // Counted again.
        }
        String key = world + "|" + date;
        Counts c = hourCounts.get(key);
        if (c != null && c.modified() == modified && c.size() == size && !date.equals(t.today())) {
            return c.hours();
        }
        int[] out = new int[24];
        for (EventLog.Line l : t.events().read(world, date)) {
            out[java.time.Instant.ofEpochMilli(l.at()).atZone(t.zone()).getHour()]++;
        }
        hourCounts.put(key, new Counts(modified, size, out));
        return out;
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
        return kind.equals("join") || kind.equals("leave") || kind.equals("kill") || kind.equals("death")
                || kind.startsWith("feats");
    }

    /**
     * The place of an event as a viewer may see it, as {x, z} with null parts when it is unknown or
     * withheld. withheld drops the place of any kind, as for a player hidden from the viewer.
     * Other people's personal events are snapped to the corner of their chunk for viewers who are not
     * admins, and a viewer's own events keep their exact place.
     */
    public static Integer[] place(String kind, Integer x, Integer z, boolean admin, boolean self, boolean withheld) {
        if (x == null || z == null) {
            return new Integer[]{null, null};
        }
        if (admin || self) {
            return new Integer[]{x, z};
        }
        if (withheld) {
            return new Integer[]{null, null};
        }
        if (personal(kind)) {
            return new Integer[]{Math.floorDiv(x, 32) * 32, Math.floorDiv(z, 32) * 32};
        }
        return new Integer[]{x, z};
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
        String who = l.who();
        String text = l.text();
        boolean namesHidden = false;
        if (!v.admin()) {
            if (hidden && who != null) {
                text = replaceName(text, who);
                who = Payloads.HIDDEN_NAME;
            }
            for (String n : hiddenNamesOriginal()) {
                if (self && n.equalsIgnoreCase(v.name())) {
                    continue;
                }
                String before = text;
                text = replaceName(text, n);
                namesHidden |= !java.util.Objects.equals(before, text);
            }
        }
        // The place goes when the player is hidden from the viewer, when another hidden player is named in
        // the event, such as the killer standing next to the victim, and when the layer is limited to the
        // viewer's faction and the player is in another one.
        boolean withheld = hidden || namesHidden
                || (layer != null && v.factionLimited(cfg(), layer) && !v.sameFaction(id == null ? null : atlas.factionOf(id)));
        Integer[] at = place(l.kind(), l.x(), l.z(), v.admin(), self, withheld);
        Integer x = at[0];
        Integer z = at[1];
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

    // ---- export ------------------------------------------------------------

    /** Longest range of one export, in days. */
    static final int MAX_EXPORT_DAYS = 90;
    private final java.util.concurrent.atomic.AtomicBoolean exporting = new java.util.concurrent.atomic.AtomicBoolean();

    /** Why a from and to pair cannot be exported, or null when it can. Both are required ISO dates. */
    static String exportRangeError(String from, String to) {
        if (from == null || to == null || !TimelineStore.validDate(from) || !TimelineStore.validDate(to)) {
            return "Give from and to as dates such as 2026-01-31.";
        }
        LocalDate a = LocalDate.parse(from);
        LocalDate b = LocalDate.parse(to);
        if (b.isBefore(a)) {
            return "The date from must not come after to.";
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(a, b) + 1 > MAX_EXPORT_DAYS) {
            return "An export covers at most " + MAX_EXPORT_DAYS + " days.";
        }
        return null;
    }

    /** Takes the single export slot. False while another export runs. */
    boolean beginExport() {
        return exporting.compareAndSet(false, true);
    }

    void endExport() {
        exporting.set(false);
    }

    /**
     * GET api/timeline/{world}/export?from=&to=, admins only. Streams a zip of every day in the range
     * with its tiles.json, stats.json, events.jsonl and isometric views, and every tile image those
     * days use once under blobs/. Nothing is held in memory but the list of image hashes. One export
     * runs at a time, a second answers 409. ApiServer counts it against tileRequestsPerMinute per address.
     */
    public void export(com.sun.net.httpserver.HttpExchange ex, Viewer v, String world, Map<String, String> query)
            throws IOException {
        Atlas.ApiResult g = gate(v, world, "timeline");
        if (g == null && !v.admin()) {
            g = error(403, "Only admins may export the timeline.");
        }
        String from = query.get("from");
        String to = query.get("to");
        String bad = g == null ? exportRangeError(from, to) : null;
        if (g == null && bad != null) {
            g = error(400, bad);
        }
        if (g == null && !beginExport()) {
            ex.getResponseHeaders().set("Retry-After", "30");
            g = error(409, "Another timeline export is running. Try again when it is done.");
        }
        if (g != null) {
            sendJson(ex, g);
            return;
        }
        try {
            streamExport(ex, world, LocalDate.parse(from), LocalDate.parse(to));
        } finally {
            endExport();
        }
    }

    private void streamExport(com.sun.net.httpserver.HttpExchange ex, String world, LocalDate from, LocalDate to)
            throws IOException {
        TimelineStore store = timeline.get().store();
        com.sun.net.httpserver.Headers h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Content-Type", "application/zip");
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        String name = "atlas-timeline-" + dev.ryvrook.atlas.tiles.TileKeys.safe(world) + "-" + from + "-" + to + ".zip";
        h.set("Content-Disposition", "attachment; filename=\"" + name + "\"");
        if ("HEAD".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(200, -1);
            return;
        }
        ex.sendResponseHeaders(200, 0);
        long started = System.currentTimeMillis();
        Counting counted = new Counting(ex.getResponseBody());
        int[] written;
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(counted)) {
            written = writeExport(zip, store, world, from, to);
        } finally {
            ex.getResponseBody().close();
        }
        int days = written[0];
        int images = written[1];
        log("Atlas exported the timeline of %s from %s to %s, %d %s and %d images, %d bytes in %d ms", world, from, to,
                days, days == 1 ? "day" : "days", images, counted.count, System.currentTimeMillis() - started);
    }

    /**
     * Writes the export of a world from one date to another into zip and returns {days, images}. A
     * snapshot written before tiles were carried over is exported with every tile it shows, the ones it
     * lacks taken from the earlier slots as the tile route does, so each tiles.json stands on its own.
     */
    static int[] writeExport(java.util.zip.ZipOutputStream zip, TimelineStore store, String world, LocalDate from,
                             LocalDate to) throws IOException {
        int days = 0;
        int images = 0;
        java.util.Set<String> hashes = new java.util.LinkedHashSet<>();
        zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
        entry(zip, "README.txt", ("Atlas timeline export of " + world + " from " + from + " to " + to + ". Each folder "
                + "days/<date> holds that day's events.jsonl, and each folder days/<slot> (a date and hour such as "
                + "2026-10-13T16, or a plain date for a day taken by Atlas 1.1 or older) holds that snapshot's tiles.json "
                + "(tile key to image hash, every tile the map showed at that slot), stats.json and its isometric views "
                + "under iso. Every image a tiles.json names is stored once as blobs/<hash>.png.\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            String date = d.toString();
            boolean any = copy(zip, store.eventsFile(world, date), "days/" + date + "/events.jsonl");
            for (String slot : store.slotsOf(world, date)) {
                java.nio.file.Path day = store.worldDir(world).resolve(slot);
                Manifest m = store.slotHasSnapshot(world, slot) ? store.effective(world, slot) : null;
                if (m != null) {
                    entry(zip, "days/" + slot + "/tiles.json",
                            Json.GSON.toJson(m.toJson()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    any = true;
                    hashes.addAll(m.tiles().values());
                }
                any |= copy(zip, day.resolve(TimelineStore.STATS), "days/" + slot + "/stats.json");
                java.nio.file.Path iso = day.resolve("iso");
                if (m != null && java.nio.file.Files.isDirectory(iso)) {
                    try (java.nio.file.DirectoryStream<java.nio.file.Path> s = java.nio.file.Files.newDirectoryStream(iso)) {
                        List<java.nio.file.Path> files = new ArrayList<>();
                        s.forEach(files::add);
                        files.sort(null);
                        for (java.nio.file.Path f : files) {
                            String n = f.getFileName().toString();
                            if (n.endsWith(".png") || n.equals("index.json")) {
                                any |= copy(zip, f, "days/" + slot + "/iso/" + n);
                            }
                        }
                    }
                }
            }
            if (any) {
                days++;
            }
        }
        for (String hash : hashes) {
            if (BlobStore.validHash(hash) && copy(zip, store.blobs().path(hash), "blobs/" + hash + ".png")) {
                images++;
            }
        }
        return new int[]{days, images};
    }

    private static void entry(java.util.zip.ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    /** Copies a file into the zip. False when it does not exist, or went away, as a pruned day may. */
    private static boolean copy(java.util.zip.ZipOutputStream zip, java.nio.file.Path file, String name) throws IOException {
        java.io.InputStream in;
        try {
            in = java.nio.file.Files.newInputStream(file);
        } catch (java.nio.file.NoSuchFileException e) {
            return false;
        }
        try (in) {
            zip.putNextEntry(new java.util.zip.ZipEntry(name));
            in.transferTo(zip);
            zip.closeEntry();
        }
        return true;
    }

    private static void sendJson(com.sun.net.httpserver.HttpExchange ex, Atlas.ApiResult r) throws IOException {
        com.sun.net.httpserver.Headers h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Cache-Control", "no-store");
        byte[] body = Json.GSON.toJson(r.body()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if ("HEAD".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(r.status(), -1);
            return;
        }
        ex.sendResponseHeaders(r.status(), body.length);
        try (java.io.OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static void log(String format, Object... args) {
        try {
            com.hypixel.hytale.logger.HytaleLogger.get("Atlas").at(java.util.logging.Level.INFO)
                    .log(String.format(Locale.ROOT, format, args));
        } catch (RuntimeException | LinkageError ignored) {
            // No logging backend, as in unit tests.
        }
    }

    /** Counts the bytes written through it. */
    private static final class Counting extends java.io.FilterOutputStream {
        long count;

        Counting(java.io.OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
