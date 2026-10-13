package dev.ryvrook.atlas.config;

import java.util.Locale;

/** Who may see a layer. Each level includes everyone above it in this list. */
public enum Visibility {
    /** Anyone who can reach the web server. */
    PUBLIC,
    /** Browsers linked to a player with /atlas link. */
    LINKED,
    /** Linked viewers, limited to data that belongs to their own faction. */
    FACTION,
    /** Admin viewers only. */
    ADMIN;

    public static Visibility parse(String raw) {
        if (raw == null) {
            return ADMIN;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ADMIN;
        }
    }
}
