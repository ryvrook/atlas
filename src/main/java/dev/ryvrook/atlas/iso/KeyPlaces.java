package dev.ryvrook.atlas.iso;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.config.AtlasConfig.KeyPlace;
import dev.ryvrook.atlas.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Key places, the curated set of iso.keyPlaces that the first snapshot of each day draws from all four
 * directions. This class holds the rules that need no running server, so they can be tested on
 * their own. The fingerprint of a place, the choice between drawing a place again and reusing the
 * picture of the day before, the file names, and the change of the list in atlas.json.
 *
 * <p>A change through a command or the Admin tab is checked against the limits in force, written
 * into the iso object of atlas.json with everything else in the file kept as it is, checked again by
 * parsing the whole new file, and only then applied. A file that does not parse or would not be
 * valid afterwards is left alone and the change is refused.
 */
public final class KeyPlaces {
    /** The four directions, as IsoView numbers them. */
    public static final int[] AZIMUTHS = {0, 90, 180, 270};
    /** The scale asked for. IsoView lowers it for a wide radius, 2 reaches up to radius 8. */
    public static final int SCALE = 2;
    private static final Pattern FILE_LABEL = Pattern.compile("([a-z0-9_-]{1,40})-(0|90|180|270)");

    private KeyPlaces() {
    }

    /** Thrown when a change cannot be made. status is the HTTP answer for the Admin tab. */
    public static final class Problem extends Exception {
        private final int status;

        public Problem(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    // ---- names -----------------------------------------------------------------

    /** The name of the image of one direction in a day folder, without .png, such as spawn-90. */
    public static String fileLabel(String label, int azimuth) {
        return label + "-" + azimuth;
    }

    /** True for a name fileLabel can give. */
    public static boolean validFileLabel(String s) {
        return s != null && FILE_LABEL.matcher(s).matches();
    }

    /** True for an azimuth of AZIMUTHS. */
    public static boolean validAzimuth(int az) {
        return az == 0 || az == 90 || az == 180 || az == 270;
    }

    /**
     * The view of one direction of a place. The radius is held to isoMaxRadius and the scale lowered
     * by the usual rule for a wide radius. Null when the place lies outside the world.
     */
    public static IsoView view(KeyPlace p, int azimuth, int maxRadius) {
        int r = Math.max(1, Math.min(p.radius, Math.min(IsoView.MAX_RADIUS, maxRadius)));
        return IsoView.of(p.world, p.x, p.z, Integer.toString(r), Integer.toString(azimuth), Integer.toString(SCALE),
                maxRadius).view();
    }

    // ---- fingerprint -------------------------------------------------------------

    /**
     * The fingerprint of a whole view, see digest(long[]), with the renderer style mixed in so a key
     * place drawn by an older renderer is drawn again once.
     */
    static long digest(IsoCache.Print print) {
        if (print == null) {
            return 0;
        }
        long h = mix(digest(print.terrain()), IsoRaster.STYLE);
        return h == 0 ? 1 : h;
    }

    /**
     * One number for the hashes of every chunk of a view, FNV-1a over the count and each hash in row
     * order, so a chunk that changed, appeared or went missing changes it. The same chunks give the
     * same number from every direction. 0 is never returned, it means unknown.
     */
    public static long digest(long[] chunks) {
        long h = 0xcbf29ce484222325L;
        int n = chunks == null ? 0 : chunks.length;
        h = mix(h, n);
        for (int i = 0; i < n; i++) {
            h = mix(h, chunks[i]);
        }
        return h == 0 ? 1 : h;
    }

    private static long mix(long h, long v) {
        for (int i = 0; i < 8; i++) {
            h ^= (v >>> (i * 8)) & 0xff;
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** The fingerprint as kept in index.json, sixteen hex digits. */
    public static String printText(long digest) {
        return String.format(Locale.ROOT, "%016x", digest);
    }

    // ---- reuse -------------------------------------------------------------------

    /**
     * True when the pictures of the previous render may stand for today, so nothing is drawn again.
     * previous holds the index entries of that render, one per direction. Every direction must be
     * there with its image on disk, drawn complete around the same chunk with the same radius and
     * scale, and carry the fingerprint read now. A read that missed a chunk never counts as the same.
     */
    public static boolean unchanged(List<JsonObject> previous, java.util.function.Predicate<JsonObject> fileExists,
                                    IsoView now, long digest, boolean partial) {
        if (previous == null || now == null || partial || digest == 0) {
            return false;
        }
        String print = printText(digest);
        for (int az : AZIMUTHS) {
            JsonObject e = null;
            for (JsonObject o : previous) {
                if (o != null && (int) Json.num(o, "azimuth", -1) == az) {
                    e = o;
                }
            }
            if (e == null || !print.equals(Json.str(e, "print")) || Json.bool(e, "partial")) {
                return false;
            }
            int x = (int) Json.num(e, "x", Integer.MIN_VALUE);
            int z = (int) Json.num(e, "z", Integer.MIN_VALUE);
            if (Math.floorDiv(x, 32) != now.cx() || Math.floorDiv(z, 32) != now.cz()
                    || (int) Json.num(e, "radius", -1) != now.radius() || (int) Json.num(e, "scale", -1) != now.scale()) {
                return false;
            }
            if (!fileExists.test(e)) {
                return false;
            }
        }
        return true;
    }

    // ---- the list in atlas.json ----------------------------------------------------

    /** Adds a place, or replaces the one with the same label where it stands. Returns true when it was new. */
    public static synchronized boolean put(Atlas atlas, KeyPlace p) throws Problem {
        AtlasConfig cfg = atlas.config();
        List<KeyPlace> next = new ArrayList<>(cfg.isoKeyPlaces());
        int at = indexOf(next, p.label);
        if (at < 0 && next.size() >= cfg.isoKeyPlaceLimit()) {
            throw new Problem(409, "There are already " + next.size() + " key places, the most isoKeyPlaceLimit allows. "
                    + "Remove one first.");
        }
        if (at < 0) {
            next.add(p);
        } else {
            next.set(at, p);
        }
        save(atlas, next);
        return at < 0;
    }

    /** Removes the place with that label. False when there is none. */
    public static synchronized boolean remove(Atlas atlas, String label) throws Problem {
        List<KeyPlace> next = new ArrayList<>(atlas.config().isoKeyPlaces());
        int at = indexOf(next, label);
        if (at < 0) {
            return false;
        }
        next.remove(at);
        save(atlas, next);
        return true;
    }

    /** The place with that label, or null. */
    public static KeyPlace find(AtlasConfig cfg, String label) {
        for (KeyPlace p : cfg.isoKeyPlaces()) {
            if (p.label.equals(label)) {
                return p;
            }
        }
        return null;
    }

    private static int indexOf(List<KeyPlace> l, String label) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).label.equals(label)) {
                return i;
            }
        }
        return -1;
    }

    /** Writes the list into atlas.json and applies it, or refuses and changes nothing. */
    private static void save(Atlas atlas, List<KeyPlace> next) throws Problem {
        AtlasConfig cfg = atlas.config();
        String problem = AtlasConfig.keyPlaceProblem(next, cfg.isoKeyPlaceLimit());
        if (problem != null) {
            throw new Problem(400, problem);
        }
        Path file = atlas.dataDir().resolve(AtlasConfig.FILE_NAME);
        String text = withKeyPlaces(read(file), next);
        try {
            AtlasConfig.parse(text);
        } catch (AtlasConfig.InvalidConfigException e) {
            throw new Problem(409, "atlas.json would not be valid with that change, " + e.getMessage() + ". Fix the file first.");
        }
        try {
            Json.writeAtomic(file, text);
        } catch (IOException e) {
            throw new Problem(500, "atlas.json could not be written, " + e.getMessage() + ".");
        }
        cfg.iso.keyPlaces = List.copyOf(next);
    }

    private static String read(Path file) throws Problem {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new Problem(500, "atlas.json could not be read, " + e.getMessage() + ".");
        }
    }

    /**
     * The text of atlas.json with iso.keyPlaces set to the list and everything else as it was. A file
     * that does not parse is refused rather than replaced.
     */
    static String withKeyPlaces(String text, List<KeyPlace> places) throws Problem {
        JsonObject root;
        if (text == null || text.isBlank()) {
            root = new JsonObject();
        } else {
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(text);
            } catch (JsonParseException e) {
                throw new Problem(409, "atlas.json is not valid JSON, so it was left alone. Fix the file first.");
            }
            if (!parsed.isJsonObject()) {
                throw new Problem(409, "atlas.json must hold one JSON object. Fix the file first.");
            }
            root = parsed.getAsJsonObject();
        }
        JsonElement iso = root.get("iso");
        if (iso != null && !iso.isJsonObject()) {
            throw new Problem(409, "iso in atlas.json must be an object. Fix the file first.");
        }
        JsonObject isoObj = iso == null ? new JsonObject() : iso.getAsJsonObject();
        isoObj.add("keyPlaces", Json.GSON.toJsonTree(places));
        root.add("iso", isoObj);
        return Json.PRETTY.toJson(root);
    }

    /** One line per place for /atlas key list. */
    public static List<String> lines(AtlasConfig cfg) {
        List<String> out = new ArrayList<>();
        for (KeyPlace p : cfg.isoKeyPlaces()) {
            out.add(p.label + (p.name == null || p.name.isBlank() ? "" : ", " + p.name) + ", " + p.world + " at " + p.x + " "
                    + p.z + ", radius " + p.radius + (p.radius > cfg.isoMaxRadius() ? " (drawn at " + cfg.isoMaxRadius() + ")" : ""));
        }
        return out;
    }

    /** A place as JSON for the Admin tab and the map. */
    public static JsonObject json(KeyPlace p) {
        JsonObject o = new JsonObject();
        o.addProperty("label", p.label);
        o.addProperty("name", p.name == null ? "" : p.name);
        o.addProperty("world", p.world);
        o.addProperty("x", p.x);
        o.addProperty("z", p.z);
        o.addProperty("radius", p.radius);
        return o;
    }
}
