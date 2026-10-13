package dev.ryvrook.atlas.api;

/**
 * One marker on a layer, in one world. Setters apply at once and return the marker, so calls can be
 * chained. Every method is thread safe and none of them touch a world thread.
 *
 * @param <T> the concrete marker type, returned by the setters
 */
public abstract class Marker<T extends Marker<T>> {
    final MarkerLayer layer;
    private final String world;
    private final String id;
    private String label;
    private String popup;
    private boolean popupTrusted;
    private String tint;
    private String faction;

    Marker(MarkerLayer layer, String world, String id) {
        this.layer = layer;
        this.world = world;
        this.id = id;
    }

    /** point, polyline, polygon or circle. */
    public abstract String kind();

    abstract T self();

    public final String id() {
        return id;
    }

    public final String world() {
        return world;
    }

    public final MarkerLayer layer() {
        return layer;
    }

    public final String label() {
        synchronized (layer) {
            return label;
        }
    }

    /** The popup as written, before escaping. */
    public final String popup() {
        synchronized (layer) {
            return popup;
        }
    }

    /** True when the popup is sent as HTML without escaping. */
    public final boolean popupTrusted() {
        synchronized (layer) {
            return popupTrusted;
        }
    }

    public final String tint() {
        synchronized (layer) {
            return tint;
        }
    }

    public final String faction() {
        synchronized (layer) {
            return faction;
        }
    }

    /** Plain text shown next to the marker and in lists, at most 200 characters. Null for none. */
    public final T label(String text) {
        return change(() -> label = Check.text(text, Check.MAX_TEXT));
    }

    /** Popup text. It is escaped, so any HTML in it shows as text. At most 4,000 characters. */
    public final T popup(String text) {
        return change(() -> {
            popup = Check.text(text, Check.MAX_POPUP);
            popupTrusted = false;
        });
    }

    /**
     * Popup HTML sent to browsers as it is. Use only for HTML your plugin builds itself, never for text
     * players typed. The map's content security policy still blocks scripts.
     */
    public final T popupHtml(String html) {
        return change(() -> {
            popup = Check.text(html, Check.MAX_POPUP);
            popupTrusted = true;
        });
    }

    /** Colour such as #ff8800, or null for the default. */
    public final T tint(String color) {
        String c = Check.color("tint", color);
        return change(() -> tint = c);
    }

    /**
     * Faction tag the marker belongs to, or null. On a layer with the faction tier, a marker with a tag
     * shows only to members of that faction and a marker without one shows to every faction member.
     */
    public final T faction(String tag) {
        String t = tag == null || tag.isBlank() ? null : tag.trim();
        return change(() -> faction = t);
    }

    /** Removes this marker from its layer. True when it was still there. */
    public final boolean remove() {
        return layer.remove(world, id, this);
    }

    /** Runs a change under the layer lock and announces it. */
    final T change(Runnable r) {
        synchronized (layer) {
            r.run();
            layer.touch();
        }
        layer.fire(world);
        return self();
    }
}
