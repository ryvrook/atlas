package dev.ryvrook.atlas.config;

import java.util.Locale;

/** The values of the tracker key in atlas.json. */
public enum TrackerMode {
    /** TrackerAPI when it is installed and new enough, Atlas's own systems otherwise. */
    AUTO,
    /** TrackerAPI only. Without it Atlas records no block activity and no kills. */
    ALWAYS,
    /** Atlas's own systems only, TrackerAPI is ignored. */
    NEVER;

    /** The name as written in atlas.json. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The mode named by raw, ignoring case and spaces, AUTO for null or blank, null for anything else. */
    public static TrackerMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
