package dev.ryvrook.atlas.auth;

import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.config.Visibility;

import java.util.Locale;
import java.util.UUID;

/**
 * Who is looking at the map. Public viewers have no uuid. Linked viewers carry the player they
 * linked as and that player's faction tag. Admins see everything unless they asked to view
 * as a faction, in which case they get exactly what a linked member of that faction would see.
 */
public record Viewer(UUID uuid, String name, String faction, boolean admin, boolean viewingAs) {
    public static final Viewer PUBLIC = new Viewer(null, null, null, false, false);

    public boolean linked() {
        return uuid != null || viewingAs;
    }

    /** True when the viewer is allowed to see the layer at all. */
    public boolean canSee(AtlasConfig cfg, String layer) {
        if (admin) {
            return true;
        }
        Visibility v = cfg.visibilityOf(layer);
        return switch (v) {
            case PUBLIC -> true;
            case LINKED -> linked();
            case FACTION -> linked() && faction != null;
            case ADMIN -> false;
        };
    }

    /** True when the layer is limited to the viewer's own faction for this viewer. */
    public boolean factionLimited(AtlasConfig cfg, String layer) {
        return !admin && cfg.visibilityOf(layer) == Visibility.FACTION;
    }

    public boolean sameFaction(String tag) {
        return faction != null && tag != null && faction.equalsIgnoreCase(tag);
    }

    /** An admin viewing the map as a member of the given faction. */
    public Viewer as(String tag) {
        return new Viewer(null, "as " + tag, tag.toUpperCase(Locale.ROOT), false, true);
    }

    /**
     * Key under which filtered broadcast payloads can be shared between viewers. Payloads mark the
     * viewer's own player with a self flag and keep a hidden viewer visible to themselves, so a
     * viewer with a player always gets a key of their own.
     */
    public String cacheKey(boolean selfHidden) {
        String own = uuid == null ? "" : ":" + uuid;
        if (admin) {
            return "A" + own;
        }
        if (!linked()) {
            return "P";
        }
        return "L:" + (faction == null ? "" : faction) + own;
    }
}
