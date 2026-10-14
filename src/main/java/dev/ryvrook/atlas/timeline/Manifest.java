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
 * The tiles of one world in one snapshot slot, as tile key to blob hash. A tile key is "zoom/x/z" in
 * the same tile coordinates as the live tile route. date holds the slot, such as 2026-10-13T16, or a
 * plain date for a day folder of Atlas 1.0 and 1.1. previous is the slot before it that the world
 * holds, and changed lists the keys whose hash differs from it, or every key in the first slot.
 *
 * <p>A complete manifest names every tile the world had at that slot. A tile missing from the tile
 * folder while the snapshot ran is carried over from the slot before, and carried counts those.
 * composed counts the tiles of zoom 1 to 4 the snapshot drew itself from the zoom below. Manifests
 * written before Atlas carried tiles over are not complete, so a reader walks back along previous
 * to find the tiles they lack, see TimelineStore.effectiveTiles.
 */
public record Manifest(String world, String date, long takenAt, String previous, boolean partial, int skipped,
                       int newBlobs, Map<String, String> tiles, List<String> changed, int carried, int composed,
                       boolean complete) {

    public Manifest {
        tiles = Collections.unmodifiableMap(new TreeMap<>(tiles));
        changed = List.copyOf(changed);
    }

    /** A manifest as Atlas wrote them before tiles were carried over, not complete. */
    public Manifest(String world, String date, long takenAt, String previous, boolean partial, int skipped,
                    int newBlobs, Map<String, String> tiles, List<String> changed) {
        this(world, date, takenAt, previous, partial, skipped, newBlobs, tiles, changed, 0, 0, false);
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

    /** The zoom the live renderer draws from the chunk images and keeps on disk. Every other zoom is drawn from it. */
    public static final int BASE_ZOOM = 0;

    /**
     * Tiles of next that show a block change against prev. Only tiles of BASE_ZOOM count, one whose
     * hash differs from prev or that prev lacks, which is newly explored ground. The tiles of the other
     * zooms are drawn from the base zoom, so a block change always shows there too, while a tile of
     * another zoom can appear or get new bytes without any block change, because the snapshot drew it
     * to fill a gap or a browser asked for it for the first time. A null prev counts every base tile.
     */
    public static int blockChanges(Map<String, String> prev, Map<String, String> next) {
        int n = 0;
        for (Map.Entry<String, String> e : next.entrySet()) {
            int[] p = parseKey(e.getKey());
            if (p == null || p[0] != BASE_ZOOM) {
                continue;
            }
            String old = prev == null ? null : prev.get(e.getKey());
            if (!e.getValue().equals(old)) {
                n++;
            }
        }
        return n;
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
        o.addProperty("date", date == null ? null : Slots.date(date));
        o.addProperty("slot", date);
        o.addProperty("takenAt", takenAt);
        o.addProperty("previous", previous);
        o.addProperty("partial", partial);
        o.addProperty("skipped", skipped);
        o.addProperty("newBlobs", newBlobs);
        o.addProperty("count", tiles.size());
        o.addProperty("carried", carried);
        o.addProperty("composed", composed);
        o.addProperty("complete", complete);
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
        o.addProperty("carried", carried);
        o.addProperty("complete", complete);
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
        String slot = Json.str(o, "slot");
        return new Manifest(Json.str(o, "world"), slot != null ? slot : Json.str(o, "date"), (long) Json.num(o, "takenAt", 0),
                Json.str(o, "previous"), Json.bool(o, "partial"), (int) Json.num(o, "skipped", 0),
                (int) Json.num(o, "newBlobs", 0), tiles, changed, (int) Json.num(o, "carried", 0),
                (int) Json.num(o, "composed", 0), Json.bool(o, "complete"));
    }

    public static Manifest read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        return e.isJsonObject() ? fromJson(e.getAsJsonObject()) : null;
    }
}
