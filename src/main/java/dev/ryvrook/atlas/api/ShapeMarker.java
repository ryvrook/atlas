package dev.ryvrook.atlas.api;

/** A line, area or circle in world blocks. The tint colours the outline and fill colours the inside. */
public abstract class ShapeMarker<T extends ShapeMarker<T>> extends Marker<T> {
    private String fill;
    private Double weight;

    ShapeMarker(MarkerLayer layer, String world, String id) {
        super(layer, world, id);
    }

    public final String fill() {
        synchronized (layer) {
            return fill;
        }
    }

    public final Double weight() {
        synchronized (layer) {
            return weight;
        }
    }

    /** Fill colour such as #ff880055, or null for the default. Lines ignore it. */
    public final T fill(String color) {
        String c = Check.color("fill", color);
        return change(() -> fill = c);
    }

    /** Outline width in screen pixels, above 0 and at most 20, or null for the default. */
    public final T weight(Double pixels) {
        Double w = pixels == null ? null : Check.positive("weight", pixels, 20);
        return change(() -> weight = w);
    }
}
