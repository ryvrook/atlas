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
    /** The cookie name of Atlas 1.0.0 and older. Still read in 1.1.0 and moved to cookieName on the next answer. */
    public static final String LEGACY_COOKIE = AtlasConfig.LEGACY_COOKIE;
    /** Ordinary requests handled at the same time. Event streams do not count. */
    static final int MAX_IN_FLIGHT = 64;
    /** Seconds a client may take to send a whole request. */
    static final int REQUEST_SECONDS = 30;
    private static final String STATIC_CACHE = "public, max-age=86400";
    /** For a file asked for with the ?v= tag index.html gives it, which changes with the file's bytes. */
    private static final String VERSIONED_CACHE = "public, max-age=31536000, immutable";
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
    private volatile byte[] indexGzip;
    private volatile String indexEtag;
    private volatile String indexCsp = CSP;
    private final java.util.concurrent.atomic.AtomicBoolean proxyWarned = new java.util.concurrent.atomic.AtomicBoolean();
    /** Static files read from the jar, which never change while the server runs. Only files that exist are kept. */
    private final Map<String, StaticFile> staticFiles = new java.util.concurrent.ConcurrentHashMap<>();
    private final MarkerIcons markerIcons = new MarkerIcons();
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);
    private final RateLimiter apiLimiter = new RateLimiter();
    /** Random id sent with health answers, so a probe of the port can tell this server from another program. */
    private final String instanceId = Long.toHexString(new java.security.SecureRandom().nextLong());
    private final RateLimiter tileLimiter = new RateLimiter();
    /** Extra health figures from the plugin, such as failing tasks and disk writes. */
    private volatile java.util.function.Consumer<JsonObject> healthExtras = o -> { };
    /** The timeline routes, or null before the plugin hands them over. */
    private volatile dev.ryvrook.atlas.timeline.TimelineRoutes timeline;
    /** Bind retry state, see startWithRetry. Guarded by this. */
    private java.util.concurrent.ScheduledFuture<?> retryTask;
    private int retryAttempts;
    private long retryStartedAt;
    private long retryNextAt;
    private String retryError;
    private boolean retryGaveUp;

    public ApiServer(Atlas atlas, com.hypixel.hytale.logger.HytaleLogger logger) {
        this.atlas = atlas;
        this.logger = logger;
        atlas.sse().setWorldGate((v, w) -> !worldHidden(v, w));
    }

    /** Hands over the timeline routes under api/timeline. */
    public void setTimeline(dev.ryvrook.atlas.timeline.TimelineRoutes routes) {
        timeline = routes;
    }

    /** Lets the plugin add its own figures, such as failing tasks and disk writes, to the admin health answer. */
    public void setHealthExtras(java.util.function.Consumer<JsonObject> extras) {
        healthExtras = extras == null ? o -> { } : extras;
    }

    /** Binds the configured address and starts serving. Throws when the address cannot be bound. */
    public synchronized void start() throws IOException {
        AtlasConfig cfg = atlas.config();
        setIndex(loadIndex(cfg.publicTitle));
        precompressStatic();
        bound = bind(cfg);
    }

    /** Seconds between bind attempts while the port is taken. */
    static final long RETRY_EVERY_MS = 30_000;
    /** How long bind attempts go on before Atlas gives up until the next reload. */
    static final long RETRY_FOR_MS = 10 * 60_000L;

    /**
     * Starts serving, and when the port is taken keeps trying every 30 seconds for 10 minutes on
     * the given scheduler. The first failure logs one WARNING and a later success one INFO line.
     * Returns null when the server is up, otherwise the reason of the first failure. Any other
     * failure, such as an address that does not exist on this machine, is returned without retries.
     */
    public synchronized String startWithRetry(java.util.concurrent.ScheduledExecutorService scheduler) {
        cancelRetry();
        try {
            start();
            announce(1);
            return null;
        } catch (IOException | RuntimeException e) {
            AtlasConfig cfg = atlas.config();
            String why = String.valueOf(e.getMessage());
            if (!(e instanceof java.net.BindException) || scheduler == null) {
                logger.at(Level.SEVERE).withCause(e).log("Atlas could not start its web server on %s:%d", cfg.bind, cfg.port);
                return why;
            }
            String occupant = describeOccupant(cfg);
            logger.at(Level.WARNING).log("Atlas could not bind its web server to %s:%d because the port is in use%s. "
                            + "Atlas tries again every %d seconds for %d minutes.", cfg.bind, cfg.port, occupant,
                    RETRY_EVERY_MS / 1000, RETRY_FOR_MS / 60_000);
            retryAttempts = 1;
            retryStartedAt = System.currentTimeMillis();
            retryNextAt = retryStartedAt + RETRY_EVERY_MS;
            retryError = why;
            retryGaveUp = false;
            retryTask = scheduler.scheduleWithFixedDelay(this::retryBind, RETRY_EVERY_MS, RETRY_EVERY_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            return "The port " + cfg.port + " is in use. Atlas tries again every 30 seconds for 10 minutes.";
        }
    }

    private synchronized void retryBind() {
        if (retryTask == null || bound != null) {
            cancelRetry();
            return;
        }
        AtlasConfig cfg = atlas.config();
        retryAttempts++;
        try {
            start();
            announce(retryAttempts);
            cancelRetry();
            retryError = null;
        } catch (IOException | RuntimeException e) {
            retryError = String.valueOf(e.getMessage());
            long now = System.currentTimeMillis();
            retryNextAt = now + RETRY_EVERY_MS;
            if (now - retryStartedAt >= RETRY_FOR_MS) {
                logger.at(Level.SEVERE).log("Atlas gave up binding its web server to %s:%d after %d attempts. "
                        + "Free the port or change it in atlas.json, then run /atlas reload.", cfg.bind, cfg.port, retryAttempts);
                retryGaveUp = true;
                if (retryTask != null) {
                    retryTask.cancel(false);
                    retryTask = null;
                }
            }
        }
    }

    /**
     * Logs where the map listens, then checks off the request path whether the port answers as
     * this server or looks shared with another program, and names other map mods when it does.
     */
    private void announce(int attempts) {
        String address = boundAddress();
        int port = atlas.config().port;
        Thread.ofVirtual().name("Atlas-port-check").start(() -> {
            String shared = probeOwnPort();
            String others = otherMapMods.get();
            if (attempts > 1) {
                logger.at(Level.INFO).log("Atlas web map listening on http://%s/ on port %d after %d bind attempts",
                        address, port, attempts);
            } else {
                logger.at(Level.INFO).log("Atlas web map listening on http://%s/ on port %d%s", address, port,
                        shared == null ? ", and the port answers as Atlas" : "");
            }
            if (shared != null) {
                logger.at(Level.WARNING).log("Atlas port %d looks shared, %s.%s Requests may reach the other program "
                        + "instead of Atlas. Give Atlas its own port in atlas.json.", port, shared,
                        others == null || others.isEmpty() ? "" : " Other map mods found in the mods folder are " + others + ".");
            }
        });
    }

    /** Names of other map mods in the mods folder, joined, or empty. Set by the plugin. */
    private volatile java.util.function.Supplier<String> otherMapMods = () -> "";

    public void setOtherMapMods(java.util.function.Supplier<String> names) {
        otherMapMods = names == null ? () -> "" : names;
    }

    /** Stops pending bind attempts and forgets their state. */
    public synchronized void cancelRetry() {
        if (retryTask != null) {
            retryTask.cancel(false);
            retryTask = null;
        }
        retryAttempts = 0;
        retryGaveUp = false;
        retryNextAt = 0;
    }

    /** Bind retry state for the health answer, or null when no retry is pending or failed. */
    private synchronized JsonObject bindState() {
        if (retryTask == null && !retryGaveUp) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("state", retryGaveUp ? "failed" : "retrying");
        o.addProperty("attempts", retryAttempts);
        o.addProperty("startedAt", retryStartedAt);
        if (retryGaveUp) {
            o.add("nextAttemptAt", com.google.gson.JsonNull.INSTANCE);
        } else {
            o.addProperty("nextAttemptAt", retryNextAt);
        }
        o.addProperty("error", retryError);
        return o;
    }

    /**
     * Asks the bound port over loopback, or over the bound address when it is a specific one, who
     * answers there. Returns null when this server answered, otherwise a few words on what did. On
     * some systems a second program can listen on the same port on a more specific address, and the
     * port then looks shared, with local requests reaching the other program.
     */
    public String probeOwnPort() {
        AtlasConfig cfg = atlas.config();
        String host = "0.0.0.0".equals(cfg.bind) || "::".equals(cfg.bind) || cfg.bind.isBlank() ? "127.0.0.1" : cfg.bind;
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.connect(new InetSocketAddress(host, cfg.port), 1000);
            sock.setSoTimeout(2000);
            OutputStream out = sock.getOutputStream();
            out.write(("GET " + cfg.basePath + "/api/health HTTP/1.0\r\nHost: " + host + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String text = new String(sock.getInputStream().readNBytes(4096), StandardCharsets.ISO_8859_1)
                    .toLowerCase(java.util.Locale.ROOT);
            if (text.contains("x-atlas-instance: " + instanceId.toLowerCase(java.util.Locale.ROOT))) {
                return null;
            }
            return text.contains("map") || text.contains("leaflet")
                    ? "another web map answers on " + host + ":" + cfg.port
                    : "another program answers on " + host + ":" + cfg.port;
        } catch (IOException | RuntimeException e) {
            return "nothing answered on " + host + ":" + cfg.port + " (" + e.getMessage() + ")";
        }
    }

    /**
     * A few words on what holds the port, found by asking it over loopback with a short timeout.
     * Says when it answered like a web map, which usually means another map mod uses the same port.
     */
    private static String describeOccupant(AtlasConfig cfg) {
        String host = "0.0.0.0".equals(cfg.bind) || "::".equals(cfg.bind) || cfg.bind.isBlank() ? "127.0.0.1" : cfg.bind;
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.connect(new InetSocketAddress(host, cfg.port), 1000);
            sock.setSoTimeout(1500);
            OutputStream out = sock.getOutputStream();
            out.write(("GET / HTTP/1.0\r\nHost: " + host + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] head = sock.getInputStream().readNBytes(4096);
            String text = new String(head, StandardCharsets.ISO_8859_1).toLowerCase(java.util.Locale.ROOT);
            if (!text.startsWith("http/")) {
                return ", by something that is not a web server";
            }
            if (text.contains("map") || text.contains("leaflet")) {
                return ", by a web server that looks like another map mod";
            }
            return ", by another web server";
        } catch (IOException | RuntimeException e) {
            return "";
        }
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
        cancelRetry();
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
            html = versionAssets(html, this::assetTag);
            return html.replace("{{TITLE}}", escapeHtml(title)).getBytes(StandardCharsets.UTF_8);
        }
    }

    private void setIndex(byte[] html) {
        indexGzip = gzip(html);
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
        return versionAssets(html, rel -> version);
    }

    /**
     * Same with a tag per file, from {@link #assetTag}. A file the tag function does not know gets the
     * running version.
     */
    static String versionAssets(String html, java.util.function.Function<String, String> tagOf) {
        return ASSET_REF.matcher(html).replaceAll(m -> {
            String tag = tagOf.apply(m.group(2));
            String v = java.net.URLEncoder.encode(tag == null ? AtlasVersion.VERSION : tag, StandardCharsets.UTF_8);
            return java.util.regex.Matcher.quoteReplacement(m.group(1) + m.group(2) + "?v=" + v + m.group(3));
        });
    }

    /**
     * The ?v= tag of a file in the jar, the running version and the start of the file's hash, or null when
     * the jar has no such file. The hash part changes with the bytes, so a rebuilt jar that keeps its
     * version still gets new addresses, and a year long immutable cache never holds an old file.
     */
    String assetTag(String rel) {
        String clean = safeRelative(rel);
        StaticFile f = clean == null ? null : staticFile(clean);
        if (f == null) {
            return null;
        }
        String hash = f.etag().replace("\"", "");
        return AtlasVersion.VERSION + "-" + hash.substring(0, Math.min(10, hash.length()));
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
        sendMaybeGzip(ex, 200, indexHtml, indexGzip);
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

    /** A static file with its gzip form when that is smaller, and its ETag. */
    record StaticFile(byte[] raw, byte[] gzip, String etag) {
    }

    /** Extensions whose files are sent gzipped to browsers that accept it. Images are compressed already. */
    private static final java.util.Set<String> COMPRESSIBLE = java.util.Set.of("html", "js", "mjs", "css", "json", "map",
            "svg", "txt", "webmanifest", "ico");

    /**
     * Reads a file below web in the jar, or returns null. The relative path must be plain segments,
     * so nothing outside the web folder is ever read. Files found are kept with their gzip form.
     */
    private StaticFile staticFile(String rel) {
        StaticFile cached = staticFiles.get(rel);
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
        if (data == null) {
            return null;
        }
        StaticFile f = staticEntry(rel, data);
        staticFiles.put(rel, f);
        return f;
    }

    private static StaticFile staticEntry(String rel, byte[] data) {
        String ext = rel.substring(rel.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        byte[] gz = COMPRESSIBLE.contains(ext) && data.length > 256 ? gzip(data) : null;
        return new StaticFile(data, gz, MarkerIcons.etagOf(data));
    }

    /**
     * Reads and compresses every file below web in the plugin jar once, at start, so no request
     * pays for compression. Files that cannot be listed are still read on first request.
     */
    private void precompressStatic() {
        if (!staticFiles.isEmpty()) {
            return;
        }
        try {
            java.net.URL root = ApiServer.class.getResource("/web/index.html");
            if (root == null) {
                return;
            }
            java.net.URI uri = root.toURI();
            if ("jar".equals(uri.getScheme())) {
                String spec = uri.getSchemeSpecificPart();
                String jarPath = spec.substring(0, spec.indexOf("!/"));
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(new java.io.File(new java.net.URI(jarPath)))) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        java.util.zip.ZipEntry e = entries.nextElement();
                        String name = e.getName();
                        if (e.isDirectory() || !name.startsWith("web/") || e.getSize() > 8 * 1024 * 1024) {
                            continue;
                        }
                        String rel = safeRelative(name.substring(4));
                        if (rel != null && !rel.equals("index.html")) {
                            try (InputStream in = zip.getInputStream(e)) {
                                staticFiles.put(rel, staticEntry(rel, in.readAllBytes()));
                            }
                        }
                    }
                }
            }
        } catch (Exception | LinkageError e) {
            logger.at(Level.FINE).withCause(e).log("Atlas could not read its web files ahead of time");
        }
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
        long wait = rateWait(ex);
        if (wait > 0) {
            try {
                ex.getResponseHeaders().set("Retry-After", Long.toString(wait));
                error(ex, 429, "Too many requests. Try again in a moment.");
            } catch (IOException ignored) {
                // The client went away.
            } finally {
                ex.close();
            }
            return;
        }
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

    /**
     * Takes one request from the client's API or tile budget and returns the seconds to wait, or 0
     * when the request may go ahead. Tiles, marker icons and timeline exports use tileRequestsPerMinute, every other
     * api request apiRequestsPerMinute. The page and its files are not limited. Without
     * trustedProxies, local and private peers are not limited per address, since behind an
     * unlisted reverse proxy every browser would share the proxy's address.
     */
    private long rateWait(HttpExchange ex) {
        String path = stripBase(ex.getRequestURI().getRawPath(), atlas.config().basePath);
        if (!path.startsWith("/api/") || "OPTIONS".equals(ex.getRequestMethod())) {
            return 0;
        }
        boolean tile = path.startsWith("/api/tiles/") || path.startsWith("/api/marker-icons/")
                || (path.startsWith("/api/timeline/") && (path.contains("/tiles/") || path.endsWith("/export")));
        AtlasConfig cfg = atlas.config();
        int limit = tile ? cfg.tileRequestsPerMinute() : cfg.apiRequestsPerMinute();
        if (limit <= 0) {
            return 0;
        }
        if (atlas.proxies().isEmpty()) {
            java.net.InetAddress peer = ex.getRemoteAddress() == null ? null : ex.getRemoteAddress().getAddress();
            if (isLocalOrPrivate(peer)) {
                return 0;
            }
        }
        String key = LinkService.rateKey(clientAddress(ex));
        return (tile ? tileLimiter : apiLimiter).acquire(key, limit);
    }

    /** Drops idle rate limit buckets. Called from the plugin's prune task. */
    public void pruneRateLimits() {
        apiLimiter.prune();
        tileLimiter.prune();
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
            if ("admin".equals(head)) {
                handleAdmin(ex, method, parts);
                return;
            }
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                ex.getResponseHeaders().set("Allow", "GET, HEAD");
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
                ex.getResponseHeaders().set("Retry-After", "60");
                error(ex, 429, "Too many attempts. Wait a minute and try again.");
                return;
            }
            boolean tokenAdmin = tokenAdmin(givenToken(ex.getRequestHeaders()));
            if (tokenGiven && !tokenAdmin) {
                // A wrong admin token counts against the same per address limit as link codes.
                atlas.links().rateLimited(address);
            }
            Viewer viewer = atlas.viewer(session(ex), tokenAdmin, query.get("as"));
            String namedWorld = parts.size() >= 2 && WORLD_IN_PATH.contains(head) ? parts.get(1)
                    : WORLD_IN_QUERY.contains(head) ? query.get("world") : null;
            if (worldHidden(viewer, namedWorld)) {
                // Portal and instance worlds stay out of reach for non admins while showPortalWorlds is false.
                error(ex, 404, "Not found.");
                return;
            }
            Atlas.ApiResult routed = atlas.route(viewer, parts, query);
            if (routed != null) {
                JsonObject body = routed.body();
                if (routed.status() == 200) {
                    switch (head) {
                        case "players" -> {
                            dropHiddenWorldRows(viewer, body, "players");
                            dropHiddenWorldRows(viewer, body, "offline");
                        }
                        case "worldevents" -> dropHiddenWorldRows(viewer, body, "events");
                        case "kills" -> dropHiddenWorldRows(viewer, body, "kills");
                        default -> {
                        }
                    }
                }
                json(ex, routed.status(), body);
                return;
            }
            Atlas.ApiResult marker = atlas.markerRoutes().handle(method, String.join("/", parts), viewer, query);
            if (marker != null) {
                json(ex, marker.status(), marker.body());
                return;
            }
            if (dev.ryvrook.atlas.iso.IsoRoutes.serve(ex, viewer, parts, query, clientAddress(ex))) {
                return;
            }
            dev.ryvrook.atlas.timeline.TimelineRoutes tl = timeline;
            if ("timeline".equals(head) && tl != null) {
                if (dev.ryvrook.atlas.timeline.TimelineRoutes.isTile(parts)) {
                    handleTimelineTile(ex, tl, viewer, parts);
                } else {
                    Atlas.ApiResult r = tl.handle(viewer, parts, query);
                    if (r == null) {
                        error(ex, 404, "Not found.");
                    } else {
                        json(ex, r.status(), r.body());
                    }
                }
                return;
            }
            switch (head) {
                case "health" -> {
                    ex.getResponseHeaders().set("X-Atlas-Instance", instanceId);
                    json(ex, 200, health(viewer));
                }
                case "tiles" -> handleTile(ex, viewer, parts);
                case "events" -> {
                    if ("HEAD".equals(method)) {
                        cors(ex.getResponseHeaders());
                        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                        ex.getResponseHeaders().set("Cache-Control", "no-cache");
                        ex.sendResponseHeaders(200, -1);
                    } else {
                        handleEvents(ex, viewer, givenToken(ex.getRequestHeaders()), query.get("world"), query.get("as"));
                    }
                }
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

    /** True when the query is exactly v= the file's tag, the address versionAssets writes. */
    static boolean versioned(String rawQuery, String tag) {
        if (rawQuery == null || tag == null) {
            return false;
        }
        return rawQuery.equals("v=" + java.net.URLEncoder.encode(tag, StandardCharsets.UTF_8));
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
        StaticFile data = rel == null ? null : staticFile(rel);
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
        h.set("Cache-Control", versioned(ex.getRequestURI().getRawQuery(), assetTag(rel)) ? VERSIONED_CACHE : STATIC_CACHE);
        h.set("ETag", data.etag());
        securityHeaders(h, CSP);
        if (data.gzip() != null) {
            h.set("Vary", "Accept-Encoding");
        }
        String match = ex.getRequestHeaders().getFirst("If-None-Match");
        if (match != null && etagMatches(match, data.etag())) {
            ex.sendResponseHeaders(304, -1);
            return;
        }
        sendMaybeGzip(ex, 200, data.raw(), data.gzip());
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
     * clears cookies set by any earlier version. With cookieDomain set the cookie carries that Domain, so
     * Atlas and Chronicle on sibling host names share it.
     */
    private String cookieAttributes(HttpExchange ex) {
        return cookieAttributes(overHttps(ex), atlas.config().cookieDomain);
    }

    static String cookieAttributes(boolean secure, String domain) {
        return "; Path=/; HttpOnly; SameSite=Lax" + (domain == null || domain.isBlank() ? "" : "; Domain=" + domain.trim())
                + (secure ? "; Secure" : "");
    }

    /**
     * The session token a request carries. legacy is true when it came in the 1.0.0 cookie because the
     * current one is missing, and legacyPresent when the 1.0.0 cookie was sent at all.
     */
    record SessionCookie(String token, boolean legacy, boolean legacyPresent) {
    }

    /** Reads the session cookie under the configured name, falling back to the cookie of 1.0.0. */
    static SessionCookie readSession(Headers h, String name) {
        String current = cookie(h, name);
        String old = LEGACY_COOKIE.equals(name) ? null : cookie(h, LEGACY_COOKIE);
        boolean oldPresent = old != null;
        if (current != null && !current.isEmpty()) {
            return new SessionCookie(current, false, oldPresent);
        }
        if (old != null && !old.isEmpty()) {
            return new SessionCookie(old, true, true);
        }
        return new SessionCookie(null, false, oldPresent);
    }

    /**
     * The Set-Cookie lines that move a 1.0.0 cookie over. A valid session found only under the old name is
     * set again under the current name, and the old cookie is cleared whenever it was sent. The old cookie
     * never carried a Domain, so it is cleared without one.
     */
    static List<String> migrateCookie(SessionCookie read, boolean valid, String name, boolean secure, String domain) {
        List<String> out = new ArrayList<>();
        if (read == null || !read.legacyPresent()) {
            return out;
        }
        if (read.legacy() && valid) {
            out.add(name + "=" + read.token() + "; Max-Age=" + LinkService.SESSION_TTL_MS / 1000 + cookieAttributes(secure, domain));
        }
        out.add(LEGACY_COOKIE + "=; Max-Age=0" + cookieAttributes(secure, null));
        return out;
    }

    /**
     * The session of the request, or null. Moves a 1.0.0 cookie to the current name on this answer. Every
     * route that resolves the viewer goes through here once, before its headers are sent.
     */
    private LinkService.Session session(HttpExchange ex) {
        AtlasConfig cfg = atlas.config();
        SessionCookie read = readSession(ex.getRequestHeaders(), cfg.cookieName);
        LinkService.Session s = atlas.links().session(read.token());
        for (String line : migrateCookie(read, s != null, cfg.cookieName, overHttps(ex), cfg.cookieDomain)) {
            ex.getResponseHeaders().add("Set-Cookie", line);
        }
        return s;
    }

    /** Endpoints whose second path part names a world. */
    private static final java.util.Set<String> WORLD_IN_PATH = java.util.Set.of("tiles", "markers", "territories", "points", "changes", "heat",
            "timeline");
    /** Endpoints that take a world query parameter. */
    private static final java.util.Set<String> WORLD_IN_QUERY = java.util.Set.of("players", "worldevents", "events");

    /**
     * True when the world is one this viewer may not see, by the one rule in Atlas.hiddenWorld (a world
     * disabled in atlas.json, a hidden world, or a portal or instance world while showPortalWorlds is
     * off). The world picker hides the same worlds.
     */
    private boolean worldHidden(Viewer v, String world) {
        return atlas.hiddenWorld(v, world);
    }

    /** Removes rows of hidden worlds from a list in the payload. The viewer's own row stays. */
    private void dropHiddenWorldRows(Viewer v, JsonObject payload, String key) {
        if (payload == null || !payload.has(key) || !payload.get(key).isJsonArray()) {
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

    /** True when the given token equals the adminToken of the current settings, compared in constant time. */
    private boolean tokenAdmin(String given) {
        String expected = atlas.config().adminToken;
        if (expected == null || expected.isBlank()) {
            return false;
        }
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

    /**
     * api/admin, the routes that change server settings from the Admin tab. Admins only, by the admin
     * token or a linked session with the admin permission. The as parameter is ignored here. A
     * change sent with a session cookie must come from a page of this map, so a request whose Origin
     * names another site is refused. The body is JSON of at most 4 KB.
     */
    private void handleAdmin(HttpExchange ex, String method, List<String> parts) throws IOException {
        String address = clientAddress(ex);
        String given = givenToken(ex.getRequestHeaders());
        if (given != null && atlas.links().blocked(address)) {
            ex.getResponseHeaders().set("Retry-After", "60");
            error(ex, 429, "Too many attempts. Wait a minute and try again.");
            return;
        }
        boolean tokenAdmin = tokenAdmin(given);
        if (given != null && !tokenAdmin) {
            atlas.links().rateLimited(address);
        }
        boolean write = !"GET".equals(method) && !"HEAD".equals(method);
        if (write && !tokenAdmin && !sameOrigin(ex)) {
            error(ex, 403, "Changes are only accepted from the map itself.");
            return;
        }
        String body = null;
        if ("POST".equals(method)) {
            String type = ex.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                error(ex, 415, "Send the change as JSON.");
                return;
            }
            body = new String(ex.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8);
        }
        Viewer viewer = atlas.viewer(session(ex), tokenAdmin, null);
        Atlas.ApiResult r = dev.ryvrook.atlas.iso.KeyPlaceRoutes.handle(atlas, method, parts, body, viewer);
        if (r == null) {
            error(ex, 404, "Not found.");
            return;
        }
        if (r.status() == 405) {
            ex.getResponseHeaders().set("Allow", "GET, HEAD, POST, DELETE");
        }
        json(ex, r.status(), r.body());
    }

    /**
     * True when the request names no Origin, or one whose host and port are the Host the request was
     * sent to (or the forwarded host behind a trusted proxy).
     */
    private boolean sameOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            return true;
        }
        String authority;
        try {
            authority = java.net.URI.create(origin.trim()).getRawAuthority();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (authority == null) {
            return false;
        }
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host != null && host.trim().equalsIgnoreCase(authority)) {
            return true;
        }
        String peer = ex.getRemoteAddress() == null || ex.getRemoteAddress().getAddress() == null
                ? null : ex.getRemoteAddress().getAddress().getHostAddress();
        String forwarded = ex.getRequestHeaders().getFirst("X-Forwarded-Host");
        return forwarded != null && peer != null && atlas.proxies().trusts(peer)
                && forwarded.split(",")[0].trim().equalsIgnoreCase(authority);
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
        String cookieName = atlas.config().cookieName;
        if ("logout".equals(action)) {
            atlas.links().revoke(cookie(ex.getRequestHeaders(), cookieName));
            ex.getResponseHeaders().add("Set-Cookie", cookieName + "=; Max-Age=0" + cookieAttributes(ex));
            if (!LEGACY_COOKIE.equals(cookieName)) {
                // The cookie of 1.0.0 may still be there. It ends the same session or an older one.
                atlas.links().revoke(cookie(ex.getRequestHeaders(), LEGACY_COOKIE));
                ex.getResponseHeaders().add("Set-Cookie", LEGACY_COOKIE + "=; Max-Age=0" + cookieAttributes(overHttps(ex), null));
            }
            String base = atlas.config().basePath;
            if (!base.isEmpty()) {
                // Earlier versions scoped the cookie to the basePath. Clear that one as well.
                ex.getResponseHeaders().add("Set-Cookie", LEGACY_COOKIE + "=; Max-Age=0; Path=" + base + "/; HttpOnly; SameSite=Lax");
            }
            JsonObject ok = new JsonObject();
            ok.addProperty("ok", true);
            json(ex, 200, ok);
            return;
        }
        if (atlas.links().rateLimited(address)) {
            ex.getResponseHeaders().set("Retry-After", "60");
            error(ex, 429, "Too many attempts. Wait a minute and try again.");
            return;
        }
        LinkService.Linked linked = atlas.links().redeem(code);
        if (linked == null) {
            error(ex, 400, "That code is not valid. Type /atlas link in game for a new one.");
            return;
        }
        long maxAge = LinkService.SESSION_TTL_MS / 1000;
        ex.getResponseHeaders().add("Set-Cookie", cookieName + "=" + linked.token() + "; Max-Age=" + maxAge + cookieAttributes(ex));
        if (!LEGACY_COOKIE.equals(cookieName) && cookie(ex.getRequestHeaders(), LEGACY_COOKIE) != null) {
            ex.getResponseHeaders().add("Set-Cookie", LEGACY_COOKIE + "=; Max-Age=0" + cookieAttributes(overHttps(ex), null));
        }
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
        Headers h = ex.getResponseHeaders();
        cors(h);
        h.set("X-Content-Type-Options", "nosniff");
        if (r.status() != 200 || r.png() == null) {
            boolean busy = r.status() == 503;
            h.set("Cache-Control", busy ? "no-store" : "public, max-age=30");
            if (busy) {
                h.set("Retry-After", "2");
            }
            ex.sendResponseHeaders(busy ? 503 : 404, -1);
            return;
        }
        String etag = tileEtag(r);
        h.set("Cache-Control", r.partial() ? "no-store" : "public, max-age=60");
        if (r.partial()) {
            h.set("X-Atlas-Partial", "1");
        }
        if (etag != null) {
            h.set("ETag", etag);
            String match = ex.getRequestHeaders().getFirst("If-None-Match");
            if (match != null && etagMatches(match, etag)) {
                ex.sendResponseHeaders(304, -1);
                return;
            }
        }
        h.set("Content-Type", "image/png");
        send(ex, 200, r.png(), isHead(ex));
    }

    /**
     * GET api/timeline/{world}/{date}/tiles/{zoom}/{x}/{z}.png, a tile as it was on that day. Past
     * days never change and are cached for a year, today's may be taken again and are cached for
     * five minutes. A tile that did not exist that day answers 404 without a body, like live tiles.
     */
    private void handleTimelineTile(HttpExchange ex, dev.ryvrook.atlas.timeline.TimelineRoutes tl, Viewer viewer,
                                    List<String> parts) throws IOException {
        Object answer = tl.tile(viewer, parts);
        if (answer instanceof Atlas.ApiResult r) {
            json(ex, r.status(), r.body());
            return;
        }
        dev.ryvrook.atlas.timeline.TimelineRoutes.Tile t = (dev.ryvrook.atlas.timeline.TimelineRoutes.Tile) answer;
        Headers h = ex.getResponseHeaders();
        cors(h);
        h.set("X-Content-Type-Options", "nosniff");
        if (t.status() != 200 || t.png() == null) {
            h.set("Cache-Control", t.past() ? "public, max-age=86400" : "public, max-age=60");
            ex.sendResponseHeaders(404, -1);
            return;
        }
        h.set("Cache-Control", t.past() ? "public, max-age=31536000, immutable" : "public, max-age=300");
        h.set("ETag", t.etag());
        String match = ex.getRequestHeaders().getFirst("If-None-Match");
        if (match != null && etagMatches(match, t.etag())) {
            ex.sendResponseHeaders(304, -1);
            return;
        }
        h.set("Content-Type", "image/png");
        send(ex, 200, t.png(), isHead(ex));
    }

    /** The strong ETag of a tile, as the tile service made it from the PNG bytes, or one made here. */
    private static String tileEtag(TileService.Result r) {
        return r.etag() != null ? r.etag() : MarkerIcons.etagOf(r.png());
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

    private void handleEvents(HttpExchange ex, Viewer viewer, String givenToken, String world, String asTag) throws IOException {
        // The stream keeps the cookie and admin token it was opened with and checks both again every
        // half minute against the current sessions and settings, so a revoked session, a changed
        // adminToken, a new faction or a changed permission applies live.
        // dispatch already moved a 1.0.0 cookie over on this answer, so this only reads.
        String token = readSession(ex.getRequestHeaders(), atlas.config().cookieName).token();
        SseHub.Identity identity = new SseHub.Identity() {
            @Override
            public Viewer resolve() {
                return atlas.viewer(atlas.links().session(token), tokenAdmin(givenToken), asTag);
            }

            @Override
            public String key(Viewer v) {
                return v.cacheKey(v.uuid() != null && atlas.links().isHidden(v.uuid()));
            }

            @Override
            public String hello(Viewer v) {
                // Compared as text to spot identity changes, so it carries no clock values.
                return Json.GSON.toJson(atlas.helloIdentity(v));
            }

            @Override
            public String helloFrame(Viewer v) {
                return Json.GSON.toJson(atlas.helloPayload(v));
            }
        };
        java.util.function.Supplier<List<byte[]>> initial = () -> {
            List<byte[]> frames = new ArrayList<>();
            frames.add(SseHub.frame("hello", identity.helloFrame(viewer)));
            JsonObject players = atlas.playersPayload(viewer, world, false);
            players.addProperty("full", true);
            frames.add(SseHub.frame("players", Json.GSON.toJson(players)));
            if (viewer.canSee(atlas.config(), "feed")) {
                frames.add(SseHub.frame("feedlog", Json.GSON.toJson(logPayload(viewer, atlas.recentFeed()))));
            }
            if (viewer.canSee(atlas.config(), "chat")) {
                frames.add(SseHub.frame("chatlog", Json.GSON.toJson(logPayload(viewer, atlas.recentChat()))));
            }
            if (viewer.canSee(atlas.config(), "kills")) {
                frames.add(SseHub.frame("killlog", Json.GSON.toJson(logPayload(viewer, atlas.killLog(viewer, 50)))));
            }
            return frames;
        };
        String addressKey = LinkService.rateKey(clientAddress(ex));
        atlas.sse().serve(ex, viewer, world, identity, initial, addressKey, streamLimitFor(ex));
    }

    /**
     * A log event body, an object with the rows under items. Rows that name a world this viewer may
     * not see are left out. Accepts the rows as a bare array or already wrapped that way.
     */
    private JsonElement logPayload(Viewer v, JsonElement rows) {
        com.google.gson.JsonArray list = rows != null && rows.isJsonArray() ? rows.getAsJsonArray()
                : rows != null && rows.isJsonObject() && rows.getAsJsonObject().has("items")
                && rows.getAsJsonObject().get("items").isJsonArray()
                ? rows.getAsJsonObject().getAsJsonArray("items") : null;
        if (list == null) {
            return rows;
        }
        com.google.gson.JsonArray kept = new com.google.gson.JsonArray();
        for (JsonElement e : list) {
            if (e.isJsonObject() && worldHidden(v, Json.str(e.getAsJsonObject(), "world"))) {
                continue;
            }
            kept.add(e);
        }
        JsonObject out = rows.isJsonObject() ? rows.getAsJsonObject().deepCopy() : new JsonObject();
        out.add("items", kept);
        return out;
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

    /** Loopback, 10/8, 172.16/12, 192.168/16, 169.254/16, fe80::/10 and fc00::/7 addresses. */
    static boolean isLocalOrPrivate(java.net.InetAddress a) {
        if (a == null) {
            return false;
        }
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    /** A world whose info was not refreshed for this long counts as stalled. The refresh runs every 2 seconds. */
    static final long WORLD_STALL_MS = 30_000;

    /**
     * Liveness. Only admins get stream counts, cache figures, uptime and the degraded list, which
     * names each problem found: a render backlog, worlds whose info refresh stalled, failing tasks
     * or disk writes, and a bind retry. status is ok when the list is empty, else degraded.
     */
    private JsonObject health(Viewer viewer) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("version", AtlasVersion.VERSION);
        if (!viewer.admin()) {
            return o;
        }
        o.addProperty("name", "Atlas");
        o.addProperty("uptimeSeconds", (System.currentTimeMillis() - atlas.startedAt()) / 1000);
        o.addProperty("standalone", !atlas.snapshot().present());
        o.addProperty("sseClients", atlas.sse().size());
        JsonObject cache = new JsonObject();
        cache.addProperty("entries", atlas.tiles().cacheEntries());
        cache.addProperty("bytes", atlas.tiles().cacheBytes());
        cache.addProperty("maxBytes", atlas.tiles().cacheMaxBytes());
        cache.addProperty("queued", atlas.tiles().queuedTiles());
        // Tile writes that failed since start, and when the last one failed.
        cache.addProperty("diskWriteFailures", atlas.tiles().diskWriteFailures());
        long lastTileFailure = atlas.tiles().lastDiskFailureAt();
        if (lastTileFailure > 0) {
            cache.addProperty("lastDiskWriteFailureAt", lastTileFailure);
        } else {
            cache.add("lastDiskWriteFailureAt", com.google.gson.JsonNull.INSTANCE);
        }
        o.add("tileCache", cache);
        com.google.gson.JsonArray degraded = new com.google.gson.JsonArray();
        if (lastTileFailure > 0 && System.currentTimeMillis() - lastTileFailure < TILE_FAILURE_RECENT_MS) {
            degraded.add("tileWriteFailing");
        }

        JsonElement render = renderStatus();
        if (render != null && render.isJsonObject()) {
            JsonObject r = render.getAsJsonObject();
            // Chunk images left in the shared budget, -1 when unlimited, and the budget per minute.
            r.addProperty("budgetLeft", atlas.tiles().budgetLeft());
            r.addProperty("budgetPerMinute", atlas.tiles().budgetPerMinute());
            if (Json.num(r, "queued", 0) >= RENDER_BACKLOG) {
                degraded.add("renderBacklog");
            }
        }
        o.add("render", render == null ? com.google.gson.JsonNull.INSTANCE : render);

        long now = System.currentTimeMillis();
        com.google.gson.JsonArray stalled = new com.google.gson.JsonArray();
        for (LiveState.WorldInfo w : atlas.live().worlds().values()) {
            if (w.updatedAt() > 0 && now - w.updatedAt() > WORLD_STALL_MS) {
                JsonObject row = new JsonObject();
                row.addProperty("world", w.name());
                row.addProperty("updatedAt", w.updatedAt());
                stalled.add(row);
            }
        }
        o.add("stalledWorlds", stalled);
        if (!stalled.isEmpty()) {
            degraded.add("worldPollStalled");
        }

        JsonObject bind = bindState();
        o.add("bind", bind == null ? com.google.gson.JsonNull.INSTANCE : bind);
        if (bind != null) {
            degraded.add("bindRetry");
        }

        JsonObject extras = new JsonObject();
        try {
            healthExtras.accept(extras);
        } catch (RuntimeException e) {
            logger.at(Level.FINE).withCause(e).log("Atlas health figures failed");
        }
        for (Map.Entry<String, JsonElement> e : extras.entrySet()) {
            if ("degraded".equals(e.getKey())) {
                if (e.getValue().isJsonArray()) {
                    degraded.addAll(e.getValue().getAsJsonArray());
                }
            } else {
                o.add(e.getKey(), e.getValue());
            }
        }
        o.addProperty("status", degraded.isEmpty() ? "ok" : "degraded");
        o.add("degraded", degraded);
        return o;
    }

    /** A tile write that failed within this long counts as a current problem in the health answer. */
    static final long TILE_FAILURE_RECENT_MS = 10 * 60_000L;

    /** Queued tiles at which the render queue counts as a backlog. */
    static final int RENDER_BACKLOG = 500;

    /** The tile service's render status as JSON, or null when it has none. */
    private JsonElement renderStatus() {
        try {
            Object status = atlas.tiles().renderStatus();
            return status == null ? null : Json.GSON.toJsonTree(status);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- responses ---------------------------------------------------------

    private static void cors(Headers h) {
        h.set("Access-Control-Allow-Origin", "*");
    }

    private static void json(HttpExchange ex, int status, JsonElement body) throws IOException {
        Headers h = ex.getResponseHeaders();
        cors(h);
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("X-Content-Type-Options", "nosniff");
        if (!h.containsKey("Cache-Control")) {
            h.set("Cache-Control", "no-store");
        }
        byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024) {
            h.set("Vary", "Accept-Encoding");
            byte[] gz = acceptsGzip(ex.getRequestHeaders().getFirst("Accept-Encoding")) ? gzip(bytes) : null;
            if (gz != null) {
                h.set("Content-Encoding", "gzip");
                bytes = gz;
            }
        }
        send(ex, status, bytes, isHead(ex));
    }

    /** Sends the gzip form when there is one and the client accepts it, else the plain bytes. HEAD gets headers only. */
    private static void sendMaybeGzip(HttpExchange ex, int status, byte[] raw, byte[] gz) throws IOException {
        byte[] body = raw;
        if (gz != null) {
            ex.getResponseHeaders().set("Vary", "Accept-Encoding");
            if (acceptsGzip(ex.getRequestHeaders().getFirst("Accept-Encoding"))) {
                ex.getResponseHeaders().set("Content-Encoding", "gzip");
                body = gz;
            }
        }
        send(ex, status, body, isHead(ex));
    }

    /** True when an Accept-Encoding header lists gzip, or a star, without q=0. */
    static boolean acceptsGzip(String header) {
        if (header == null) {
            return false;
        }
        for (String part : header.split(",")) {
            String[] bits = part.trim().split(";");
            String coding = bits[0].trim().toLowerCase(java.util.Locale.ROOT);
            if (!coding.equals("gzip") && !coding.equals("x-gzip") && !coding.equals("*")) {
                continue;
            }
            double q = 1;
            for (int i = 1; i < bits.length; i++) {
                String b = bits[i].trim().toLowerCase(java.util.Locale.ROOT);
                if (b.startsWith("q=")) {
                    try {
                        q = Double.parseDouble(b.substring(2).trim());
                    } catch (NumberFormatException e) {
                        q = 0;
                    }
                }
            }
            if (q > 0) {
                return true;
            }
        }
        return false;
    }

    static byte[] gzip(byte[] bytes) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, bytes.length / 3));
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(bytes);
            }
            byte[] out = bos.toByteArray();
            return out.length < bytes.length ? out : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isHead(HttpExchange ex) {
        return "HEAD".equals(ex.getRequestMethod());
    }

    private static void error(HttpExchange ex, int status, String message) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        json(ex, status, o);
    }

    private static void send(HttpExchange ex, int status, byte[] body, boolean headOnly) throws IOException {
        if (headOnly) {
            if (body.length > 0 && status != 304 && status != 204) {
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
