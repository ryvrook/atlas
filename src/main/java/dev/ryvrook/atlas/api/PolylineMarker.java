package dev.ryvrook.atlas.api;

import java.util.ArrayList;
import java.util.List;

/** A line through two or more block positions, each an x and z pair. */
public final class PolylineMarker extends ShapeMarker<PolylineMarker> {
    private List<double[]> points;

    PolylineMarker(MarkerLayer layer, String world, String id, List<double[]> points) {
        super(layer, world, id);
        this.points = Check.points(points, 2);
    }

    @Override
    public String kind() {
        return "polyline";
    }

    @Override
    PolylineMarker self() {
        return this;
    }

    /** A copy of the points. */
    public List<double[]> points() {
        synchronized (layer) {
            return copy(points);
        }
    }

    public PolylineMarker points(List<double[]> next) {
        List<double[]> p = Check.points(next, 2);
        return change(() -> points = p);
    }

    static List<double[]> copy(List<double[]> in) {
        List<double[]> out = new ArrayList<>(in.size());
        for (double[] p : in) {
            out.add(p.clone());
        }
        return out;
    }
}
