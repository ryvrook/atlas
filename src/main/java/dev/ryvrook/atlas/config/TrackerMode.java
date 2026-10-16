package dev.ryvrook.atlas.config;

import java.util.Locale;

/** The values of the tracker, coinage, dominion, feats and chronicle keys in atlas.json. */
public enum TrackerMode {
    /** The sibling when it is installed and new enough, Atlas on its own otherwise. */
    AUTO,
    /** The sibling only. For tracker, without TrackerAPI Atlas records no block activity and no kills. */
    ALWAYS,
    /** Atlas on its own, the sibling is ignored. */
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
