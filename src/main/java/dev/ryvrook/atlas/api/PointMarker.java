package dev.ryvrook.atlas.api;

/** A marker at one block position, drawn with an icon. */
public final class PointMarker extends Marker<PointMarker> {
    private double x;
    private double z;
    private Double y;
    private String icon;

    PointMarker(MarkerLayer layer, String world, String id, double x, double z) {
        super(layer, world, id);
        this.x = Check.coord("x", x);
        this.z = Check.coord("z", z);
    }

    @Override
    public String kind() {
        return "point";
    }

    @Override
    PointMarker self() {
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

    public Double y() {
        synchronized (layer) {
            return y;
        }
    }

    public String icon() {
        synchronized (layer) {
            return icon;
        }
    }

    /** Moves the marker. */
    public PointMarker at(double x, double z) {
        double nx = Check.coord("x", x);
        double nz = Check.coord("z", z);
        return change(() -> {
            this.x = nx;
            this.z = nz;
        });
    }

    /** Height shown in the popup, or null. */
    public PointMarker y(Double y) {
        Double ny = y == null ? null : Check.coord("y", y);
        return change(() -> this.y = ny);
    }

    /**
     * An engine marker icon name such as Home, Death or UserB (served from the server's assets), or a
     * base64 data URL of a png, jpeg, gif, webp or svg image of at most 8 KB. Null for the default pin.
     */
    public PointMarker icon(String nameOrDataUrl) {
        String i = Check.icon(nameOrDataUrl);
        return change(() -> icon = i);
    }
}
