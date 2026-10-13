package dev.ryvrook.atlas.iso;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.auth.LinkService;
import dev.ryvrook.atlas.auth.Viewer;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.http.RateLimiter;
import dev.ryvrook.atlas.timeline.Timeline;
import dev.ryvrook.atlas.timeline.TimelineRoutes;
import dev.ryvrook.atlas.timeline.TimelineStore;
import dev.ryvrook.atlas.util.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The routes that answer with images or streams rather than JSON bodies, reached from ApiServer
 * through one dispatch line. They are
 *
 * <ul>
 *   <li>api/iso, what the server allows</li>
 *   <li>api/iso/{world} with x, z, r, az and s, an isometric view</li>
 *   <li>api/iso/{world}/marker/{markerId} with layer, r, az and s, the view around a marker</li>
 *   <li>api/timeline/{world}/{date}/iso/{label}.png, a daily isometric view</li>
 *   <li>api/timeline/{world}/export with from and to, handed to TimelineRoutes</li>
 * </ul>
 *
 * <p>Views follow the terrain layer and the iso layer, both must be visible. A disabled, hidden or
 * unknown world and an area with nothing explored answer 404.
 */
public final class IsoRoutes {
    private static volatile IsoRoutes current;

    private final Atlas atlas;
    private final IsoService service;
    private final Supplier<Timeline> timeline;
    private final TimelineRoutes timelineRoutes;
    private final RateLimiter limiter = new RateLimiter();

    public IsoRoutes(Atlas atlas, IsoService service, Supplier<Timeline> timeline, TimelineRoutes timelineRoutes) {
        this.atlas = atlas;
        this.service = service;
        this.timeline = timeline;
        this.timelineRoutes = timelineRoutes;
    }

    /** Installs the routes ApiServer hands requests to, or removes them with null. */
    public static void install(IsoRoutes routes) {
        current = routes;
    }

    /**
     * Answers the request when it is one of these routes and returns true, otherwise returns false
     * and leaves the exchange alone. Called by ApiServer with the decoded path parts after /api/.
     */
    public static boolean serve(HttpExchange ex, Viewer viewer, List<String> parts, Map<String, String> query, String address)
            throws IOException {
        IsoRoutes r = current;
        if (r == null || parts.isEmpty()) {
            return false;
        }
        String head = parts.get(0);
        if ("iso".equals(head)) {
            r.iso(ex, viewer, parts, query, address);
            return true;
        }
        if ("timeline".equals(head) && parts.size() == 5 && "iso".equals(parts.get(3))) {
            r.dayView(ex, viewer, parts);
            return true;
        }
        if ("timeline".equals(head) && TimelineRoutes.isExport(parts) && r.timelineRoutes != null) {
            r.timelineRoutes.export(ex, viewer, parts.get(1), query);
            return true;
        }
        return false;
    }

    private AtlasConfig cfg() {
        return atlas.config();
    }

    // ---- api/iso -------------------------------------------------------------

    private void iso(HttpExchange ex, Viewer v, List<String> parts, Map<String, String> query, String address) throws IOException {
        AtlasConfig c = cfg();
        if (parts.size() == 1) {
            json(ex, 200, capabilities(v));
            return;
        }
        if (!c.isoEnabled()) {
            error(ex, 404, "Isometric views are turned off on this server.");
            return;
        }
        String world = parts.get(1);
        if (atlas.hiddenWorld(v, world) || !atlas.worldExists(world) || !c.worldEnabled(world)) {
            error(ex, 404, "No world with that name.");
            return;
        }
        if (!v.canSee(c, "terrain") || !v.canSee(c, "iso")) {
            error(ex, 403, "This layer is not visible to you.");
            return;
        }
        IsoView.Parsed p;
        if (parts.size() == 2) {
            p = IsoView.parse(world, query, c.isoMaxRadius());
        } else if (parts.size() == 4 && "marker".equals(parts.get(2))) {
            double[] at = resolveMarker(v, world, parts.get(3), query.get("layer"));
            if (at == null) {
                error(ex, 404, "No marker with that id is visible to you in this world.");
                return;
            }
            String r = query.get("r");
            if ((r == null || r.isBlank()) && at[2] > 0) {
                r = Integer.toString((int) Math.max(1, Math.min(c.isoMaxRadius(), Math.ceil(at[2] / 32.0))));
            }
            p = IsoView.of(world, Math.round(at[0]), Math.round(at[1]), r, query.get("az"), query.get("s"), c.isoMaxRadius());
        } else {
            error(ex, 404, "Not found.");
            return;
        }
        if (p.view() == null) {
            error(ex, 400, p.error());
            return;
        }
        IsoView view = p.view();
        if (!service.anyAllowed(view)) {
            error(ex, 404, "Nothing has been explored in that area.");
            return;
        }
        boolean exempt = exempt(ex);
        IsoService.Answer a = service.request(view, () -> exempt ? 0
                : limiter.acquire(LinkService.rateKey(address), c.isoRequestsPerMinute()));
        Headers h = ex.getResponseHeaders();
        h.set("X-Atlas-Iso", view.world() + "," + view.blockX() + "," + view.blockZ() + "," + view.radius() + ","
                + view.azimuth() + "," + view.scale());
        switch (a.kind()) {
            case READY -> png(ex, a.entry().png(), a.entry().etag(), a.entry().partial() ? "no-store" : "public, max-age=300",
                    a.entry().partial());
            case QUEUED -> {
                h.set("Retry-After", Integer.toString(a.etaSeconds()));
                h.set("Cache-Control", "no-store");
                JsonObject o = new JsonObject();
                o.addProperty("ok", true);
                o.addProperty("queued", true);
                o.addProperty("etaSeconds", a.etaSeconds());
                o.add("view", viewJson(view));
                json(ex, 202, o);
            }
            case LIMITED -> {
                h.set("Retry-After", Integer.toString(a.retryAfter()));
                error(ex, 429, "Too many new views from this address. Wait a little and try again.");
            }
            case BUSY -> {
                h.set("Retry-After", Integer.toString(a.retryAfter()));
                error(ex, 429, "The server is drawing too many views right now. Try again shortly.");
            }
            case FAILED -> {
                h.set("Retry-After", Integer.toString(a.retryAfter()));
                error(ex, 503, "This view could not be drawn. Try again in a moment.");
            }
        }
    }

    private JsonObject capabilities(Viewer v) {
        AtlasConfig c = cfg();
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("enabled", c.isoEnabled());
        o.addProperty("visible", v.canSee(c, "terrain") && v.canSee(c, "iso"));
        o.addProperty("maxRadius", c.isoMaxRadius());
        o.addProperty("defaultRadius", Math.min(IsoView.DEFAULT_RADIUS, c.isoMaxRadius()));
        o.addProperty("defaultScale", IsoView.DEFAULT_SCALE);
        JsonArray az = new JsonArray();
        for (int a : new int[]{0, 90, 180, 270}) {
            az.add(a);
        }
        o.add("azimuths", az);
        JsonObject scales = new JsonObject();
        for (int r = 1; r <= c.isoMaxRadius(); r++) {
            scales.addProperty(Integer.toString(r), IsoView.maxScale(r));
        }
        o.add("maxScale", scales);
        return o;
    }

    private static JsonObject viewJson(IsoView v) {
        JsonObject o = new JsonObject();
        o.addProperty("world", v.world());
        o.addProperty("x", v.blockX());
        o.addProperty("z", v.blockZ());
        o.addProperty("radius", v.radius());
        o.addProperty("azimuth", v.azimuth());
        o.addProperty("scale", v.scale());
        return o;
    }

    /**
     * The centre of a marker the viewer may see as {x, z, halfSize}, or null. Markers of
     * api/markers come first (personal markers are only listed for their owner), then the marker
     * layers, only the named one when layer is given. halfSize is 0 for points and half the larger
     * side of a shape otherwise.
     */
    double[] resolveMarker(Viewer v, String world, String id, String layer) {
        AtlasConfig c = cfg();
        if ((layer == null || layer.isBlank()) && v.canSee(c, "markers")) {
            JsonObject p = atlas.markersPayload(v, world);
            for (JsonElement e : Json.arr(p, "markers")) {
                JsonObject m = e.getAsJsonObject();
                if (id.equals(Json.str(m, "id"))) {
                    return new double[]{Json.num(m, "x", 0), Json.num(m, "z", 0), 0};
                }
            }
        }
        if (!v.canSee(c, "layers")) {
            return null;
        }
        JsonObject p = atlas.markers().layersPayload(v, world);
        for (JsonElement le : Json.arr(p, "layers")) {
            JsonObject l = le.getAsJsonObject();
            if (layer != null && !layer.isBlank() && !layer.equals(Json.str(l, "id"))) {
                continue;
            }
            for (JsonElement me : Json.arr(l, "markers")) {
                JsonObject m = me.getAsJsonObject();
                if (id.equals(Json.str(m, "id"))) {
                    return centre(m);
                }
            }
        }
        return null;
    }

    static double[] centre(JsonObject m) {
        JsonArray pts = m.has("points") && m.get("points").isJsonArray() ? m.getAsJsonArray("points") : null;
        if (pts != null && !pts.isEmpty()) {
            double minX = Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            for (JsonElement e : pts) {
                JsonArray p = e.getAsJsonArray();
                minX = Math.min(minX, p.get(0).getAsDouble());
                maxX = Math.max(maxX, p.get(0).getAsDouble());
                minZ = Math.min(minZ, p.get(1).getAsDouble());
                maxZ = Math.max(maxZ, p.get(1).getAsDouble());
            }
            return new double[]{(minX + maxX) / 2, (minZ + maxZ) / 2, Math.max(maxX - minX, maxZ - minZ) / 2};
        }
        double radius = Json.num(m, "radius", 0);
        return new double[]{Json.num(m, "x", 0), Json.num(m, "z", 0), Math.max(0, radius)};
    }

    // ---- api/timeline/{world}/{date}/iso/{label}.png ---------------------------

    private void dayView(HttpExchange ex, Viewer v, List<String> parts) throws IOException {
        AtlasConfig c = cfg();
        Timeline t = timeline.get();
        String world = parts.get(1);
        String date = parts.get(2);
        String file = parts.get(4);
        if (t == null || !c.timelineEnabled()) {
            error(ex, 404, "The timeline is turned off on this server.");
            return;
        }
        if (!c.worldEnabled(world) || atlas.hiddenWorld(v, world)) {
            error(ex, 404, "There is no such world.");
            return;
        }
        if (!v.canSee(c, "terrain") || !v.canSee(c, "iso")) {
            error(ex, 403, "This layer is not visible to you.");
            return;
        }
        if (!TimelineStore.validDate(date)) {
            error(ex, 400, "The date must look like 2026-01-31.");
            return;
        }
        String label = file.endsWith(".png") ? file.substring(0, file.length() - 4) : null;
        Path f = AtlasConfig.validIsoLabel(label) ? IsoSnapshots.file(t.store().dayDir(world, date), label) : null;
        byte[] data;
        try {
            data = f == null || !IsoSnapshots.dayHeld(t.store().dayDir(world, date)) ? null : Files.readAllBytes(f);
        } catch (IOException e) {
            data = null;
        }
        if (data == null) {
            error(ex, 404, "There is no isometric view with that label on that day.");
            return;
        }
        boolean past = date.compareTo(t.today()) < 0;
        png(ex, data, dev.ryvrook.atlas.tiles.TileService.etag(data),
                past ? "public, max-age=31536000, immutable" : "public, max-age=300", false);
    }

    // ---- responses -----------------------------------------------------------

    /** True when the address is not limited, a local or private peer while no trusted proxy is set, as for the API. */
    private boolean exempt(HttpExchange ex) {
        if (!atlas.proxies().isEmpty()) {
            return false;
        }
        java.net.InetAddress a = ex.getRemoteAddress() == null ? null : ex.getRemoteAddress().getAddress();
        if (a == null) {
            return false;
        }
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    private static void png(HttpExchange ex, byte[] data, String etag, String cache, boolean partial) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Cache-Control", cache);
        h.set("ETag", etag);
        if (partial) {
            h.set("X-Atlas-Partial", "1");
        }
        String match = ex.getRequestHeaders().getFirst("If-None-Match");
        if (match != null && etagMatches(match, etag)) {
            ex.sendResponseHeaders(304, -1);
            return;
        }
        h.set("Content-Type", "image/png");
        send(ex, 200, data);
    }

    static boolean etagMatches(String header, String etag) {
        for (String part : header.split(",")) {
            String t = part.trim();
            if (t.startsWith("W/")) {
                t = t.substring(2);
            }
            if (t.equals("*") || t.equals(etag)) {
                return true;
            }
        }
        return false;
    }

    private static void error(HttpExchange ex, int status, String message) throws IOException {
        json(ex, status, dev.ryvrook.atlas.data.Payloads.error(message));
    }

    private static void json(HttpExchange ex, int status, JsonObject body) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("X-Content-Type-Options", "nosniff");
        if (!h.containsKey("Cache-Control")) {
            h.set("Cache-Control", "no-store");
        }
        send(ex, status, Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, byte[] body) throws IOException {
        if ("HEAD".equals(ex.getRequestMethod())) {
            if (body.length > 0) {
                ex.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            }
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
