package dev.ryvrook.atlas.api;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Input checks shared by the marker API and the drop folder. Each failure throws IllegalArgumentException. */
final class Check {
    /** Ids of plugins, layers and markers. */
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    /** Engine marker icon names, served by api/marker-icons. */
    static final Pattern ICON_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    static final Pattern COLOR = Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");
    static final Pattern DATA_URL = Pattern.compile("data:image/(png|jpeg|gif|webp|svg\\+xml);base64,[A-Za-z0-9+/=]+");
    /** Largest data URL accepted as an icon, in characters. */
    static final int MAX_ICON_DATA = 8 * 1024;
    static final double MAX_COORD = 30_000_000;
    static final int MAX_POINTS = 2_000;
    static final int MAX_TEXT = 200;
    static final int MAX_POPUP = 4_000;

    private Check() {
    }

    static String id(String what, String raw) {
        if (raw == null || !ID.matcher(raw).matches()) {
            throw new IllegalArgumentException(what + " must be 1 to 64 letters, digits or _ . : -");
        }
        return raw;
    }

    static String world(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 128 || raw.contains("/")) {
            throw new IllegalArgumentException("world must name a world");
        }
        return raw;
    }

    static double coord(String what, double v) {
        if (!Double.isFinite(v) || Math.abs(v) > MAX_COORD) {
            throw new IllegalArgumentException(what + " must be a finite block coordinate");
        }
        return v;
    }

    static String text(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String color(String what, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!COLOR.matcher(raw.trim()).matches()) {
            throw new IllegalArgumentException(what + " must be a colour such as #ff8800");
        }
        return raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** An engine icon name or a data URL of at most 8 KB, or null. */
    static String icon(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("data:")) {
            if (s.length() > MAX_ICON_DATA) {
                throw new IllegalArgumentException("icon data URLs may hold at most 8 KB");
            }
            if (!DATA_URL.matcher(s).matches()) {
                throw new IllegalArgumentException("icon data URLs must be base64 png, jpeg, gif, webp or svg images");
            }
            return s;
        }
        if (!ICON_NAME.matcher(s).matches()) {
            throw new IllegalArgumentException("icon must be an engine icon name such as Home or a data URL");
        }
        return s;
    }

    static List<double[]> points(List<double[]> in, int min) {
        if (in == null || in.size() < min || in.size() > MAX_POINTS) {
            throw new IllegalArgumentException("a shape needs " + min + " to " + MAX_POINTS + " points");
        }
        List<double[]> out = new ArrayList<>(in.size());
        for (double[] p : in) {
            if (p == null || p.length != 2) {
                throw new IllegalArgumentException("each point must be an x and z pair");
            }
            out.add(new double[]{coord("x", p[0]), coord("z", p[1])});
        }
        return out;
    }

    static double positive(String what, double v, double max) {
        if (!Double.isFinite(v) || v <= 0 || v > max) {
            throw new IllegalArgumentException(what + " must be above 0 and at most " + (long) max);
        }
        return v;
    }

    static Integer zoom(String what, Integer z) {
        if (z != null && (z < -10 || z > 10)) {
            throw new IllegalArgumentException(what + " must be between -10 and 10");
        }
        return z;
    }
}
