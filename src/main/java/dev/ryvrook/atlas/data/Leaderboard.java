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
                lastError = "the root is not a JSON object";
                return false;
            }
            lastModified = mod;
            lastSize = size;
            JsonObject o = parsed.getAsJsonObject();
            root = o;
            updatedAt = (long) Json.num(o, "updatedAt", mod.toMillis());
            lastError = null;
            return true;
        } catch (Exception e) {
            // Feats writes through a temporary file, so this is rare. Try again on the next poll.
            lastError = e.getClass().getSimpleName() + " " + e.getMessage();
            return false;
        }
    }
}
