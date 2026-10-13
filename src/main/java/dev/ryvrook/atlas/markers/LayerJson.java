package dev.ryvrook.atlas.markers;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.ryvrook.atlas.api.CircleMarker;
import dev.ryvrook.atlas.api.Marker;
import dev.ryvrook.atlas.api.MarkerLayer;
import dev.ryvrook.atlas.api.PointMarker;
import dev.ryvrook.atlas.api.PolygonMarker;
import dev.ryvrook.atlas.api.PolylineMarker;
import dev.ryvrook.atlas.api.ShapeMarker;
import dev.ryvrook.atlas.api.Tier;
import dev.ryvrook.atlas.auth.Viewer;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.config.Visibility;

/** The browser form of marker layers, with the tier rules applied for one viewer. */
public final class LayerJson {
    /** The visibility key that gates every marker layer. */
    public static final String LAYER_KEY = "layers";

    private LayerJson() {
    }

    /** True when the viewer may see a layer of this tier. Admins see every tier. */
    public static boolean canSee(Viewer v, Tier t) {
        if (v.admin()) {
            return true;
        }
        return switch (t) {
            case PUBLIC -> true;
            case LINKED -> v.linked();
            case FACTION -> v.linked() && v.faction() != null;
            case ADMIN -> false;
        };
    }

    /** True when the viewer may see this layer at all, under both the layers setting and the layer's own tier. */
    public static boolean canSee(Viewer v, AtlasConfig cfg, Tier t) {
        return v.canSee(cfg, LAYER_KEY) && canSee(v, t);
    }

    /** True when markers are limited to the viewer's own faction, by the layer tier or the layers setting. */
    static boolean factionLimited(Viewer v, AtlasConfig cfg, Tier t) {
        return !v.admin() && (t == Tier.FACTION || cfg.visibilityOf(LAYER_KEY) == Visibility.FACTION);
    }

    /**
     * One layer with its markers in a world as this viewer may see it, or null when the viewer may not see
     * it or it holds nothing for them there.
     */
    public static JsonObject layer(Viewer v, AtlasConfig cfg, MarkerLayer layer, String world, String source) {
        JsonObject o = new JsonObject();
        JsonArray list = new JsonArray();
        synchronized (layer) {
            Tier tier = layer.tier();
            if (!canSee(v, cfg, tier)) {
                return null;
            }
            boolean limited = factionLimited(v, cfg, tier);
            for (Marker<?> m : layer.markers(world)) {
                if (limited && m.faction() != null && !v.sameFaction(m.faction())) {
                    continue;
                }
                list.add(marker(m));
            }
            if (list.isEmpty()) {
                return null;
            }
            o.addProperty("id", layer.key());
            o.addProperty("plugin", layer.pluginId());
            o.addProperty("label", layer.label());
            o.addProperty("source", source);
            o.addProperty("tier", tier.id());
            o.addProperty("defaultHidden", layer.defaultHidden());
            o.addProperty("priority", layer.priority());
            putInt(o, "minZoom", layer.minZoom());
            putInt(o, "maxZoom", layer.maxZoom());
            o.addProperty("updatedAt", layer.updatedAt());
        }
        o.add("markers", list);
        return o;
    }

    /** One marker with the fixed field list. Popups that are not trusted are escaped here. */
    static JsonObject marker(Marker<?> m) {
        JsonObject o = new JsonObject();
        o.addProperty("id", m.id());
        o.addProperty("kind", m.kind());
        putStr(o, "label", m.label());
        String popup = m.popup();
        putStr(o, "popup", popup == null ? null : m.popupTrusted() ? popup : Html.escape(popup));
        Double x = null;
        Double y = null;
        Double z = null;
        Double radius = null;
        JsonArray points = null;
        String icon = null;
        String iconData = null;
        if (m instanceof PointMarker p) {
            x = p.x();
            y = p.y();
            z = p.z();
            String i = p.icon();
            if (i != null && i.startsWith("data:")) {
                iconData = i;
            } else {
                icon = i;
            }
        } else if (m instanceof CircleMarker c) {
            x = c.x();
            z = c.z();
            radius = c.radius();
        } else if (m instanceof PolylineMarker l) {
            points = LayerFile.pairs(l.points());
        } else if (m instanceof PolygonMarker g) {
            points = LayerFile.pairs(g.points());
        }
        putNum(o, "x", x);
        putNum(o, "y", y);
        putNum(o, "z", z);
        putNum(o, "radius", radius);
        o.add("points", points == null ? JsonNull.INSTANCE : points);
        putStr(o, "icon", icon);
        putStr(o, "iconData", iconData);
        putStr(o, "tint", m.tint());
        if (m instanceof ShapeMarker<?> s) {
            putStr(o, "fill", s.fill());
            putNum(o, "weight", s.weight());
        } else {
            o.add("fill", JsonNull.INSTANCE);
            o.add("weight", JsonNull.INSTANCE);
        }
        putStr(o, "faction", m.faction());
        return o;
    }

    private static void putStr(JsonObject o, String k, String v) {
        if (v == null) {
            o.add(k, JsonNull.INSTANCE);
        } else {
            o.addProperty(k, v);
        }
    }

    private static void putNum(JsonObject o, String k, Double v) {
        if (v == null) {
            o.add(k, JsonNull.INSTANCE);
        } else {
            o.addProperty(k, v);
        }
    }

    private static void putInt(JsonObject o, String k, Integer v) {
        if (v == null) {
            o.add(k, JsonNull.INSTANCE);
        } else {
            o.addProperty(k, v);
        }
    }
}
