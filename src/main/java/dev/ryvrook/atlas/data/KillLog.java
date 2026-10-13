package dev.ryvrook.atlas.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.atlas.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/** The last 200 player deaths and NPC deaths caused by players, saved in kills.json. */
public final class KillLog {
    public static final int KEEP = 200;

    /** One side of a kill. Uuid is set for players and never sent to browsers. */
    public record Party(String name, String type, boolean player, boolean boss, UUID uuid) {
    }

    public record Kill(long at, String world, Party victim, Party killer, String cause, boolean ranged) {
    }

    private static final String[] BOSS_EXACT = {"Dungeon_Feastmaster_Boss", "Bear_Voidtaken", "Golem_Guardian_Void",
            "Shadow_Knight", "Goblin_Ogre"};
    private static final String[] BOSS_PREFIX = {"Goblin_Gobliterator_", "Skeleton_Elite", "Goblin_Duke", "Dragon_",
            "Scarak_Broodmother"};

    private final Deque<Kill> kills = new ArrayDeque<>();
    private final Path file;
    /** Counts changes. The file is written while it differs from the count at the last successful write. */
    private final java.util.concurrent.atomic.AtomicLong changes = new java.util.concurrent.atomic.AtomicLong();
    private volatile long saved;

    public KillLog(Path dataDir) {
        this.file = dataDir.resolve("kills.json");
    }

    /** True for the NPC role ids Atlas treats as bosses. */
    public static boolean isBoss(String roleId) {
        if (roleId == null) {
            return false;
        }
        for (String s : BOSS_EXACT) {
            if (s.equals(roleId)) {
                return true;
            }
        }
        for (String s : BOSS_PREFIX) {
            if (roleId.startsWith(s)) {
                return true;
            }
        }
        return false;
    }

    /** A role id made readable, underscores to spaces. */
    public static String readable(String roleId) {
        return roleId == null ? "Something" : roleId.replace('_', ' ').trim();
    }

    public void add(Kill k) {
        synchronized (kills) {
            kills.addLast(k);
            while (kills.size() > KEEP) {
                kills.removeFirst();
            }
        }
        changes.incrementAndGet();
    }

    /** Up to limit entries, oldest first. */
    public List<Kill> recent(int limit) {
        synchronized (kills) {
            List<Kill> all = new ArrayList<>(kills);
            return all.subList(Math.max(0, all.size() - Math.max(0, limit)), all.size());
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
            synchronized (kills) {
                for (JsonElement e : root.getAsJsonArray()) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject o = e.getAsJsonObject();
                    Party victim = party(Json.obj(o, "victim"));
                    if (victim == null) {
                        continue;
                    }
                    kills.addLast(new Kill((long) Json.num(o, "at", 0), Json.str(o, "world"), victim,
                            party(Json.obj(o, "killer")), Json.str(o, "cause"), Json.bool(o, "ranged")));
                }
                while (kills.size() > KEEP) {
                    kills.removeFirst();
                }
            }
        } catch (Exception ignored) {
            // A damaged file only costs the kill history.
        }
    }

    public synchronized void save() throws java.io.IOException {
        long version = changes.get();
        if (version == saved) {
            return;
        }
        JsonArray out = new JsonArray();
        for (Kill k : recent(KEEP)) {
            JsonObject o = new JsonObject();
            o.addProperty("at", k.at());
            o.addProperty("world", k.world());
            o.add("victim", partyJson(k.victim()));
            if (k.killer() != null) {
                o.add("killer", partyJson(k.killer()));
            }
            o.addProperty("cause", k.cause());
            o.addProperty("ranged", k.ranged());
            out.add(o);
        }
        Json.writeAtomic(file, Json.GSON.toJson(out));
        // Only a successful write counts. A failed one leaves the changes pending for the next save.
        saved = version;
    }

    private static JsonObject partyJson(Party p) {
        JsonObject o = new JsonObject();
        o.addProperty("name", p.name());
        o.addProperty("type", p.type());
        o.addProperty("player", p.player());
        o.addProperty("boss", p.boss());
        o.addProperty("uuid", p.uuid() == null ? null : p.uuid().toString());
        return o;
    }

    private static Party party(JsonObject o) {
        if (o == null) {
            return null;
        }
        UUID uuid = null;
        String raw = Json.str(o, "uuid");
        if (raw != null) {
            try {
                uuid = UUID.fromString(raw);
            } catch (IllegalArgumentException ignored) {
                // Saved by hand. Keep the row without a uuid.
            }
        }
        return new Party(Json.str(o, "name"), Json.str(o, "type"), Json.bool(o, "player"), Json.bool(o, "boss"), uuid);
    }
}
