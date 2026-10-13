package dev.ryvrook.atlas.iso;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.auth.Viewer;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.config.AtlasConfig.KeyPlace;

import java.util.List;

/**
 * The admin routes of the key places, reached from ApiServer for api/admin/keyplaces.
 *
 * <ul>
 *   <li>GET api/admin/keyplaces, the list and its limit</li>
 *   <li>POST api/admin/keyplaces with a JSON body of world, x, z, label and optional radius and name,
 *   adds a place or moves the one with the same label</li>
 *   <li>DELETE api/admin/keyplaces/{label}, removes a place</li>
 * </ul>
 *
 * <p>Only admins, by the admin token or a linked session with the admin permission. Every answer
 * carries the whole list, so the page draws it again from the answer alone.
 */
public final class KeyPlaceRoutes {
    private KeyPlaceRoutes() {
    }

    /** The answer for the request, or null when the path is not one of these routes. */
    public static Atlas.ApiResult handle(Atlas atlas, String method, List<String> parts, String body, Viewer viewer) {
        if (parts.size() < 2 || !"admin".equals(parts.get(0)) || !"keyplaces".equals(parts.get(1)) || parts.size() > 3) {
            return null;
        }
        if (!viewer.admin()) {
            return Atlas.ApiResult.error(403, "Only admins may change the key places.");
        }
        try {
            switch (method) {
                case "GET", "HEAD" -> {
                    if (parts.size() != 2) {
                        return Atlas.ApiResult.error(404, "Not found.");
                    }
                    return Atlas.ApiResult.ok(list(atlas.config(), null));
                }
                case "POST" -> {
                    if (parts.size() != 2) {
                        return Atlas.ApiResult.error(404, "Not found.");
                    }
                    KeyPlace p = parse(body);
                    if (!atlas.worldExists(p.world) || !atlas.config().worldEnabled(p.world)) {
                        return Atlas.ApiResult.error(400, "No loaded and enabled world is called " + p.world + ".");
                    }
                    boolean added = KeyPlaces.put(atlas, p);
                    JsonObject o = list(atlas.config(), added ? "added" : "moved");
                    o.add("keyPlace", KeyPlaces.json(p));
                    return Atlas.ApiResult.ok(o);
                }
                case "DELETE" -> {
                    if (parts.size() != 3) {
                        return Atlas.ApiResult.error(400, "Name the key place to remove, as in api/admin/keyplaces/{label}.");
                    }
                    String label = parts.get(2);
                    if (!KeyPlaces.remove(atlas, label)) {
                        return Atlas.ApiResult.error(404, "There is no key place " + label + ".");
                    }
                    return Atlas.ApiResult.ok(list(atlas.config(), "removed"));
                }
                default -> {
                    return Atlas.ApiResult.error(405, "Use GET, POST or DELETE here.");
                }
            }
        } catch (KeyPlaces.Problem e) {
            return Atlas.ApiResult.error(e.status(), e.getMessage());
        }
    }

    /** The answer body with every place, the limit and what changed. */
    static JsonObject list(AtlasConfig c, String changed) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        if (changed != null) {
            o.addProperty("changed", changed);
        }
        o.addProperty("limit", c.isoKeyPlaceLimit());
        o.addProperty("maxRadius", Math.min(KeyPlace.MAX_RADIUS, c.isoMaxRadius()));
        o.addProperty("defaultRadius", KeyPlace.DEFAULT_RADIUS);
        JsonArray a = new JsonArray();
        for (KeyPlace p : c.isoKeyPlaces()) {
            a.add(KeyPlaces.json(p));
        }
        o.add("keyPlaces", a);
        return o;
    }

    /**
     * Reads a place from a POST body. world, x, z and label are needed, radius defaults to 6 and name
     * to empty. Whole numbers only, a wrong type or range is refused with a sentence.
     */
    static KeyPlace parse(String body) throws KeyPlaces.Problem {
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(body == null ? "" : body);
            if (!e.isJsonObject()) {
                throw new KeyPlaces.Problem(400, "Send the key place as a JSON object.");
            }
            o = e.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new KeyPlaces.Problem(400, "Send the key place as a JSON object.");
        }
        String world = string(o, "world", true);
        String label = string(o, "label", true);
        String name = string(o, "name", false);
        int x = whole(o, "x", null);
        int z = whole(o, "z", null);
        int radius = whole(o, "radius", KeyPlace.DEFAULT_RADIUS);
        if (!AtlasConfig.validIsoLabel(label)) {
            throw new KeyPlaces.Problem(400, "The label must be 1 to 40 lower case letters, digits, dashes or underscores.");
        }
        if (radius < 1 || radius > KeyPlace.MAX_RADIUS) {
            throw new KeyPlaces.Problem(400, "The radius must be a whole number of chunks from 1 to " + KeyPlace.MAX_RADIUS + ".");
        }
        name = name == null ? "" : name.trim();
        if (!AtlasConfig.validKeyPlaceName(name)) {
            throw new KeyPlaces.Problem(400, "The name may hold at most " + KeyPlace.MAX_NAME + " characters.");
        }
        if (Math.abs((long) x) > 30_000_000L || Math.abs((long) z) > 30_000_000L) {
            throw new KeyPlaces.Problem(400, "That position lies outside the world.");
        }
        return new KeyPlace(world.trim(), x, z, radius, label, name);
    }

    private static String string(JsonObject o, String key, boolean required) throws KeyPlaces.Problem {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            if (required) {
                throw new KeyPlaces.Problem(400, "The key place needs " + key + ".");
            }
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new KeyPlaces.Problem(400, key + " must be text.");
        }
        String s = e.getAsString();
        if (required && s.isBlank()) {
            throw new KeyPlaces.Problem(400, "The key place needs " + key + ".");
        }
        return s;
    }

    private static int whole(JsonObject o, String key, Integer def) throws KeyPlaces.Problem {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            if (def == null) {
                throw new KeyPlaces.Problem(400, "The key place needs " + key + ".");
            }
            return def;
        }
        JsonPrimitive p = e.isJsonPrimitive() ? e.getAsJsonPrimitive() : null;
        double d;
        try {
            d = p == null ? Double.NaN : p.isNumber() ? p.getAsDouble() : p.isString() ? Double.parseDouble(p.getAsString().trim()) : Double.NaN;
        } catch (NumberFormatException ex) {
            d = Double.NaN;
        }
        if (Double.isNaN(d) || d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) {
            throw new KeyPlaces.Problem(400, key + " must be a whole number.");
        }
        return (int) d;
    }
}
