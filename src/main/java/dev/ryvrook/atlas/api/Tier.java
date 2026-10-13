package dev.ryvrook.atlas.api;

/** Who may see a marker layer. Each level includes everyone above it in this list. */
public enum Tier {
    /** Anyone who can open the map. */
    PUBLIC,
    /** Browsers linked to a player. */
    LINKED,
    /** Linked viewers in a faction. A marker with a faction tag shows only to members of that faction. */
    FACTION,
    /** Admin viewers only. */
    ADMIN;

    /** The tier named by raw, ignoring case, or null when raw names no tier. */
    public static Tier parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The lower case name used in JSON. */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
