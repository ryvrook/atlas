package dev.ryvrook.atlas.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.atlas.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * Reads the leaderboard file Feats exports. Atlas never links against Feats, it watches the file's
 * modification time and parses it again when it changes. A missing file means no leaderboard.
 */
public final class Leaderboard {
    private volatile Path path;
    private volatile JsonObject root;
    private volatile long updatedAt;
    private FileTime lastModified;
    private long lastSize = -1;
    private volatile String lastError;
    /** True while a read failure has been logged and no good read has followed. */
    private boolean errorLogged;

    public Leaderboard(Path path) {
        this.path = path;
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
        Path p = path;
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
            root = o;
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
