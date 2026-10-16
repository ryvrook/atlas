package dev.ryvrook.atlas.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.atlas.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Reads the leaderboard file Feats exports. Atlas never links against Feats, it watches the file's
 * modification time and parses it again when it changes. A missing file means no leaderboard.
 */
public final class Leaderboard {
    private volatile Path path;
    private volatile boolean off;
    private volatile JsonObject root;
    private volatile long updatedAt;
    private FileTime lastModified;
    private long lastSize = -1;
    private volatile String lastError;
    /** True while a read failure has been logged and no good read has followed. */
    private boolean errorLogged;
    /** Told about each unlock, milestone and first that is new since the previous read, for the timeline. */
    private volatile Consumer<FeatEvent> events = e -> { };

    /**
     * One new Feats event. kind is feats.unlock, feats.milestone or feats.first. uuid and name are the real
     * player, or null when the record names nobody. featName falls back to the feat id. hiddenFeat is true for
     * a feat Feats keeps hidden, as far as its recent list shows.
     */
    public record FeatEvent(String kind, long at, UUID uuid, String name, String feat, String featName, String tier,
                            boolean hiddenFeat) {
    }

    public void onEvent(Consumer<FeatEvent> listener) {
        events = listener == null ? e -> { } : listener;
    }

    public Leaderboard(Path path) {
        this.path = path;
    }

    /** Turns reading off or on. While off the file counts as missing, for the feats or chronicle key set to never. */
    public synchronized void setOff(boolean off) {
        if (this.off != off) {
            this.off = off;
            lastModified = null;
            lastSize = -1;
        }
    }

    public boolean off() {
        return off;
    }

    public synchronized void setPath(Path p) {
        if (!p.equals(path)) {
            path = p;
            lastModified = null;
            lastSize = -1;
        }
    }

    public Path path() {
        return path;
    }

    /** The parsed export, or null when the file is missing. Never modified after it is published. */
    public JsonObject root() {
        return root;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public String lastError() {
        return lastError;
    }

    /** Checks the file and reloads it when it changed. Returns true when the published data changed. */
    public synchronized boolean poll() {
        Path p = off ? null : path;
        try {
            if (p == null || !Files.isRegularFile(p)) {
                lastModified = null;
                lastSize = -1;
                if (root != null) {
                    root = null;
                    updatedAt = System.currentTimeMillis();
                    return true;
                }
                return false;
            }
            FileTime mod = Files.getLastModifiedTime(p);
            long size = Files.size(p);
            if (mod.equals(lastModified) && size == lastSize) {
                return false;
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                fail(p, "the root is not a JSON object");
                // The same broken file is not parsed again until it changes.
                lastModified = mod;
                lastSize = size;
                return false;
            }
            lastModified = mod;
            lastSize = size;
            JsonObject o = parsed.getAsJsonObject();
            JsonObject before = root;
            root = o;
            if (before != null) {
                // The first read only sets the baseline, so a restart does not replay old unlocks.
                for (FeatEvent e : diff(before, o)) {
                    try {
                        events.accept(e);
                    } catch (RuntimeException ignored) {
                        // The timeline must never break the leaderboard.
                    }
                }
            }
            updatedAt = (long) Json.num(o, "updatedAt", mod.toMillis());
            lastError = null;
            errorLogged = false;
            return true;
        } catch (Exception e) {
            // Feats writes through a temporary file, so this is rare. Try again on the next poll.
            fail(p, e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    /**
     * The unlocks, milestones and firsts in next that were not in before. Unlocks count as new when they
     * are newer than every unlock before held, or as new as the newest and not listed before. Milestones
     * and firsts count as new when their feat is new or its time changed.
     */
    public static List<FeatEvent> diff(JsonObject before, JsonObject next) {
        List<FeatEvent> out = new ArrayList<>();
        long newest = Long.MIN_VALUE;
        Set<String> seen = new HashSet<>();
        for (JsonElement e : Json.arr(before, "recent")) {
            if (e.isJsonObject()) {
                Long at = Payloads.time(e.getAsJsonObject(), "at");
                if (at != null) {
                    newest = Math.max(newest, at);
                    seen.add(unlockKey(e.getAsJsonObject(), at));
                }
            }
        }
        for (JsonElement e : Json.arr(next, "recent")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject r = e.getAsJsonObject();
            Long at = Payloads.time(r, "at");
            String feat = Payloads.text(r, "feat");
            if (at == null || feat == null || at < newest || seen.contains(unlockKey(r, at))) {
                continue;
            }
            out.add(new FeatEvent("feats.unlock", at, Payloads.uuid(Payloads.text(r, "uuid")), Payloads.text(r, "name"),
                    feat, nameOr(Payloads.text(r, "featName"), feat), Payloads.text(r, "tier"), Payloads.flag(r, "hidden")));
        }
        java.util.Set<String> secret = Payloads.hiddenFeats(next);
        records(out, "feats.milestone", Json.obj(before, "milestones"), Json.obj(next, "milestones"), secret);
        records(out, "feats.first", Json.obj(before, "firsts"), Json.obj(next, "firsts"), secret);
        out.sort((a, b) -> Long.compare(a.at(), b.at()));
        return out;
    }

    private static void records(List<FeatEvent> out, String kind, JsonObject before, JsonObject next,
                                java.util.Set<String> secret) {
        if (next == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : next.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject r = e.getValue().getAsJsonObject();
            Long at = Payloads.time(r, "at");
            if (at == null) {
                continue;
            }
            JsonObject prev = before == null ? null : Json.obj(before, e.getKey());
            Long prevAt = prev == null ? null : Payloads.time(prev, "at");
            if (prevAt != null && prevAt.equals(at)) {
                continue;
            }
            out.add(new FeatEvent(kind, at, Payloads.uuid(Payloads.text(r, "by")), Payloads.text(r, "byName"), e.getKey(),
                    nameOr(Payloads.text(r, "featName"), e.getKey()), Payloads.text(r, "tier"), secret.contains(e.getKey())));
        }
    }

    private static String unlockKey(JsonObject r, long at) {
        return at + "|" + Payloads.text(r, "uuid") + "|" + Payloads.text(r, "feat");
    }

    private static String nameOr(String a, String b) {
        return a == null ? b : a;
    }

    /** Records a read failure and logs it once until a good read follows. */
    private void fail(Path p, String reason) {
        lastError = reason;
        if (errorLogged) {
            return;
        }
        errorLogged = true;
        try {
            com.hypixel.hytale.logger.HytaleLogger.get("Atlas").at(java.util.logging.Level.WARNING)
                    .log("Atlas could not read the Feats leaderboard at " + p + " because " + reason
                            + ". The last good copy stays in use and this is logged once until the file reads again.");
        } catch (RuntimeException | LinkageError ignored) {
            // No logging backend, as in unit tests.
        }
    }
}
