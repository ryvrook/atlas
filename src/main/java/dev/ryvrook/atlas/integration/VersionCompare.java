package dev.ryvrook.atlas.integration;

/**
 * Compares plugin version strings as major, minor and patch. Named so it never clashes
 * with the engine's {@code common.semver.Semver}. Anything after the patch number, such as a pre release
 * tag, is ignored. A string that does not start with a number counts as 0.0.0. Pure.
 */
public final class VersionCompare {
    private VersionCompare() {
    }

    /** True when {@code version} is below {@code minimum}. */
    public static boolean below(String version, String minimum) {
        return compare(version, minimum) < 0;
    }

    public static int compare(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) {
                return Integer.compare(x[i], y[i]);
            }
        }
        return 0;
    }

    static int[] parse(String v) {
        int[] out = new int[3];
        if (v == null) {
            return out;
        }
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int part = 0;
        int i = 0;
        while (part < 3 && i < s.length()) {
            int start = i;
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                i++;
            }
            if (i == start) {
                break;
            }
            try {
                out[part] = Integer.parseInt(s.substring(start, i));
            } catch (NumberFormatException e) {
                out[part] = Integer.MAX_VALUE;
            }
            part++;
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
            } else {
                break;
            }
        }
        return out;
    }
}
