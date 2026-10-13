package dev.ryvrook.atlas.iso;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.auth.Viewer;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.markers.MarkerService;
import dev.ryvrook.atlas.util.Json;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Daily isometric views. When the timeline takes the snapshot of a world, every area of
 * iso.snapshots in that world, and every admin marker when iso.snapshotAdminMarkers is on and the
 * admin marker layer is public, is drawn into the day's folder as iso/{label}.png. The list
 * goes to iso/index.json next to them and reaches the day list of the timeline as isoViews.
 */
public final class IsoSnapshots {
    static final String DIR = "iso";
    static final String INDEX = "index.json";
    /** Areas drawn per world and day at most. */
    static final int MAX_AREAS = 64;
    /** Radius of the view around an admin point marker. */
    static final int MARKER_RADIUS = 4;
    private static volatile IsoSnapshots current;

    /** One area to draw. */
    record Area(String label, int x, int z, int radius) {
    }

    private final Atlas atlas;
    private final IsoService service;
    private final HytaleLogger logger;

    public IsoSnapshots(Atlas atlas, IsoService service, HytaleLogger logger) {
        this.atlas = atlas;
        this.service = service;
        this.logger = logger;
    }

    public static void install(IsoSnapshots s) {
        current = s;
    }

    /** The hook in the timeline job. Queues the day's views of the world and returns at once. */
    public static void dayTaken(String world, String date, Path dayDir) {
        IsoSnapshots s = current;
        if (s != null) {
            try {
                s.take(world, date, dayDir);
            } catch (RuntimeException e) {
                s.logger.at(Level.WARNING).withCause(e).log("Atlas could not queue the isometric views of %s", world);
            }
        }
    }

    /** The isoViews of a day for the timeline day list, oldest label first, empty when there are none. */
    public static JsonArray views(String world, String date, Path dayDir) {
        JsonArray out = new JsonArray();
        if (!dayHeld(dayDir)) {
            return out;
        }
        Path index = dayDir.resolve(DIR).resolve(INDEX);
        if (!Files.isRegularFile(index)) {
            return out;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(index, StandardCharsets.UTF_8));
            for (JsonElement e : Json.arr(root.getAsJsonObject(), "views")) {
                JsonObject v = e.getAsJsonObject();
                String label = Json.str(v, "label");
                if (!AtlasConfig.validIsoLabel(label) || !Files.isRegularFile(file(dayDir, label))) {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("label", label);
                o.addProperty("x", (int) Json.num(v, "x", 0));
                o.addProperty("z", (int) Json.num(v, "z", 0));
                o.addProperty("radius", (int) Json.num(v, "radius", 0));
                o.addProperty("file", "api/timeline/" + URLEncoder.encode(world, StandardCharsets.UTF_8).replace("+", "%20")
                        + "/" + date + "/iso/" + label + ".png");
                out.add(o);
            }
        } catch (IOException | RuntimeException e) {
            // A torn or hand edited index lists nothing.
        }
        return out;
    }

    /** The image of a label in a day folder. */
    static Path file(Path dayDir, String label) {
        return dayDir.resolve(DIR).resolve(label + ".png");
    }

    /** True while the day still has its snapshot. A pruned day keeps only a tombstone. */
    static boolean dayHeld(Path dayDir) {
        return Files.isRegularFile(dayDir.resolve("tiles.json"));
    }

    /** The areas of a world for today, configured ones first. */
    List<Area> areas(String world) {
        AtlasConfig c = atlas.config();
        Map<String, Area> out = new LinkedHashMap<>();
        for (AtlasConfig.IsoArea a : c.isoSnapshots()) {
            if (world.equals(a.world) && out.size() < MAX_AREAS) {
                out.putIfAbsent(a.label, new Area(a.label, a.x, a.z, Math.min(a.radius, c.isoMaxRadius())));
            }
        }
        if (c.isoSnapshotAdminMarkers() && atlas.markers().adminTimelineVisible()) {
            Viewer admin = new Viewer(null, "Atlas", null, true, false);
            JsonObject p = atlas.markers().layersPayload(admin, world);
            String adminLayer = MarkerService.ADMIN_PLUGIN + ":" + MarkerService.ADMIN_LAYER;
            for (JsonElement le : Json.arr(p, "layers")) {
                JsonObject l = le.getAsJsonObject();
                if (!adminLayer.equals(Json.str(l, "id"))) {
                    continue;
                }
                for (JsonElement me : Json.arr(l, "markers")) {
                    if (out.size() >= MAX_AREAS) {
                        break;
                    }
                    JsonObject m = me.getAsJsonObject();
                    double[] at = IsoRoutes.centre(m);
                    int radius = at[2] > 0 ? (int) Math.ceil(at[2] / 32.0) : MARKER_RADIUS;
                    String label = markerLabel(Json.str(m, "id"));
                    out.putIfAbsent(label, new Area(label, (int) Math.round(at[0]), (int) Math.round(at[1]),
                            Math.max(1, Math.min(c.isoMaxRadius(), radius))));
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /** marker- and the id in lower case with other characters as dashes, at most 40 long. */
    static String markerLabel(String id) {
        String s = "marker-" + (id == null ? "" : id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-"));
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private void take(String world, String date, Path dayDir) {
        AtlasConfig c = atlas.config();
        if (!c.isoEnabled()) {
            return;
        }
        List<Area> areas = areas(world);
        if (areas.isEmpty()) {
            return;
        }
        List<CompletableFuture<JsonObject>> parts = new ArrayList<>();
        for (Area a : areas) {
            IsoView.Parsed p = IsoView.of(world, a.x(), a.z(), Integer.toString(a.radius()), "0", "2", c.isoMaxRadius());
            if (p.view() == null || !service.anyAllowed(p.view())) {
                continue;
            }
            parts.add(service.render(p.view()).handle((d, err) -> {
                if (err != null || d == null) {
                    logger.at(Level.WARNING).log("Atlas could not draw the daily isometric view %s of %s", a.label(), world);
                    return null;
                }
                try {
                    Json.writeAtomic(file(dayDir, a.label()), d.png(), -1);
                } catch (IOException e) {
                    logger.at(Level.WARNING).log("Atlas could not save the daily isometric view %s of %s, %s", a.label(), world,
                            e.getMessage());
                    return null;
                }
                JsonObject o = new JsonObject();
                o.addProperty("label", a.label());
                o.addProperty("x", a.x());
                o.addProperty("z", a.z());
                o.addProperty("radius", d.view().radius());
                o.addProperty("scale", d.view().scale());
                o.addProperty("renderedAt", d.renderedAt());
                o.addProperty("partial", d.partial());
                return o;
            }));
        }
        if (parts.isEmpty()) {
            return;
        }
        CompletableFuture.allOf(parts.toArray(new CompletableFuture[0])).whenComplete((x, err) -> {
            JsonArray list = new JsonArray();
            for (CompletableFuture<JsonObject> f : parts) {
                JsonObject o = f.getNow(null);
                if (o != null) {
                    list.add(o);
                }
            }
            JsonObject root = new JsonObject();
            root.addProperty("world", world);
            root.addProperty("date", date);
            root.add("views", list);
            try {
                Json.writeAtomic(dayDir.resolve(DIR).resolve(INDEX), Json.PRETTY.toJson(root));
                logger.at(Level.INFO).log("Atlas saved %d daily isometric views of %s for %s", list.size(), world, date);
            } catch (IOException e) {
                logger.at(Level.WARNING).log("Atlas could not save the isometric index of %s for %s, %s", world, date, e.getMessage());
            }
        });
    }

    /**
     * Removes the iso folders of days the timeline pruned. The prune keeps a tombstone in the day
     * folder and removes the snapshot, so a folder whose day has no snapshot any more goes.
     */
    public void sweep(Path timelineRoot) {
        Path worlds = timelineRoot.resolve("worlds");
        if (!Files.isDirectory(worlds)) {
            return;
        }
        int removed = 0;
        try (DirectoryStream<Path> ws = Files.newDirectoryStream(worlds, Files::isDirectory)) {
            for (Path w : ws) {
                try (DirectoryStream<Path> days = Files.newDirectoryStream(w, Files::isDirectory)) {
                    for (Path d : days) {
                        Path iso = d.resolve(DIR);
                        if (Files.isDirectory(iso) && !dayHeld(d)) {
                            deleteTree(iso);
                            removed++;
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // Tried again on the next sweep.
        }
        if (removed > 0) {
            logger.at(Level.INFO).log("Atlas removed the isometric views of %d pruned timeline days", removed);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var s = Files.walk(dir)) {
            List<Path> all = new ArrayList<>(s.toList());
            java.util.Collections.reverse(all);
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        }
    }
}
