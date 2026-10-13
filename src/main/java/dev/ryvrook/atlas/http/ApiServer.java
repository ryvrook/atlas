package dev.ryvrook.atlas.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.auth.LinkService;
import dev.ryvrook.atlas.auth.Viewer;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.data.LiveState;
import dev.ryvrook.atlas.tiles.TileService;
import dev.ryvrook.atlas.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;

/**
 * The embedded web server, built on com.sun.net.httpserver from the jdk.httpserver module.
 * Requests run on virtual threads so long lived event streams cost almost nothing.
 *
 * <p>At most MAX_IN_FLIGHT ordinary requests are handled at once and the rest get 503 with
 * Retry-After. Event streams are left out of that count because SseHub holds its own limits.
 * A client gets REQUEST_SECONDS to send its request line, headers and body before the
 * connection is closed, through the sun.net.httpserver.maxReqTime property of the JDK server.
 */
public final class ApiServer {
    public static final String COOKIE = "atlas_session";
    /** Ordinary requests handled at the same time. Event streams do not count. */
    static final int MAX_IN_FLIGHT = 64;
    /** Seconds a client may take to send a whole request. */
    static final int REQUEST_SECONDS = 30;
    private static final String STATIC_CACHE = "public, max-age=86400";
    /** Inline script elements without attributes in index.html. */
    private static final java.util.regex.Pattern INLINE_SCRIPT = java.util.regex.Pattern.compile(
            "<script>(.*?)</script>", java.util.regex.Pattern.DOTALL);
    /**
     * Content Security Policy of the page and its files. Styles allow inline attributes because the
     * client sets faction colours and bar widths in style attributes it builds at run time. The page
     * itself also allows its one inline boot script by hash, see indexCsp.
     */
    static final String CSP = "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
            + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    static {
        // The JDK server reads this once, when its first instance is made. An owner who set it keeps their value.
        if (System.getProperty("sun.net.httpserver.maxReqTime") == null) {
            System.setProperty("sun.net.httpserver.maxReqTime", Integer.toString(REQUEST_SECONDS));
        }
    }
    /** src and href values of local scripts, stylesheets and images in index.html. */
    private static final java.util.regex.Pattern ASSET_REF = java.util.regex.Pattern.compile(
            "((?:src|href)=\")([A-Za-z0-9_][A-Za-z0-9_./@+-]*\\.(?:js|css|png|svg|ico|webp|jpg|json|webmanifest))(\")");
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("map", "application/json; charset=utf-8"),
            Map.entry("png", "image/png"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("webp", "image/webp"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("woff", "font/woff"),
            Map.entry("txt", "text/plain; charset=utf-8"));

    /** One bound HTTP server with its own request executor. */
    private record Bound(HttpServer server, ExecutorService executor, String bind, int port) {
        void stop() {
            server.stop(1);
            executor.shutdownNow();
        }
    }

    private final Atlas atlas;
    private final com.hypixel.hytale.logger.HytaleLogger logger;
    private Bound bound;
    private volatile byte[] indexHtml;
    private volatile String indexEtag;
    private volatile String indexCsp = CSP;
    private final java.util.concurrent.atomic.AtomicBoolean proxyWarned = new java.util.concurrent.atomic.AtomicBoolean();
    /** Static files read from the jar, which never change while the server runs. Only files that exist are kept. */
    private final Map<String, byte[]> staticFiles = new java.util.concurrent.ConcurrentHashMap<>();
    private final MarkerIcons markerIcons = new MarkerIcons();
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);

    public ApiServer(Atlas atlas, com.hypixel.hytale.logger.HytaleLogger logger) {
        this.atlas = atlas;
        this.logger = logger;
    }

    /** Binds the configured address and starts serving. Throws when the address cannot be bound. */
    public synchronized void start() throws IOException {
        AtlasConfig cfg = atlas.config();
        setIndex(loadIndex(cfg.publicTitle));
        bound = bind(cfg);
    }

    private Bound bind(AtlasConfig cfg) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(cfg.bind, cfg.port), 64);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        return new Bound(server, executor, cfg.bind, cfg.port);
    }

    /**
     * Applies new settings. When the bind address or port changed, the new address is bound first and
     * the old server is stopped only after that worked, so a failed bind keeps the map reachable on
     * the old address. Title and basePath apply without a new bind, since every request reads the
     * current settings. Returns a problem or null.
     */
    public synchronized String reconfigure() {
        AtlasConfig cfg = atlas.config();
        try {
            setIndex(loadIndex(cfg.publicTitle));
        } catch (IOException e) {
            return "The web client could not be read from the plugin jar. " + e.getMessage();
        }
        if (bound != null && bound.bind().equals(cfg.bind) && bound.port() == cfg.port) {
            return null;
        }
        Bound next;
        try {
            next = bind(cfg);
        } catch (IOException | RuntimeException e) {
            String old = bound == null ? "" : " The map stays on " + bound.bind() + ":" + bound.port() + ".";
            logger.at(Level.SEVERE).withCause(e).log("Atlas could not bind its web server to %s:%d and keeps the old address",
                    cfg.bind, cfg.port);
            return "The web server could not bind to " + cfg.bind + ":" + cfg.port + ". " + e.getMessage() + old;
        }
        Bound old = bound;
        bound = next;
        if (old != null) {
            // Open streams belong to the old server. They reconnect to the new one on their own.
            atlas.sse().closeAll();
            old.stop();
        }
        return null;
    }

    public synchronized void stop() {
        atlas.sse().closeAll();
        if (bound != null) {
            bound.stop();
            bound = null;
        }
    }

    public synchronized boolean running() {
        return bound != null;
    }

    public synchronized String boundAddress() {
        return bound == null ? null : bound.bind() + ":" + bound.port();
    }

    private byte[] loadIndex(String title) throws IOException {
        try (InputStream in = ApiServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                return "<!doctype html><title>Atlas</title><p>The web client is missing from the plugin jar.</p>"
                        .getBytes(StandardCharsets.UTF_8);
            }
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            html = versionAssets(html, AtlasVersion.VERSION);
            return html.replace("{{TITLE}}", escapeHtml(title)).getBytes(StandardCharsets.UTF_8);
        }
    }

    private void setIndex(byte[] html) {
        indexHtml = html;
        indexEtag = MarkerIcons.etagOf(html);
        indexCsp = indexCsp(new String(html, StandardCharsets.UTF_8));
    }

    /**
     * The policy for index.html. The boot script that picks the theme and fixes a missing trailing
     * slash has to run before any relative address is resolved, so it stays inline and is allowed by
     * its SHA-256 hash rather than by 'unsafe-inline'.
     */
    static String indexCsp(String html) {
        StringBuilder hashes = new StringBuilder();
        java.util.regex.Matcher m = INLINE_SCRIPT.matcher(html);
        while (m.find()) {
            try {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(m.group(1).getBytes(StandardCharsets.UTF_8));
                hashes.append(" 'sha256-").append(java.util.Base64.getEncoder().encodeToString(d)).append('\'');
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return hashes.isEmpty() ? CSP : CSP + "; script-src 'self'" + hashes;
    }

    /**
     * Adds ?v=(version) to every local script, stylesheet and image reference, so a browser fetches
     * the files of a new jar at once while each version can still be cached for a long time.
     */
    static String versionAssets(String html, String version) {
        String v = java.net.URLEncoder.encode(version == null ? "0" : version, StandardCharsets.UTF_8);
        return ASSET_REF.matcher(html).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                m.group(1) + m.group(2) + "?v=" + v + m.group(3)));
    }

    /** Sends index.html with no-cache and an ETag, or 304 when the browser already has it. */
    private void sendIndex(HttpExchange ex, String method) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/html; charset=utf-8");
        h.set("Cache-Control", "no-cache");
        securityHeaders(h, indexCsp);
        String etag = indexEtag;
        if (etag != null) {
            h.set("ETag", etag);
            String match = ex.getRequestHeaders().getFirst("If-None-Match");
            if (match != null && etagMatches(match, etag)) {
                ex.sendResponseHeaders(304, -1);
                return;
            }
        }
        send(ex, 200, indexHtml, "HEAD".equals(method));
    }

    /** Headers sent with the page and every static file. */
    static void securityHeaders(Headers h, String csp) {
        h.set("Content-Security-Policy", csp);
        h.set("Referrer-Policy", "same-origin");
        h.set("X-Content-Type-Options", "nosniff");
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Reads a file below web in the jar, or returns null. The relative path must be plain segments,
     * so nothing outside the web folder is ever read.
     */
    private byte[] staticFile(String rel) {
        byte[] cached = staticFiles.get(rel);
        if (cached != null) {
            return cached;
        }
        byte[] data = null;
        try {
            java.net.URL url = ApiServer.class.getResource("/web/" + rel);
            if (url != null && !url.getPath().endsWith("/")) {
                try (InputStream in = url.openStream()) {
                    data = in.readAllBytes();
                }
            }
        } catch (IOException | RuntimeException e) {
            data = null;
        }
        // Misses are not kept, so requests for made up names cannot fill the cache.
        if (data != null) {
            staticFiles.put(rel, data);
        }
        return data;
    }

    /** A safe relative path below web, or null when the path holds anything unusual. */
    static String safeRelative(String decoded) {
        if (decoded == null || decoded.isEmpty() || decoded.length() > 200 || decoded.indexOf('\\') >= 0
                || decoded.indexOf('\0') >= 0 || decoded.startsWith("/")) {
            return null;
        }
        for (String seg : decoded.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..") || seg.startsWith(".")) {
                return null;
            }
            for (int i = 0; i < seg.length(); i++) {
                char c = seg.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == '@' || c == '+')) {
                    return null;
                }
            }
        }
        int dot = decoded.lastIndexOf('.');
        return dot > decoded.lastIndexOf('/') && dot < decoded.length() - 1 ? decoded : null;
    }

    private static String typeOf(String rel) {
        String ext = rel.substring(rel.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        return TYPES.getOrDefault(ext, "application/octet-stream");
    }

    // ---- dispatch ----------------------------------------------------------

    /** Strips the configured basePath from a raw request path. Returns null when the path is outside it. */
    static String stripBase(String path, String basePath) {
        if (basePath == null || basePath.isEmpty()) {
            return path;
        }
        if (path.equals(basePath)) {
            return "";
        }
        if (path.startsWith(basePath + "/")) {
            return path.substring(basePath.length());
        }
        return path;
    }

    /**
     * Entry point of every request. Event streams go straight through. Every other request takes a
     * slot of MAX_IN_FLIGHT and gets 503 with Retry-After when none is free.
     */
    private void handle(HttpExchange ex) throws IOException {
        if (isEventStream(ex)) {
            dispatch(ex);
            return;
        }
        if (!inFlight.tryAcquire()) {
            try {
                ex.getResponseHeaders().set("Retry-After", "1");
                error(ex, 503, "The map is busy. Try again in a moment.");
            } catch (IOException ignored) {
                // The client went away.
            } finally {
                ex.close();
            }
            return;
        }
        try {
            dispatch(ex);
        } finally {
            inFlight.release();
        }
    }

    /** True for api/events, with or without the basePath prefix. */
    private boolean isEventStream(HttpExchange ex) {
        String path = stripBase(ex.getRequestURI().getRawPath(), atlas.config().basePath);
        return path.equals("/api/events") || path.startsWith("/api/events/");
    }

    private void dispatch(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String rawPath = ex.getRequestURI().getRawPath();
            String basePath = atlas.config().basePath;
            String path = stripBase(rawPath, basePath);
            if (path.isEmpty()) {
                // The bare mount point. Relative links need the trailing slash.
                ex.getResponseHeaders().set("Location", basePath + "/");
                ex.sendResponseHeaders(301, -1);
                return;
            }
            if ("OPTIONS".equals(method)) {
                cors(ex.getResponseHeaders());
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-Atlas-Token, Authorization");
                ex.getResponseHeaders().set("Access-Control-Max-Age", "600");
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if ("/".equals(path) || "/index.html".equals(path)) {
                if (!"GET".equals(method) && !"HEAD".equals(method)) {
                    error(ex, 405, "Only GET is allowed here.");
                    return;
                }
                sendIndex(ex, method);
                return;
            }
            if (!path.startsWith("/api/")) {
                serveStatic(ex, method, path);
                return;
            }
            List<String> parts = new ArrayList<>();
            Map<String, String> query;
            try {
                for (String p : path.substring(5).split("/")) {
                    if (!p.isEmpty()) {
                        parts.add(URLDecoder.decode(p, StandardCharsets.UTF_8));
                    }
                }
                query = query(ex.getRequestURI().getRawQuery());
            } catch (IllegalArgumentException e) {
                error(ex, 400, "The address is not encoded correctly.");
                return;
            }
            if (parts.isEmpty()) {
                error(ex, 404, "Not found.");
                return;
            }
            String head = parts.get(0);
            if ("link".equals(head)) {
                if (!"POST".equals(method)) {
                    error(ex, 405, "Post a code to link this browser.");
                    return;
                }
                handleLink(ex);
                return;
            }
            if (!"GET".equals(method) && !("HEAD".equals(method) && "marker-icons".equals(head))) {
                error(ex, 405, "Only GET is allowed here.");
                return;
            }
            if ("marker-icons".equals(head)) {
                // Game images, the same for everyone. No session is looked at.
                handleMarkerIcon(ex, parts, "HEAD".equals(method));
                return;
            }
            String address = clientAddress(ex);
            boolean tokenGiven = givenToken(ex.getRequestHeaders()) != null;
            if (tokenGiven && atlas.links().blocked(address)) {
                error(ex, 429, "Too many attempts. Wait a minute and try again.");
                return;
            }
            boolean tokenAdmin = tokenAdmin(ex.getRequestHeaders());
            if (tokenGiven && !tokenAdmin) {
                // A wrong admin token counts against the same per address limit as link codes.
                atlas.links().rateLimited(address);
            }
            Viewer viewer = atlas.viewer(atlas.links().session(cookie(ex.getRequestHeaders(), COOKIE)), tokenAdmin, query.get("as"));
            String namedWorld = parts.size() >= 2 && WORLD_IN_PATH.contains(head) ? parts.get(1)
                    : WORLD_IN_QUERY.contains(head) ? query.get("world") : null;
            if (worldHidden(viewer, namedWorld)) {
                // Portal and instance worlds stay out of reach for non admins while showPortalWorlds is false.
                error(ex, 404, "Not found.");
                return;
            }
            switch (head) {
                case "health" -> json(ex, 200, health(viewer));
                case "worlds" -> json(ex, 200, atlas.worldsPayload(viewer));
                case "players" -> {
                    JsonObject p = atlas.playersApiPayload(viewer, query.get("world"),
                            "1".equals(query.get("trails")) || "true".equals(query.get("trails")));
                    dropHiddenWorldRows(viewer, p, "players");
                    dropHiddenWorldRows(viewer, p, "offline");
                    json(ex, 200, p);
                }
                case "markers" -> {
                    if (parts.size() < 2) {
                        error(ex, 400, "Name a world.");
                    } else {
                        json(ex, 200, atlas.markersPayload(viewer, parts.get(1)));
                    }
                }
                case "worldevents" -> {
                    JsonObject p = atlas.worldEventsPayload(viewer, query.get("world"));
                    dropHiddenWorldRows(viewer, p, "events");
                    json(ex, 200, p);
                }
                case "kills" -> json(ex, 200, atlas.killsPayload(viewer, (int) Math.min(200, Math.max(1,
                        parseLong(query.get("limit"), 50)))));
                case "bosses" -> json(ex, 200, atlas.bossesPayload(viewer));
                case "leaderboard" -> json(ex, 200, atlas.leaderboardPayload(viewer));
                case "territories" -> {
                    if (parts.size() < 2) {
                        error(ex, 400, "Name a world.");
                    } else {
                        json(ex, 200, atlas.territoriesPayload(viewer, parts.get(1)));
                    }
                }
                case "points" -> {
                    if (parts.size() < 2) {
                        error(ex, 400, "Name a world.");
                    } else {
                        json(ex, 200, atlas.pointsPayload(viewer, parts.get(1)));
                    }
                }
                case "factions" -> {
                    if (parts.size() < 2) {
                        json(ex, 200, atlas.factionsPayload(viewer));
                    } else {
                        JsonObject f = atlas.factionPayload(viewer, parts.get(1));
                        if (f == null) {
                            error(ex, 404, "No faction with that tag is visible.");
                        } else {
                            json(ex, 200, f);
                        }
                    }
                }
                case "stats" -> json(ex, 200, atlas.statsPayload(viewer));
                case "changes" -> {
                    if (parts.size() < 2) {
                        error(ex, 400, "Name a world.");
                    } else {
                        long since = parseLong(query.get("since"), System.currentTimeMillis() - 60 * 60 * 1000L);
                        json(ex, 200, atlas.changesPayload(viewer, parts.get(1), since));
                    }
                }
                case "tiles" -> handleTile(ex, viewer, parts);
                case "events" -> handleEvents(ex, viewer, tokenAdmin, query.get("world"), query.get("as"));
                case "nations" -> json(ex, 200, atlas.nationsPayload(viewer));
                default -> error(ex, 404, "Not found.");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            logger.at(Level.WARNING).withCause(e).log("Atlas request from %s failed for %s", clientAddress(ex), ex.getRequestURI());
            try {
                error(ex, 500, "The server hit an error.");
            } catch (Exception ignored) {
                // Headers were already sent.
            }
        } finally {
            ex.close();
        }
    }

    private void serveStatic(HttpExchange ex, String method, String path) throws IOException {
        String decoded;
        try {
            decoded = URLDecoder.decode(path.substring(1).replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            error(ex, 400, "The address is not encoded correctly.");
            return;
        }
        String rel = safeRelative(decoded);
        byte[] data = rel == null ? null : staticFile(rel);
        if (data == null) {
            error(ex, 404, "Not found.");
            return;
        }
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            error(ex, 405, "Only GET is allowed here.");
            return;
        }
        if (rel.equals("index.html")) {
            sendIndex(ex, method);
            return;
        }
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", typeOf(rel));
        h.set("Cache-Control", STATIC_CACHE);
        securityHeaders(h, CSP);
        send(ex, 200, data, "HEAD".equals(method));
    }

    // ---- identity ----------------------------------------------------------

    /**
     * True when the browser reached the map over https. Atlas itself only speaks http, so that is a
     * trusted proxy saying so in X-Forwarded-Proto. publicUrl alone is not enough, since a browser on the
     * plain http address would then get a cookie it never sends back.
     */
    private boolean overHttps(HttpExchange ex) {
        String proto = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
        if (proto == null) {
            return false;
        }
        String peer = ex.getRemoteAddress() == null || ex.getRemoteAddress().getAddress() == null
                ? null : ex.getRemoteAddress().getAddress().getHostAddress();
        return peer != null && atlas.proxies().trusts(peer)
                && proto.split(",")[0].trim().equalsIgnoreCase("https");
    }

    /**
     * Path is always the root so the cookie works with and without the basePath prefix, and logout
     * clears cookies set by any earlier version.
     */
    private String cookieAttributes(HttpExchange ex) {
        return "; Path=/; HttpOnly; SameSite=Lax" + (overHttps(ex) ? "; Secure" : "");
    }

    /** Endpoints whose second path part names a world. */
    private static final java.util.Set<String> WORLD_IN_PATH = java.util.Set.of("tiles", "markers", "territories", "points", "changes");
    /** Endpoints that take a world query parameter. */
    private static final java.util.Set<String> WORLD_IN_QUERY = java.util.Set.of("players", "worldevents", "events");

    /**
     * True when the world is a portal or instance world that this viewer may not see, which is any
     * non admin while showPortalWorlds is false. The world picker hides the same worlds.
     */
    private boolean worldHidden(Viewer v, String world) {
        if (world == null || v.admin() || atlas.config().showPortalWorlds) {
            return false;
        }
        LiveState.WorldInfo info = atlas.live().worlds().get(world);
        return info != null && info.kind() != null && info.kind().transientWorld();
    }

    /** Removes rows of hidden worlds from a list in the payload. The viewer's own row stays. */
    private void dropHiddenWorldRows(Viewer v, JsonObject payload, String key) {
        if (v.admin() || atlas.config().showPortalWorlds || payload == null || !payload.has(key) || !payload.get(key).isJsonArray()) {
            return;
        }
        com.google.gson.JsonArray kept = new com.google.gson.JsonArray();
        for (JsonElement e : payload.getAsJsonArray(key)) {
            if (e.isJsonObject() && !Json.bool(e.getAsJsonObject(), "self") && worldHidden(v, Json.str(e.getAsJsonObject(), "world"))) {
                continue;
            }
            kept.add(e);
        }
        payload.add(key, kept);
    }

    /**
     * The address of the browser. Behind a trusted reverse proxy this is the last untrusted address in
     * X-Forwarded-For, otherwise the direct peer. Used for rate limits and log lines.
     */
    String clientAddress(HttpExchange ex) {
        String peer = ex.getRemoteAddress() == null || ex.getRemoteAddress().getAddress() == null
                ? "?" : ex.getRemoteAddress().getAddress().getHostAddress();
        return atlas.proxies().client(peer, ex.getRequestHeaders().get("X-Forwarded-For"));
    }

    /** The admin token a request carries in X-Atlas-Token or as a Bearer token, or null. */
    private static String givenToken(Headers h) {
        String given = h.getFirst("X-Atlas-Token");
        if (given == null) {
            String auth = h.getFirst("Authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                given = auth.substring(7).trim();
            }
        }
        return given == null || given.isEmpty() ? null : given;
    }

    private boolean tokenAdmin(Headers h) {
        String expected = atlas.config().adminToken;
        if (expected == null || expected.isBlank()) {
            return false;
        }
        String given = givenToken(h);
        return given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private static String cookie(Headers h, String name) {
        List<String> all = h.get("Cookie");
        if (all == null) {
            return null;
        }
        for (String line : all) {
            for (String part : line.split(";")) {
                String p = part.trim();
                if (p.startsWith(name + "=")) {
                    return p.substring(name.length() + 1);
                }
            }
        }
        return null;
    }

    private void handleLink(HttpExchange ex) throws IOException {
        String address = clientAddress(ex);
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            // Plain form posts from other sites cannot set this type without a CORS preflight.
            error(ex, 415, "Send the code as JSON.");
            return;
        }
        byte[] body = ex.getRequestBody().readNBytes(4096);
        String code = null;
        String action = null;
        try {
            JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                code = Json.str(parsed.getAsJsonObject(), "code");
                action = Json.str(parsed.getAsJsonObject(), "action");
            } else if (parsed.isJsonPrimitive()) {
                code = parsed.getAsString();
            }
        } catch (RuntimeException e) {
            code = null;
        }
        if ("logout".equals(action)) {
            atlas.links().revoke(cookie(ex.getRequestHeaders(), COOKIE));
            ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Max-Age=0" + cookieAttributes(ex));
            String base = atlas.config().basePath;
            if (!base.isEmpty()) {
                // Earlier versions scoped the cookie to the basePath. Clear that one as well.
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Max-Age=0; Path=" + base + "/; HttpOnly; SameSite=Lax");
            }
            JsonObject ok = new JsonObject();
            ok.addProperty("ok", true);
            json(ex, 200, ok);
            return;
        }
        if (atlas.links().rateLimited(address)) {
            error(ex, 429, "Too many attempts. Wait a minute and try again.");
            return;
        }
        LinkService.Linked linked = atlas.links().redeem(code);
        if (linked == null) {
            error(ex, 400, "That code is not valid. Type /atlas link in game for a new one.");
            return;
        }
        long maxAge = LinkService.SESSION_TTL_MS / 1000;
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + linked.token() + "; Max-Age=" + maxAge + cookieAttributes(ex));
        Viewer v = atlas.viewer(linked.session(), false, null);
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.add("viewer", atlas.viewerJson(v));
        json(ex, 200, out);
    }

    // ---- tiles and events --------------------------------------------------

    private void handleTile(HttpExchange ex, Viewer viewer, List<String> parts) throws IOException {
        if (parts.size() < 5) {
            error(ex, 400, "Use /api/tiles/world/zoom/x/z.png");
            return;
        }
        if (!viewer.canSee(atlas.config(), "terrain")) {
            error(ex, 403, "The terrain layer is not visible to you.");
            return;
        }
        String last = parts.get(4);
        if (last.endsWith(".png")) {
            last = last.substring(0, last.length() - 4);
        }
        int zoom;
        int x;
        int z;
        try {
            zoom = Integer.parseInt(parts.get(2));
            x = Integer.parseInt(parts.get(3));
            z = Integer.parseInt(last);
        } catch (NumberFormatException e) {
            error(ex, 400, "Tile coordinates must be whole numbers.");
            return;
        }
        TileService.Result r = atlas.tiles().get(parts.get(1), zoom, x, z);
        if (r.status() != 200 || r.png() == null) {
            cors(ex.getResponseHeaders());
            boolean busy = r.status() == 503;
            ex.getResponseHeaders().set("Cache-Control", busy ? "no-store" : "public, max-age=30");
            if (busy) {
                ex.getResponseHeaders().set("Retry-After", "2");
            }
            ex.sendResponseHeaders(busy ? 503 : 404, -1);
            return;
        }
        Headers h = ex.getResponseHeaders();
        cors(h);
        h.set("Content-Type", "image/png");
        h.set("Cache-Control", r.partial() ? "no-store" : "public, max-age=60");
        if (r.partial()) {
            h.set("X-Atlas-Partial", "1");
        }
        send(ex, 200, r.png(), false);
    }

    /**
     * GET api/marker-icons/{Name}.png. Name is letters, digits and underscores, at most 40 long, and
     * anything else, a missing image or unknown assets give 404. Images are cached for a day and
     * carry an ETag, and a matching If-None-Match gets 304.
     */
    private void handleMarkerIcon(HttpExchange ex, List<String> parts, boolean headOnly) throws IOException {
        String file = parts.size() == 2 ? parts.get(1) : null;
        String name = file != null && file.endsWith(".png") ? file.substring(0, file.length() - 4) : null;
        MarkerIcons.Icon icon = MarkerIcons.validName(name) ? markerIcons.get(name) : null;
        Headers h = ex.getResponseHeaders();
        cors(h);
        if (icon == null) {
            h.set("Cache-Control", "public, max-age=300");
            error(ex, 404, "Not found.");
            return;
        }
        h.set("Cache-Control", "public, max-age=86400");
        h.set("ETag", icon.etag());
        h.set("X-Content-Type-Options", "nosniff");
        String match = ex.getRequestHeaders().getFirst("If-None-Match");
        if (match != null && etagMatches(match, icon.etag())) {
            ex.sendResponseHeaders(304, -1);
            return;
        }
        h.set("Content-Type", "image/png");
        send(ex, 200, icon.png(), headOnly);
    }

    /** True when an If-None-Match header names the tag, or is a star. */
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

    private void handleEvents(HttpExchange ex, Viewer viewer, boolean tokenAdmin, String world, String asTag) throws IOException {
        // The stream keeps the cookie and token it was opened with and resolves them again every
        // half minute, so a revoked session, a new faction or a changed permission applies live.
        String token = cookie(ex.getRequestHeaders(), COOKIE);
        SseHub.Identity identity = new SseHub.Identity() {
            @Override
            public Viewer resolve() {
                return atlas.viewer(atlas.links().session(token), tokenAdmin, asTag);
            }

            @Override
            public String key(Viewer v) {
                return v.cacheKey(v.uuid() != null && atlas.links().isHidden(v.uuid()));
            }

            @Override
            public String hello(Viewer v) {
                // Compared as text to spot identity changes, so it carries no clock values.
                JsonObject hello = new JsonObject();
                hello.add("viewer", atlas.viewerJson(v));
                JsonObject server = atlas.serverJson();
                server.remove("uptimeMs");
                server.remove("now");
                hello.add("server", server);
                return Json.GSON.toJson(hello);
            }

            @Override
            public String helloFrame(Viewer v) {
                JsonObject hello = new JsonObject();
                hello.add("viewer", atlas.viewerJson(v));
                hello.add("server", atlas.serverJson());
                return Json.GSON.toJson(hello);
            }
        };
        List<byte[]> initial = new ArrayList<>();
        initial.add(SseHub.frame("hello", identity.helloFrame(viewer)));
        JsonObject players = atlas.playersPayload(viewer, world, false);
        players.addProperty("full", true);
        initial.add(SseHub.frame("players", Json.GSON.toJson(players)));
        if (viewer.canSee(atlas.config(), "feed")) {
            initial.add(SseHub.frame("feedlog", Json.GSON.toJson(atlas.recentFeed())));
        }
        if (viewer.canSee(atlas.config(), "chat")) {
            initial.add(SseHub.frame("chatlog", Json.GSON.toJson(atlas.recentChat())));
        }
        if (viewer.canSee(atlas.config(), "kills")) {
            initial.add(SseHub.frame("killlog", Json.GSON.toJson(atlas.killLog(viewer, 50))));
        }
        String addressKey = LinkService.rateKey(clientAddress(ex));
        atlas.sse().serve(ex, viewer, world, identity, initial, addressKey, streamLimitFor(ex));
    }

    /**
     * The per address stream limit for this request. Without trustedProxies a reverse proxy makes every
     * browser share the proxy's address, so a loopback or private peer gets no per address limit then and
     * only the global limit applies. The first time that happens a warning suggests setting trustedProxies.
     */
    private int streamLimitFor(HttpExchange ex) {
        int limit = atlas.config().sseClientsPerIp;
        if (!atlas.proxies().isEmpty()) {
            return limit;
        }
        java.net.InetAddress peer = ex.getRemoteAddress() == null ? null : ex.getRemoteAddress().getAddress();
        if (!isLocalOrPrivate(peer)) {
            return limit;
        }
        if (proxyWarned.compareAndSet(false, true)) {
            logger.at(Level.WARNING).log("Atlas event streams from the local or private address %s are not limited per address "
                    + "because trustedProxies is empty. If Atlas runs behind a reverse proxy, add its address to trustedProxies "
                    + "so the per address limit and rate limits see real browser addresses.", peer.getHostAddress());
        }
        return 0;
    }

    /** Loopback, 10/8, 172.16/12, 192.168/16 and fc00::/7 addresses. */
    static boolean isLocalOrPrivate(java.net.InetAddress a) {
        if (a == null) {
            return false;
        }
        if (a.isLoopbackAddress() || a.isSiteLocalAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    /** Liveness. Only admins get stream counts, cache figures and uptime. */
    private JsonObject health(Viewer viewer) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("version", AtlasVersion.VERSION);
        if (!viewer.admin()) {
            return o;
        }
        o.addProperty("name", "Atlas");
        o.addProperty("uptimeMs", System.currentTimeMillis() - atlas.startedAt());
        o.addProperty("standalone", !atlas.snapshot().present());
        o.addProperty("sseClients", atlas.sse().size());
        JsonObject cache = new JsonObject();
        cache.addProperty("entries", atlas.tiles().cacheEntries());
        cache.addProperty("bytes", atlas.tiles().cacheBytes());
        cache.addProperty("maxBytes", atlas.tiles().cacheMaxBytes());
        cache.addProperty("queued", atlas.tiles().queuedTiles());
        o.add("tileCache", cache);
        return o;
    }

    // ---- responses ---------------------------------------------------------

    private static void cors(Headers h) {
        h.set("Access-Control-Allow-Origin", "*");
    }

    private static void json(HttpExchange ex, int status, JsonElement body) throws IOException {
        Headers h = ex.getResponseHeaders();
        cors(h);
        h.set("Content-Type", "application/json; charset=utf-8");
        if (!h.containsKey("Cache-Control")) {
            h.set("Cache-Control", "no-store");
        }
        byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        String accept = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (bytes.length > 1024 && accept != null && accept.contains("gzip")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(bytes.length / 4);
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(bytes);
            }
            bytes = bos.toByteArray();
            h.set("Content-Encoding", "gzip");
            h.set("Vary", "Accept-Encoding");
        }
        send(ex, status, bytes, false);
    }

    private static void error(HttpExchange ex, int status, String message) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        json(ex, status, o);
    }

    private static void send(HttpExchange ex, int status, byte[] body, boolean headOnly) throws IOException {
        if (headOnly) {
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

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
