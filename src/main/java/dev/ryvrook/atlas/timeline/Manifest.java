package dev.ryvrook.atlas.timeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.atlas.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The tiles of one world on one day, as tile key to blob hash. A tile key is "zoom/x/z" in the
 * same tile coordinates as the live tile route. changed lists the keys whose hash differs from the
 * previous day held for the world, or every key on the first day.
 */
public record Manifest(String world, String date, long takenAt, String previous, boolean partial, int skipped,
                       int newBlobs, Map<String, String> tiles, List<String> changed) {

    public Manifest {
        tiles = Collections.unmodifiableMap(new TreeMap<>(tiles));
        changed = List.copyOf(changed);
    }

    /** Keys whose hash in next differs from prev, new keys included, in key order. Removed keys are not listed. */
    public static List<String> diff(Map<String, String> prev, Map<String, String> next) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(next).entrySet()) {
            String old = prev == null ? null : prev.get(e.getKey());
            if (!e.getValue().equals(old)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    public static String key(int zoom, int x, int z) {
        return zoom + "/" + x + "/" + z;
    }

    /** {zoom, x, z} of a key, or null when it is not one. */
    public static int[] parseKey(String key) {
        String[] p = key.split("/", -1);
        if (p.length != 3) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        o.addProperty("world", world);
        o.addProperty("date", date);
        o.addProperty("takenAt", takenAt);
        o.addProperty("previous", previous);
        o.addProperty("partial", partial);
        o.addProperty("skipped", skipped);
        o.addProperty("newBlobs", newBlobs);
        o.addProperty("count", tiles.size());
        JsonObject t = new JsonObject();
        tiles.forEach(t::addProperty);
        o.add("tiles", t);
        JsonArray c = new JsonArray();
        changed.forEach(c::add);
        o.add("changed", c);
        return o;
    }

    /** The small summary kept next to the manifest so day lists never parse whole manifests. */
    public JsonObject summary() {
        JsonObject o = new JsonObject();
        o.addProperty("takenAt", takenAt);
        o.addProperty("tiles", tiles.size());
        o.addProperty("changed", changed.size());
        o.addProperty("partial", partial);
        o.addProperty("previous", previous);
        return o;
    }

    public static Manifest fromJson(JsonObject o) {
        Map<String, String> tiles = new TreeMap<>();
        JsonObject t = Json.obj(o, "tiles");
        if (t != null) {
            for (Map.Entry<String, JsonElement> e : t.entrySet()) {
                if (e.getValue().isJsonPrimitive() && parseKey(e.getKey()) != null
                        && BlobStore.validHash(e.getValue().getAsString())) {
                    tiles.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        List<String> changed = new ArrayList<>();
        for (JsonElement e : Json.arr(o, "changed")) {
            if (e.isJsonPrimitive() && tiles.containsKey(e.getAsString())) {
                changed.add(e.getAsString());
            }
        }
        return new Manifest(Json.str(o, "world"), Json.str(o, "date"), (long) Json.num(o, "takenAt", 0),
                Json.str(o, "previous"), Json.bool(o, "partial"), (int) Json.num(o, "skipped", 0),
                (int) Json.num(o, "newBlobs", 0), tiles, changed);
    }

    public static Manifest read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        return e.isJsonObject() ? fromJson(e.getAsJsonObject()) : null;
    }
}
