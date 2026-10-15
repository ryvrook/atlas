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
import dev.ryvrook.atlas.timeline.TimelineStore;
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
 *
 * <p>Every key place of iso.keyPlaces in the world is drawn from all four directions as
 * iso/{label}-{azimuth}.png. When the place was drawn before, its chunks are first read without
 * drawing them and their fingerprint compared with the one kept in the index of that render. When
 * nothing changed, the pictures are not drawn again. The day folder gets a hard link to the earlier
 * file (a copy where the file system has no links), so the disk holds one picture, and the entry is
 * marked unchanged with the slot it was drawn in as sameAs. A link also survives the prune of the
 * day it points at.
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

    /** One earlier render of a key place, its date and folder and its entries, one per direction. */
    record Previous(String date, Path dayDir, List<JsonObject> entries) {
    }

    private final Atlas atlas;
    private final IsoService service;
    private final HytaleLogger logger;
    private final java.util.function.Supplier<dev.ryvrook.atlas.timeline.Timeline> timeline;

    public IsoSnapshots(Atlas atlas, IsoService service, HytaleLogger logger,
                        java.util.function.Supplier<dev.ryvrook.atlas.timeline.Timeline> timeline) {
        this.atlas = atlas;
        this.service = service;
        this.logger = logger;
        this.timeline = timeline;
    }

    public static void install(IsoSnapshots s) {
        current = s;
    }

    /**
     * The hook in the timeline job, with the slot just taken and its folder. Queues the day's views of
     * the world and returns at once. The views are drawn once a day, into the first slot of the day.
     * A later slot of a day whose views are already drawn draws nothing and shows those views.
     */
    public static void dayTaken(String world, String slot, Path dayDir) {
        IsoSnapshots s = current;
        if (s != null) {
            try {
                Path first = dayViewsDir(dayDir.getParent(), dev.ryvrook.atlas.timeline.Slots.date(slot));
                if (first != null && first.getFileName().toString().compareTo(slot) < 0) {
                    return;
                }
                s.take(world, slot, dayDir);
            } catch (RuntimeException e) {
                s.logger.at(Level.WARNING).withCause(e).log("Atlas could not queue the isometric views of %s", world);
            }
        }
    }

    /**
     * The earliest slot folder of a day that holds drawn views, or null. The other slots of the day
     * show its views.
     */
    static Path dayViewsDir(Path worldDir, String date) {
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return null;
        }
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(worldDir, date + "*")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (dev.ryvrook.atlas.timeline.Slots.valid(n) && dev.ryvrook.atlas.timeline.Slots.date(n).equals(date)
                        && dayHeld(p) && Files.isRegularFile(p.resolve(DIR).resolve(INDEX))) {
                    names.add(n);
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (names.isEmpty()) {
            return null;
        }
        java.util.Collections.sort(names);
        return worldDir.resolve(names.get(0));
    }

    /**
     * The isoViews of a slot for the timeline day list, oldest label first, empty when there are none.
     * A slot without views of its own shows the views of its day, with the files of the slot that holds them.
     */
    public static JsonArray views(String world, String date, Path dayDir) {
        JsonArray out = new JsonArray();
        if (!dayHeld(dayDir)) {
            return out;
        }
        Path index = dayDir.resolve(DIR).resolve(INDEX);
        if (!Files.isRegularFile(index)) {
            String slot = dayDir.getFileName().toString();
            Path first = dev.ryvrook.atlas.timeline.Slots.valid(slot)
                    ? dayViewsDir(dayDir.getParent(), dev.ryvrook.atlas.timeline.Slots.date(slot)) : null;
            if (first == null) {
                return out;
            }
            dayDir = first;
            date = first.getFileName().toString();
            index = dayDir.resolve(DIR).resolve(INDEX);
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(index, StandardCharsets.UTF_8));
            String w = URLEncoder.encode(world, StandardCharsets.UTF_8).replace("+", "%20");
            for (JsonElement e : Json.arr(root.getAsJsonObject(), "views")) {
                JsonObject v = e.getAsJsonObject();
                String label = Json.str(v, "label");
                boolean key = Json.bool(v, "keyPlace");
                int az = (int) Json.num(v, "azimuth", 0);
                if (!AtlasConfig.validIsoLabel(label) || (key && !KeyPlaces.validAzimuth(az))) {
                    continue;
                }
                String name = key ? KeyPlaces.fileLabel(label, az) : label;
                if (!Files.isRegularFile(file(dayDir, name))) {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("label", label);
                o.addProperty("x", (int) Json.num(v, "x", 0));
                o.addProperty("z", (int) Json.num(v, "z", 0));
                o.addProperty("radius", (int) Json.num(v, "radius", 0));
                String fileDate = date;
                if (key) {
                    String display = Json.str(v, "name");
                    o.addProperty("name", display == null || display.isBlank() ? label : display);
                    o.addProperty("azimuth", az);
                    o.addProperty("scale", (int) Json.num(v, "scale", KeyPlaces.SCALE));
                    o.addProperty("keyPlace", true);
                    boolean unchanged = Json.bool(v, "unchanged");
                    o.addProperty("unchanged", unchanged);
                    String sameAs = Json.str(v, "sameAs");
                    o.add("sameAs", com.google.gson.JsonNull.INSTANCE);
                    if (unchanged && TimelineStore.validSlot(sameAs)) {
                        o.addProperty("sameAs", sameAs);
                        // The same picture under the address of the day it was drawn on, so a browser loads it once.
                        Path other = dayDir.resolveSibling(sameAs);
                        if (!sameAs.equals(date) && dayHeld(other) && Files.isRegularFile(file(other, name))) {
                            fileDate = sameAs;
                        }
                    }
                }
                o.addProperty("file", "api/timeline/" + w + "/" + fileDate + "/iso/" + name + ".png");
                out.add(o);
            }
        } catch (IOException | RuntimeException e) {
            // A torn or hand edited index lists nothing.
        }
        return out;
    }

    /** True for the name of an image in a day folder, a label or a key place label with its direction. */
    static boolean validFileLabel(String s) {
        return AtlasConfig.validIsoLabel(s) || KeyPlaces.validFileLabel(s);
    }

    /** The image of a label in a day folder. */
    static Path file(Path dayDir, String label) {
        return dayDir.resolve(DIR).resolve(label + ".png");
    }

    /** True while the day still has its snapshot. A pruned day keeps only a tombstone. */
    static boolean dayHeld(Path dayDir) {
        return Files.isRegularFile(dayDir.resolve("tiles.json"));
    }

    /**
     * The areas of a world for today, configured ones first. An area whose label is also the file
     * name of a key place direction, such as spawn-90 next to a key place spawn, is left out.
     */
    List<Area> areas(String world) {
        AtlasConfig c = atlas.config();
        Map<String, Area> out = new LinkedHashMap<>();
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (AtlasConfig.KeyPlace p : c.isoKeyPlaces()) {
            if (world.equals(p.world)) {
                for (int az : KeyPlaces.AZIMUTHS) {
                    taken.add(KeyPlaces.fileLabel(p.label, az));
                }
            }
        }
        for (AtlasConfig.IsoArea a : c.isoSnapshots()) {
            if (world.equals(a.world) && out.size() < MAX_AREAS && !taken.contains(a.label)) {
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
                    if (taken.contains(label)) {
                        continue;
                    }
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
        List<AtlasConfig.KeyPlace> places = new ArrayList<>();
        for (AtlasConfig.KeyPlace p : c.isoKeyPlaces()) {
            if (world.equals(p.world)) {
                places.add(p);
            }
        }
        if (areas.isEmpty() && places.isEmpty()) {
            return;
        }
        List<CompletableFuture<List<JsonObject>>> parts = new ArrayList<>();
        for (Area a : areas) {
            IsoView.Parsed p = IsoView.of(world, a.x(), a.z(), Integer.toString(a.radius()), "0", "2", c.isoMaxRadius());
            if (p.view() == null || !service.anyAllowed(p.view())) {
                continue;
            }
            parts.add(service.render(p.view()).handle((d, err) -> {
                if (err != null || d == null) {
                    logger.at(Level.WARNING).log("Atlas could not draw the daily isometric view %s of %s", a.label(), world);
                    return List.of();
                }
                try {
                    Json.writeAtomic(file(dayDir, a.label()), d.png(), -1);
                } catch (IOException e) {
                    logger.at(Level.WARNING).log("Atlas could not save the daily isometric view %s of %s, %s", a.label(), world,
                            e.getMessage());
                    return List.of();
                }
                JsonObject o = new JsonObject();
                o.addProperty("label", a.label());
                o.addProperty("x", a.x());
                o.addProperty("z", a.z());
                o.addProperty("radius", d.view().radius());
                o.addProperty("scale", d.view().scale());
                o.addProperty("renderedAt", d.renderedAt());
                o.addProperty("partial", d.partial());
                return List.of(o);
            }));
        }
        for (AtlasConfig.KeyPlace p : places) {
            parts.add(keyPlace(p, date, dayDir, false));
        }
        if (parts.isEmpty()) {
            return;
        }
        CompletableFuture.allOf(parts.toArray(new CompletableFuture[0])).whenComplete((x, err) -> {
            List<JsonObject> list = new ArrayList<>();
            for (CompletableFuture<List<JsonObject>> f : parts) {
                List<JsonObject> l = f.getNow(null);
                if (l != null) {
                    list.addAll(l);
                }
            }
            try {
                writeIndex(world, date, dayDir, v -> true, list);
                long unchanged = list.stream().filter(o -> Json.bool(o, "unchanged")).count();
                logger.at(Level.INFO).log("Atlas saved %d daily isometric views of %s for %s, %d of them unchanged key place views",
                        list.size(), world, date, unchanged);
            } catch (IOException e) {
                logger.at(Level.WARNING).log("Atlas could not save the isometric index of %s for %s, %s", world, date, e.getMessage());
            }
        });
    }

    /**
     * The four directions of a key place for a day folder, as index entries. Drawn again when force
     * is set, when the place has no earlier render, or when its fingerprint changed since that
     * render, otherwise the earlier pictures are reused. Never fails, a direction that could not be
     * drawn or saved is left out.
     */
    CompletableFuture<List<JsonObject>> keyPlace(AtlasConfig.KeyPlace p, String date, Path dayDir, boolean force) {
        AtlasConfig c = atlas.config();
        IsoView[] views = new IsoView[KeyPlaces.AZIMUTHS.length];
        for (int i = 0; i < views.length; i++) {
            views[i] = KeyPlaces.view(p, KeyPlaces.AZIMUTHS[i], c.isoMaxRadius());
            if (views[i] == null) {
                return CompletableFuture.completedFuture(List.of());
            }
        }
        if (!service.anyAllowed(views[0])) {
            logger.at(Level.FINE).log("Atlas skipped the key place %s, nothing around it is explored", p.label);
            return CompletableFuture.completedFuture(List.of());
        }
        Previous prev = force ? null : previous(dayDir, date, p.label);
        if (prev == null) {
            return drawAll(p, views, dayDir);
        }
        return service.fingerprint(views[0]).handle((fp, err) -> fp).thenCompose(fp -> {
            if (fp != null && KeyPlaces.unchanged(prev.entries(), e -> Files.isRegularFile(file(prev.dayDir(),
                    KeyPlaces.fileLabel(p.label, (int) Json.num(e, "azimuth", 0)))), views[0], fp.digest(), fp.partial())) {
                List<JsonObject> reused = reuse(p, prev, date, dayDir);
                if (reused != null) {
                    return CompletableFuture.completedFuture(reused);
                }
            }
            return drawAll(p, views, dayDir);
        });
    }

    /** Draws the four directions of a place into the day folder. */
    private CompletableFuture<List<JsonObject>> drawAll(AtlasConfig.KeyPlace p, IsoView[] views, Path dayDir) {
        List<CompletableFuture<JsonObject>> dirs = new ArrayList<>();
        for (IsoView v : views) {
            String name = KeyPlaces.fileLabel(p.label, v.azimuth());
            dirs.add(service.render(v).handle((d, err) -> {
                if (err != null || d == null) {
                    logger.at(Level.WARNING).log("Atlas could not draw the key place view %s of %s", name, p.world);
                    return null;
                }
                try {
                    Json.writeAtomic(file(dayDir, name), d.png(), -1);
                } catch (IOException e) {
                    logger.at(Level.WARNING).log("Atlas could not save the key place view %s of %s, %s", name, p.world,
                            e.getMessage());
                    return null;
                }
                JsonObject o = entry(p, d.view().radius(), d.view().scale(), v.azimuth());
                o.addProperty("print", KeyPlaces.printText(d.digest()));
                o.addProperty("renderedAt", d.renderedAt());
                o.addProperty("partial", d.partial());
                o.addProperty("unchanged", false);
                return o;
            }));
        }
        return CompletableFuture.allOf(dirs.toArray(new CompletableFuture[0])).handle((x, err) -> {
            List<JsonObject> out = new ArrayList<>();
            for (CompletableFuture<JsonObject> f : dirs) {
                JsonObject o = f.getNow(null);
                if (o != null) {
                    out.add(o);
                }
            }
            return out;
        });
    }

    /**
     * The entries of an earlier render taken over for this day, with the pictures linked into the day
     * folder. Null when a picture could not be linked or copied, so the place is drawn instead.
     */
    private List<JsonObject> reuse(AtlasConfig.KeyPlace p, Previous prev, String date, Path dayDir) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject e : prev.entries()) {
            int az = (int) Json.num(e, "azimuth", 0);
            String name = KeyPlaces.fileLabel(p.label, az);
            if (!prev.dayDir().equals(dayDir)) {
                try {
                    linkOrCopy(file(prev.dayDir(), name), file(dayDir, name));
                } catch (IOException ex) {
                    logger.at(Level.WARNING).log("Atlas could not reuse the key place view %s of %s, %s", name, p.world,
                            ex.getMessage());
                    return null;
                }
            }
            JsonObject o = e.deepCopy();
            String display = p.name == null ? "" : p.name;
            o.addProperty("name", display);
            o.addProperty("unchanged", true);
            String sameAs = Json.str(e, "sameAs");
            o.addProperty("sameAs", TimelineStore.validSlot(sameAs) ? sameAs : prev.date());
            out.add(o);
        }
        return out;
    }

    /** A hard link to the earlier picture, or a copy where links are not possible, replacing what is there. */
    static void linkOrCopy(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Path tmp = to.resolveSibling(to.getFileName() + Json.TMP_SUFFIX);
        Files.deleteIfExists(tmp);
        try {
            Files.createLink(tmp, from);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Files.copy(from, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JsonObject entry(AtlasConfig.KeyPlace p, int radius, int scale, int azimuth) {
        JsonObject o = new JsonObject();
        o.addProperty("label", p.label);
        o.addProperty("name", p.name == null ? "" : p.name);
        o.addProperty("keyPlace", true);
        o.addProperty("azimuth", azimuth);
        o.addProperty("x", p.x);
        o.addProperty("z", p.z);
        o.addProperty("radius", radius);
        o.addProperty("scale", scale);
        return o;
    }

    /**
     * The newest render of a key place on this day or before, from the days of the world that still
     * hold their snapshot, with all four directions in its index. Null when there is none.
     */
    static Previous previous(Path dayDir, String date, String label) {
        Path worldDir = dayDir.getParent();
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return null;
        }
        List<String> dates = new ArrayList<>();
        try (DirectoryStream<Path> days = Files.newDirectoryStream(worldDir, Files::isDirectory)) {
            for (Path d : days) {
                String name = d.getFileName().toString();
                if (TimelineStore.validSlot(name) && name.compareTo(date) <= 0) {
                    dates.add(name);
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        dates.sort(java.util.Comparator.reverseOrder());
        for (String d : dates) {
            Path dir = worldDir.resolve(d);
            if (!dayHeld(dir)) {
                continue;
            }
            List<JsonObject> found = new ArrayList<>();
            for (JsonObject v : indexViews(dir)) {
                if (Json.bool(v, "keyPlace") && label.equals(Json.str(v, "label"))) {
                    found.add(v);
                }
            }
            if (found.size() == KeyPlaces.AZIMUTHS.length) {
                return new Previous(d, dir, found);
            }
        }
        return null;
    }

    /** The entries of a day's index.json, empty when there is none or it does not parse. */
    static List<JsonObject> indexViews(Path dayDir) {
        List<JsonObject> out = new ArrayList<>();
        Path index = dayDir.resolve(DIR).resolve(INDEX);
        if (!Files.isRegularFile(index)) {
            return out;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(index, StandardCharsets.UTF_8));
            for (JsonElement e : Json.arr(root.getAsJsonObject(), "views")) {
                if (e.isJsonObject()) {
                    out.add(e.getAsJsonObject());
                }
            }
        } catch (IOException | RuntimeException e) {
            // A torn or hand edited index counts as none.
        }
        return out;
    }

    /** Rewrites a day's index.json, dropping the entries drop matches and adding the new ones. */
    private synchronized void writeIndex(String world, String date, Path dayDir, java.util.function.Predicate<JsonObject> drop,
                                         List<JsonObject> add) throws IOException {
        JsonArray list = new JsonArray();
        for (JsonObject o : indexViews(dayDir)) {
            if (!drop.test(o)) {
                list.add(o);
            }
        }
        for (JsonObject o : add) {
            list.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("world", world);
        root.addProperty("date", date);
        root.add("views", list);
        Json.writeAtomic(dayDir.resolve(DIR).resolve(INDEX), Json.PRETTY.toJson(root));
    }

    /**
     * Draws a key place now, for /atlas key render. When today already has a snapshot of its world the
     * four directions replace the ones of today in the timeline, otherwise they are saved under
     * iso/exports. Completes with the sentence for the command.
     */
    public CompletableFuture<String> renderNow(AtlasConfig.KeyPlace p) {
        AtlasConfig c = atlas.config();
        dev.ryvrook.atlas.timeline.Timeline t = timeline.get();
        String today = t == null ? null : t.today();
        if (t != null && c.timelineEnabled() && today != null && t.store().hasSnapshot(p.world, today)) {
            // Into the slot that holds today's views, or the last slot of today when none does yet.
            Path views = dayViewsDir(t.store().worldDir(p.world), today);
            Path dayDir = views != null ? views : t.store().dayDir(p.world, today);
            String slotName = dayDir.getFileName().toString();
            return keyPlace(p, slotName, dayDir, true).thenApply(list -> {
                if (list.isEmpty()) {
                    return "Nothing of " + p.displayName() + " could be drawn, since nothing around it is explored.";
                }
                try {
                    writeIndex(p.world, slotName, dayDir, o -> Json.bool(o, "keyPlace") && p.label.equals(Json.str(o, "label")), list);
                } catch (IOException e) {
                    return "The views of " + p.displayName() + " were drawn but the index could not be saved, " + e.getMessage() + ".";
                }
                return "Drew " + list.size() + " of 4 directions of " + p.displayName() + " into the timeline of " + p.world
                        + " for " + today + ".";
            });
        }
        List<CompletableFuture<String>> files = new ArrayList<>();
        for (int az : KeyPlaces.AZIMUTHS) {
            IsoView v = KeyPlaces.view(p, az, c.isoMaxRadius());
            if (v == null || !service.anyAllowed(v)) {
                return CompletableFuture.completedFuture("Nothing has been explored around " + p.displayName() + ".");
            }
            files.add(service.render(v).handle((d, err) -> {
                if (err != null || d == null) {
                    return null;
                }
                try {
                    return service.export(d).getFileName().toString();
                } catch (IOException e) {
                    return null;
                }
            }));
        }
        return CompletableFuture.allOf(files.toArray(new CompletableFuture[0])).handle((x, err) -> {
            long n = files.stream().map(f -> f.getNow(null)).filter(java.util.Objects::nonNull).count();
            return "Saved " + n + " of 4 directions of " + p.displayName() + " under iso/exports. Today has no snapshot of "
                    + p.world + " yet, so they join the timeline with the next one.";
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
