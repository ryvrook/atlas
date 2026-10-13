package dev.ryvrook.atlas.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A named group of markers that browsers can switch on and off. A layer holds markers in any number of
 * worlds and shows up in a world's layer list while it has markers there. Settings apply at once and
 * return the layer, so calls can be chained. Every method is thread safe and none of them touch a
 * world thread.
 */
public final class MarkerLayer {
    /** Most markers one layer may hold across all worlds. */
    public static final int MAX_MARKERS = 10_000;

    private final String pluginId;
    private final String id;
    private final AtlasApi.LayerListener listener;
    private final Map<String, LinkedHashMap<String, Marker<?>>> worlds = new LinkedHashMap<>();
    private int count;
    private String label;
    private Tier tier = Tier.PUBLIC;
    private boolean defaultHidden;
    private int priority;
    private Integer minZoom;
    private Integer maxZoom;
    private long updatedAt = System.currentTimeMillis();

    MarkerLayer(String pluginId, String id, AtlasApi.LayerListener listener) {
        this.pluginId = Check.id("pluginId", pluginId);
        this.id = Check.id("layerId", id);
        this.listener = listener;
        this.label = id;
    }

    public String pluginId() {
        return pluginId;
    }

    public String id() {
        return id;
    }

    /** pluginId:id, the layer id browsers see. */
    public String key() {
        return pluginId + ":" + id;
    }

    public synchronized String label() {
        return label;
    }

    public synchronized Tier tier() {
        return tier;
    }

    public synchronized boolean defaultHidden() {
        return defaultHidden;
    }

    public synchronized int priority() {
        return priority;
    }

    public synchronized Integer minZoom() {
        return minZoom;
    }

    public synchronized Integer maxZoom() {
        return maxZoom;
    }

    /** When the layer or one of its markers last changed, epoch ms. */
    public synchronized long updatedAt() {
        return updatedAt;
    }

    /** Name shown in the layer list, at most 200 characters. Null falls back to the layer id. */
    public MarkerLayer label(String text) {
        String l = Check.text(text, Check.MAX_TEXT);
        return meta(() -> label = l == null ? id : l);
    }

    /** Who may see the layer. Public by default. */
    public MarkerLayer tier(Tier t) {
        if (t == null) {
            throw new IllegalArgumentException("tier must not be null");
        }
        return meta(() -> tier = t);
    }

    /** True to list the layer switched off until the viewer turns it on. */
    public MarkerLayer defaultHidden(boolean hidden) {
        return meta(() -> defaultHidden = hidden);
    }

    /** Order in the layer list and drawing order, higher first and on top. */
    public MarkerLayer priority(int p) {
        return meta(() -> priority = p);
    }

    /** Lowest map zoom at which the layer draws, or null for every zoom. Uses the map's zoom levels. */
    public MarkerLayer minZoom(Integer zoom) {
        Integer z = Check.zoom("minZoom", zoom);
        return meta(() -> minZoom = z);
    }

    /** Highest map zoom at which the layer draws, or null for every zoom. */
    public MarkerLayer maxZoom(Integer zoom) {
        Integer z = Check.zoom("maxZoom", zoom);
        return meta(() -> maxZoom = z);
    }

    /** Adds a point marker, or replaces the marker with the same id in that world. */
    public PointMarker point(String world, String markerId, double x, double z) {
        return put(new PointMarker(this, Check.world(world), Check.id("marker id", markerId), x, z));
    }

    /** Adds a line through two or more x and z pairs, or replaces the marker with the same id. */
    public PolylineMarker polyline(String world, String markerId, List<double[]> points) {
        return put(new PolylineMarker(this, Check.world(world), Check.id("marker id", markerId), points));
    }

    /** Adds a closed area through three or more x and z pairs, or replaces the marker with the same id. */
    public PolygonMarker polygon(String world, String markerId, List<double[]> points) {
        return put(new PolygonMarker(this, Check.world(world), Check.id("marker id", markerId), points));
    }

    /** Adds a circle with a radius in blocks, or replaces the marker with the same id. */
    public CircleMarker circle(String world, String markerId, double x, double z, double radius) {
        return put(new CircleMarker(this, Check.world(world), Check.id("marker id", markerId), x, z, radius));
    }

    /** The marker with this id in a world, or null. */
    public synchronized Marker<?> get(String world, String markerId) {
        Map<String, Marker<?>> m = worlds.get(world);
        return m == null ? null : m.get(markerId);
    }

    /** Removes one marker. True when it existed. */
    public boolean remove(String world, String markerId) {
        return remove(world, markerId, null);
    }

    boolean remove(String world, String markerId, Marker<?> only) {
        synchronized (this) {
            Map<String, Marker<?>> m = worlds.get(world);
            if (m == null) {
                return false;
            }
            Marker<?> cur = m.get(markerId);
            if (cur == null || (only != null && cur != only)) {
                return false;
            }
            m.remove(markerId);
            count--;
            if (m.isEmpty()) {
                worlds.remove(world);
            }
            touch();
        }
        fire(world);
        return true;
    }

    /** Removes every marker in every world. The layer itself stays. */
    public void clear() {
        List<String> had;
        synchronized (this) {
            had = new ArrayList<>(worlds.keySet());
            worlds.clear();
            count = 0;
            touch();
        }
        for (String w : had) {
            fire(w);
        }
    }

    /** Removes every marker in one world. */
    public void clear(String world) {
        synchronized (this) {
            LinkedHashMap<String, Marker<?>> m = worlds.remove(world);
            if (m == null) {
                return;
            }
            count -= m.size();
            touch();
        }
        fire(world);
    }

    /** A copy of the markers in a world, in the order they were added. */
    public synchronized List<Marker<?>> markers(String world) {
        Map<String, Marker<?>> m = worlds.get(world);
        return m == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(m.values()));
    }

    /** The worlds the layer has markers in. */
    public synchronized Set<String> worlds() {
        return Collections.unmodifiableSet(new TreeSet<>(worlds.keySet()));
    }

    /** Markers across all worlds. */
    public synchronized int size() {
        return count;
    }

    private <M extends Marker<M>> M put(M marker) {
        synchronized (this) {
            LinkedHashMap<String, Marker<?>> m = worlds.computeIfAbsent(marker.world(), k -> new LinkedHashMap<>());
            Marker<?> prev = m.put(marker.id(), marker);
            if (prev == null) {
                if (count >= MAX_MARKERS) {
                    m.remove(marker.id());
                    if (m.isEmpty()) {
                        worlds.remove(marker.world());
                    }
                    throw new IllegalStateException("layer " + key() + " already holds " + MAX_MARKERS + " markers");
                }
                count++;
            }
            touch();
        }
        fire(marker.world());
        return marker;
    }

    private MarkerLayer meta(Runnable r) {
        synchronized (this) {
            r.run();
            touch();
        }
        fire(null);
        return this;
    }

    /** Called under the lock. */
    void touch() {
        updatedAt = System.currentTimeMillis();
    }

    /** Called outside the lock. A null world means every world the layer is in. */
    void fire(String world) {
        AtlasApi.LayerListener l = listener;
        if (l != null) {
            try {
                l.changed(this, world);
            } catch (RuntimeException ignored) {
                // A listener problem must never reach the plugin that changed the layer.
            }
        }
    }
}
