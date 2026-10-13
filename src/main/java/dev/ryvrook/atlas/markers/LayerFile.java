package dev.ryvrook.atlas.markers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.ryvrook.atlas.api.CircleMarker;
import dev.ryvrook.atlas.api.Marker;
import dev.ryvrook.atlas.api.MarkerLayer;
import dev.ryvrook.atlas.api.PointMarker;
import dev.ryvrook.atlas.api.PolygonMarker;
import dev.ryvrook.atlas.api.PolylineMarker;
import dev.ryvrook.atlas.api.ShapeMarker;
import dev.ryvrook.atlas.api.Tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The JSON form of one layer, used by the drop folder and admin.json. The fields match the API.
 *
 * <pre>
 * {"label": "Shops", "tier": "public", "defaultHidden": false, "priority": 0, "minZoom": null, "maxZoom": null,
 *  "world": "default",
 *  "markers": [
 *    {"id": "market", "kind": "point", "x": 120, "z": -40, "y": 64, "icon": "Coins", "label": "Market",
 *     "popup": "Open at dawn", "tint": "#ffcc00"},
 *    {"id": "road", "kind": "polyline", "points": [[0, 0], [100, 40]], "tint": "#ffffff", "weight": 3},
 *    {"id": "farm", "kind": "polygon", "points": [[0, 0], [50, 0], [50, 50]], "fill": "#33aa3355"},
 *    {"id": "spawn", "kind": "circle", "x": 0, "z": 0, "radius": 64, "world": "default"}]}
 * </pre>
 *
 * A marker without a world uses the top level world. A popup from a file is always escaped, raw HTML
 * popups are only for plugins through the API.
 */
public final class LayerFile {
    /** Largest file read, in bytes. */
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final Set<String> TOP = Set.of("label", "tier", "defaultHidden", "priority", "minZoom", "maxZoom",
            "world", "markers");
    private static final Set<String> MARKER = Set.of("id", "kind", "world", "x", "y", "z", "icon", "label", "popup",
            "popupHtml", "tint", "fill", "weight", "faction", "points", "radius");

    private static final com.google.gson.Gson PRETTY = new com.google.gson.GsonBuilder().disableHtmlEscaping()
            .setPrettyPrinting().serializeNulls().create();

    private LayerFile() {
    }

    /** Thrown when a file cannot be used. The message says why in one line. */
    public static final class Invalid extends Exception {
        public Invalid(String message) {
            super(message);
        }
    }

    /**
     * Parses a layer into a layer that reports changes to nobody. The caller swaps it in whole.
     * Unknown keys are refused so a misspelt field is noticed.
     */
    public static MarkerLayer parse(String pluginId, String layerId, String text) throws Invalid {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text == null ? "" : text);
        } catch (JsonParseException e) {
            throw new Invalid("it is not valid JSON");
        }
        if (!parsed.isJsonObject()) {
            throw new Invalid("it must hold one JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        for (String k : root.keySet()) {
            if (!TOP.contains(k)) {
                throw new Invalid("it has an unknown key " + k);
            }
        }
        MarkerLayer layer;
        try {
            layer = ApiImpl.detached(pluginId, layerId);
            String label = string(root, "label");
            if (label != null) {
                layer.label(label);
            }
            String tierRaw = string(root, "tier");
            if (tierRaw != null) {
                Tier t = Tier.parse(tierRaw);
                if (t == null) {
                    throw new Invalid("tier is " + tierRaw + " but must be public, linked, faction or admin");
                }
                layer.tier(t);
            }
            Boolean dh = bool(root, "defaultHidden");
            if (dh != null) {
                layer.defaultHidden(dh);
            }
            Double prio = number(root, "priority");
            if (prio != null) {
                layer.priority(whole("priority", prio));
            }
            Double minZ = number(root, "minZoom");
            layer.minZoom(minZ == null ? null : whole("minZoom", minZ));
            Double maxZ = number(root, "maxZoom");
            layer.maxZoom(maxZ == null ? null : whole("maxZoom", maxZ));
        } catch (IllegalArgumentException e) {
            throw new Invalid(e.getMessage());
        }
        String world = string(root, "world");
        JsonElement list = root.get("markers");
        if (list == null || list.isJsonNull()) {
            return layer;
        }
        if (!list.isJsonArray()) {
            throw new Invalid("markers must be a list");
        }
        int i = 0;
        for (JsonElement e : list.getAsJsonArray()) {
            i++;
            try {
                if (!e.isJsonObject()) {
                    throw new Invalid("it is not an object");
                }
                marker(layer, world, e.getAsJsonObject());
            } catch (Invalid | IllegalArgumentException | IllegalStateException ex) {
                throw new Invalid("marker " + i + " is invalid, " + ex.getMessage());
            }
        }
        return layer;
    }

    private static void marker(MarkerLayer layer, String defaultWorld, JsonObject m) throws Invalid {
        for (String k : m.keySet()) {
            if (!MARKER.contains(k)) {
                throw new Invalid("it has an unknown key " + k);
            }
        }
        String id = string(m, "id");
        if (id == null) {
            throw new Invalid("id is missing");
        }
        String world = string(m, "world");
        if (world == null) {
            world = defaultWorld;
        }
        if (world == null) {
            throw new Invalid("world is missing here and at the top of the file");
        }
        String kind = string(m, "kind");
        if (kind == null) {
            kind = "point";
        }
        Marker<?> made;
        switch (kind) {
            case "point" -> {
                PointMarker p = layer.point(world, id, required(m, "x"), required(m, "z"));
                p.y(number(m, "y"));
                p.icon(string(m, "icon"));
                made = p;
            }
            case "polyline" -> made = shape(layer.polyline(world, id, points(m)), m);
            case "polygon" -> made = shape(layer.polygon(world, id, points(m)), m);
            case "circle" -> made = shape(layer.circle(world, id, required(m, "x"), required(m, "z"), required(m, "radius")), m);
            default -> throw new Invalid("kind is " + kind + " but must be point, polyline, polygon or circle");
        }
        if (!"point".equals(kind) && (m.has("icon") && !m.get("icon").isJsonNull())) {
            throw new Invalid("only points take an icon");
        }
        made.label(string(m, "label"));
        if (m.has("popupHtml")) {
            // Files are written by tools that may copy text players typed, so only plugins may send raw HTML.
            throw new Invalid("popupHtml is only for plugins through the Java API, a file popup is always escaped, use popup");
        }
        made.popup(string(m, "popup"));
        made.tint(string(m, "tint"));
        made.faction(string(m, "faction"));
    }

    private static <T extends ShapeMarker<T>> T shape(T s, JsonObject m) throws Invalid {
        s.fill(string(m, "fill"));
        s.weight(number(m, "weight"));
        return s;
    }

    private static List<double[]> points(JsonObject m) throws Invalid {
        JsonElement e = m.get("points");
        if (e == null || !e.isJsonArray()) {
            throw new Invalid("points must be a list of [x, z] pairs");
        }
        List<double[]> out = new ArrayList<>();
        for (JsonElement p : e.getAsJsonArray()) {
            if (!p.isJsonArray() || p.getAsJsonArray().size() != 2 || !isNumber(p.getAsJsonArray().get(0))
                    || !isNumber(p.getAsJsonArray().get(1))) {
                throw new Invalid("points must be a list of [x, z] pairs");
            }
            out.add(new double[]{p.getAsJsonArray().get(0).getAsDouble(), p.getAsJsonArray().get(1).getAsDouble()});
        }
        return out;
    }

    private static int whole(String key, double v) throws Invalid {
        if (v != Math.rint(v) || Math.abs(v) > 1_000_000) {
            throw new Invalid(key + " must be a whole number");
        }
        return (int) v;
    }

    private static double required(JsonObject o, String key) throws Invalid {
        Double v = number(o, key);
        if (v == null) {
            throw new Invalid(key + " is missing");
        }
        return v;
    }

    private static boolean isNumber(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    private static Double number(JsonObject o, String key) throws Invalid {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!isNumber(e)) {
            throw new Invalid(key + " must be a number");
        }
        return e.getAsDouble();
    }

    private static String string(JsonObject o, String key) throws Invalid {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new Invalid(key + " must be a string");
        }
        return e.getAsString();
    }

    private static Boolean bool(JsonObject o, String key) throws Invalid {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new Invalid(key + " must be true or false");
        }
        return e.getAsBoolean();
    }

    /** The file form of a layer, which parse reads back to the same layer. */
    public static String write(MarkerLayer layer) {
        JsonObject root = new JsonObject();
        JsonArray list = new JsonArray();
        synchronized (layer) {
            root.addProperty("label", layer.label());
            root.addProperty("tier", layer.tier().id());
            root.addProperty("defaultHidden", layer.defaultHidden());
            root.addProperty("priority", layer.priority());
            root.add("minZoom", layer.minZoom() == null ? JsonNull.INSTANCE : new JsonPrimitive(layer.minZoom()));
            root.add("maxZoom", layer.maxZoom() == null ? JsonNull.INSTANCE : new JsonPrimitive(layer.maxZoom()));
            for (String world : layer.worlds()) {
                for (Marker<?> m : layer.markers(world)) {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", m.id());
                    o.addProperty("kind", m.kind());
                    o.addProperty("world", world);
                    if (m instanceof PointMarker p) {
                        o.addProperty("x", p.x());
                        o.addProperty("z", p.z());
                        if (p.y() != null) {
                            o.addProperty("y", p.y());
                        }
                        if (p.icon() != null) {
                            o.addProperty("icon", p.icon());
                        }
                    } else if (m instanceof CircleMarker c) {
                        o.addProperty("x", c.x());
                        o.addProperty("z", c.z());
                        o.addProperty("radius", c.radius());
                    } else if (m instanceof PolylineMarker l) {
                        o.add("points", pairs(l.points()));
                    } else if (m instanceof PolygonMarker g) {
                        o.add("points", pairs(g.points()));
                    }
                    if (m instanceof ShapeMarker<?> s) {
                        if (s.fill() != null) {
                            o.addProperty("fill", s.fill());
                        }
                        if (s.weight() != null) {
                            o.addProperty("weight", s.weight());
                        }
                    }
                    if (m.label() != null) {
                        o.addProperty("label", m.label());
                    }
                    if (m.popup() != null) {
                        o.addProperty(m.popupTrusted() ? "popupHtml" : "popup", m.popup());
                    }
                    if (m.tint() != null) {
                        o.addProperty("tint", m.tint());
                    }
                    if (m.faction() != null) {
                        o.addProperty("faction", m.faction());
                    }
                    list.add(o);
                }
            }
        }
        root.add("markers", list);
        return PRETTY.toJson(root);
    }

    static JsonArray pairs(List<double[]> pts) {
        JsonArray a = new JsonArray();
        for (double[] p : pts) {
            JsonArray pair = new JsonArray();
            pair.add(p[0]);
            pair.add(p[1]);
            a.add(pair);
        }
        return a;
    }
}
