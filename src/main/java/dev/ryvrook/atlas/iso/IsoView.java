package dev.ryvrook.atlas.iso;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One isometric view, a square of chunks around a centre chunk seen from one of four directions at
 * a pixel scale. The scale is lowered to what the radius allows, so the widest views stay within a
 * fixed pixel budget.
 */
public record IsoView(String world, int cx, int cz, int radius, int azimuth, int scale) {
    public static final int MAX_RADIUS = 16;
    public static final int DEFAULT_RADIUS = 4;
    public static final int DEFAULT_SCALE = 2;
    public static final int MAX_SCALE = 3;
    /** Blocks along one side times the scale stay at or under this, about 4400 by 2400 pixels at most. */
    static final int SIDE_PIXEL_BUDGET = 1100;
    /** Views wider than 17 by 17 chunks run one at a time with a pause after them. */
    static final int LARGE_SIDE = 17;
    static final long COORD_LIMIT = 30_000_000L;
    private static final Pattern NAME = Pattern.compile("(-?\\d+)_(-?\\d+)_r(\\d+)_a(\\d+)_s(\\d+)_t(\\d+)(?:_v(\\d+))?\\.png");

    /** The largest scale the radius allows, 1 to 3. */
    public static int maxScale(int radius) {
        int side = (2 * radius + 1) * 32;
        for (int s = MAX_SCALE; s > 1; s--) {
            if (side * s <= SIDE_PIXEL_BUDGET) {
                return s;
            }
        }
        return 1;
    }

    public int side() {
        return 2 * radius + 1;
    }

    public int chunks() {
        return side() * side();
    }

    public boolean large() {
        return side() > LARGE_SIDE;
    }

    /** Block x of the centre of the centre chunk. */
    public int blockX() {
        return cx * 32 + 16;
    }

    public int blockZ() {
        return cz * 32 + 16;
    }

    /** True when the chunk lies inside the square of this view. */
    public boolean covers(int chunkX, int chunkZ) {
        return Math.abs(chunkX - cx) <= radius && Math.abs(chunkZ - cz) <= radius;
    }

    /** The cache file name of this view in a time bucket, drawn by the current renderer. */
    public String fileName(long bucket) {
        return cx + "_" + cz + "_r" + radius + "_a" + azimuth + "_s" + scale + "_t" + bucket + "_v" + IsoRaster.STYLE + ".png";
    }

    /**
     * A cache file name read back, or null when the name is not one. style is the renderer that drew
     * it, 1 for names from before the renderer was marked.
     */
    record Named(IsoView view, long bucket, int style) {
    }

    static Named parseName(String world, String name) {
        Matcher m = NAME.matcher(name);
        if (!m.matches()) {
            return null;
        }
        try {
            IsoView v = new IsoView(world, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
            return new Named(v, Long.parseLong(m.group(6)), m.group(7) == null ? 1 : Integer.parseInt(m.group(7)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A parsed request, the view or the sentence for a 400 answer. */
    public record Parsed(IsoView view, String error) {
    }

    /** Reads x, z, r, az and s of a request. x and z are required. */
    public static Parsed parse(String world, Map<String, String> query, int maxRadius) {
        Long x = number(query.get("x"));
        Long z = number(query.get("z"));
        if (x == null || z == null) {
            return new Parsed(null, "Give the centre as whole block numbers x and z.");
        }
        return of(world, x, z, query.get("r"), query.get("az"), query.get("s"), maxRadius);
    }

    /** Builds a view around a block position from the optional r, az and s values of a request. */
    public static Parsed of(String world, long x, long z, String r, String az, String s, int maxRadius) {
        int max = Math.max(1, Math.min(MAX_RADIUS, maxRadius));
        if (Math.abs(x) > COORD_LIMIT || Math.abs(z) > COORD_LIMIT) {
            return new Parsed(null, "That position lies outside the world.");
        }
        int radius = Math.min(DEFAULT_RADIUS, max);
        if (r != null && !r.isBlank()) {
            Long v = number(r);
            if (v == null || v < 1 || v > max) {
                return new Parsed(null, "The radius r must be a whole number of chunks from 1 to " + max + ".");
            }
            radius = v.intValue();
        }
        int azimuth = 0;
        if (az != null && !az.isBlank()) {
            Long v = number(az);
            if (v == null || (v != 0 && v != 90 && v != 180 && v != 270)) {
                return new Parsed(null, "The direction az must be 0, 90, 180 or 270.");
            }
            azimuth = v.intValue();
        }
        int scale = DEFAULT_SCALE;
        if (s != null && !s.isBlank()) {
            Long v = number(s);
            if (v == null || v < 1 || v > MAX_SCALE) {
                return new Parsed(null, "The scale s must be 1, 2 or 3.");
            }
            scale = v.intValue();
        }
        scale = Math.min(scale, maxScale(radius));
        return new Parsed(new IsoView(world, (int) Math.floorDiv(x, 32), (int) Math.floorDiv(z, 32), radius, azimuth, scale), null);
    }

    private static Long number(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
