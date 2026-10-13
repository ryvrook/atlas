package dev.ryvrook.atlas.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.atlas.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** When and where each player was last seen, recorded on disconnect and saved in lastseen.json. */
public final class LastSeen {
    public static final long KEEP_MS = 30L * 24 * 60 * 60 * 1000L;

    public record Entry(UUID uuid, String name, long at, String world, double x, double y, double z) {
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Path file;
    /** Counts changes. The file is written while it differs from the count at the last successful write. */
    private final java.util.concurrent.atomic.AtomicLong changes = new java.util.concurrent.atomic.AtomicLong();
    private volatile long saved;

    public LastSeen(Path dataDir) {
        this.file = dataDir.resolve("lastseen.json");
    }

    public void record(Entry e) {
        entries.put(e.uuid(), e);
        changes.incrementAndGet();
    }

    /** Entries seen at or after the time, newest first. */
    public List<Entry> since(long at) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.at() >= at) {
                out.add(e);
            }
        }
        out.sort((a, b) -> Long.compare(b.at(), a.at()));
        return out;
    }

    public void prune() {
        long floor = System.currentTimeMillis() - KEEP_MS;
        if (entries.values().removeIf(e -> e.at() < floor)) {
            changes.incrementAndGet();
        }
    }

    public void load() {
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) {
                return;
            }
            for (JsonElement el : root.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                try {
                    UUID uuid = UUID.fromString(Json.str(o, "uuid"));
                    entries.put(uuid, new Entry(uuid, Json.str(o, "name"), (long) Json.num(o, "at", 0),
                            Json.str(o, "world"), Json.num(o, "x", 0), Json.num(o, "y", 0), Json.num(o, "z", 0)));
                } catch (RuntimeException ignored) {
                    // Skip a damaged row.
                }
            }
            prune();
        } catch (Exception ignored) {
            // A damaged file only costs the last seen list.
        }
    }

    public synchronized void save() throws java.io.IOException {
        long version = changes.get();
        if (version == saved) {
            return;
        }
        JsonArray out = new JsonArray();
        for (Entry e : entries.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", e.uuid().toString());
            o.addProperty("name", e.name());
            o.addProperty("at", e.at());
            o.addProperty("world", e.world());
            o.addProperty("x", Math.round(e.x() * 100) / 100.0);
            o.addProperty("y", Math.round(e.y() * 100) / 100.0);
            o.addProperty("z", Math.round(e.z() * 100) / 100.0);
            out.add(o);
        }
        Json.writeAtomic(file, Json.GSON.toJson(out));
        // Only a successful write counts. A failed one leaves the changes pending for the next save.
        saved = version;
    }
}
