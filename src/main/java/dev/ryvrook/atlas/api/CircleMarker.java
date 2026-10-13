package dev.ryvrook.atlas.api;

/** A circle around a block position with a radius in blocks. */
public final class CircleMarker extends ShapeMarker<CircleMarker> {
    /** Largest radius accepted, in blocks. */
    public static final double MAX_RADIUS = 100_000;
    private double x;
    private double z;
    private double radius;

    CircleMarker(MarkerLayer layer, String world, String id, double x, double z, double radius) {
        super(layer, world, id);
        this.x = Check.coord("x", x);
        this.z = Check.coord("z", z);
        this.radius = Check.positive("radius", radius, MAX_RADIUS);
    }

    @Override
    public String kind() {
        return "circle";
    }

    @Override
    CircleMarker self() {
        return this;
    }

    public double x() {
        synchronized (layer) {
            return x;
        }
    }

    public double z() {
        synchronized (layer) {
            return z;
        }
    }

    public double radius() {
        synchronized (layer) {
            return radius;
        }
    }

    public CircleMarker at(double x, double z) {
        double nx = Check.coord("x", x);
        double nz = Check.coord("z", z);
        return change(() -> {
            this.x = nx;
            this.z = nz;
        });
    }

    public CircleMarker radius(double blocks) {
        double r = Check.positive("radius", blocks, MAX_RADIUS);
        return change(() -> radius = r);
    }
}
