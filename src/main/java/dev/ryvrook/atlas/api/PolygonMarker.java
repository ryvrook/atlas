package dev.ryvrook.atlas.api;

import java.util.List;

/** A closed area through three or more block positions, each an x and z pair. */
public final class PolygonMarker extends ShapeMarker<PolygonMarker> {
    private List<double[]> points;

    PolygonMarker(MarkerLayer layer, String world, String id, List<double[]> points) {
        super(layer, world, id);
        this.points = Check.points(points, 3);
    }

    @Override
    public String kind() {
        return "polygon";
    }

    @Override
    PolygonMarker self() {
        return this;
    }

    /** A copy of the points. */
    public List<double[]> points() {
        synchronized (layer) {
            return PolylineMarker.copy(points);
        }
    }

    public PolygonMarker points(List<double[]> next) {
        List<double[]> p = Check.points(next, 3);
        return change(() -> points = p);
    }
}
