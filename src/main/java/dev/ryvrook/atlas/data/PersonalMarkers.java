package dev.ryvrook.atlas.data;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.asset.type.gameplay.WorldMapConfig;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerConfigData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerDeathPositionData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerWorldData;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.user.UserMapMarker;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * The map markers only one player sees in game. Their own user markers, their respawn points and
 * their death positions, read from the online Player's per world data on the world thread, the same
 * data PersonalMarkersProvider, RespawnMarkerProvider and DeathMarkerProvider read. Homes and deaths
 * follow the world map config switches isDisplayHome and isDisplayDeathMarker, as in game.
 *
 * <p>The last set per player and world is kept in memory for an hour after it was read, so a linked
 * viewer still sees their markers for a while after logging out. Nothing here is written to disk.
 */
public final class PersonalMarkers {
    public static final long KEEP_MS = 60 * 60 * 1000L;

    /** One personal entry. Kind is personal, home or death. y is null for user markers. */
    public record Entry(String id, String kind, String name, int x, Integer y, int z, String icon, String tint,
                        UUID byUuid, String byName) {
    }

    public record PersonalSet(long at, List<Entry> entries, long hash) {
    }

    private final Map<UUID, Map<String, PersonalSet>> cache = new ConcurrentHashMap<>();
    /**
     * The hash each player and world was last announced with. Only poll reads and writes it, so a read
     * made for one browser tab can never swallow the change the player's other streams need to hear.
     */
    private final Map<UUID, Map<String, Long>> announced = new ConcurrentHashMap<>();
    /** Reads of one player and world queued on the world thread, keyed by uuid and world name. */
    private final Map<String, CompletableFuture<PersonalSet>> reads = new ConcurrentHashMap<>();
    /** Worlds with a poll queued on their thread. */
    private final Set<String> pollInFlight = ConcurrentHashMap.newKeySet();

    /**
     * Builds the entries from the engine values. Pure, so it can be checked without a running
     * server. Any argument may be null.
     */
    public static List<Entry> build(Collection<? extends UserMapMarker> markers, PlayerRespawnPointData[] homes,
                                    List<PlayerDeathPositionData> deaths, boolean showHomes, boolean showDeaths) {
        List<Entry> out = new ArrayList<>();
        if (markers != null) {
            for (UserMapMarker m : markers) {
                EngineData.SharedMarker s = EngineData.sharedOf(m);
                if (s != null) {
                    out.add(new Entry(s.id(), "personal", s.name(), s.x(), null, s.z(), s.icon(), s.tint(),
                            s.byUuid(), s.byName()));
                }
            }
        }
        if (showHomes && homes != null) {
            for (int i = 0; i < homes.length; i++) {
                PlayerRespawnPointData h = homes[i];
                if (h == null) {
                    continue;
                }
                Vector3i b = h.getBlockPosition();
                Vector3d r = h.getRespawnPosition();
                if (b == null && r == null) {
                    continue;
                }
                int x = b != null ? b.x : (int) Math.round(r.x);
                int y = b != null ? b.y : (int) Math.round(r.y);
                int z = b != null ? b.z : (int) Math.round(r.z);
                String name = h.getName();
                out.add(new Entry("home_" + i, "home", name == null || name.isBlank() ? "Home" : name, x, y, z,
                        "Home", null, null, null));
            }
        }
        if (showDeaths && deaths != null) {
            int n = 0;
            for (PlayerDeathPositionData d : deaths) {
                Transform t = d == null ? null : d.getTransform();
                Vector3d p = t == null ? null : t.getPosition();
                if (p == null) {
                    continue;
                }
                String id = d.getMarkerId() != null ? d.getMarkerId() : "death_" + n;
                n++;
                out.add(new Entry(id, "death", "Died on day " + d.getDay(), (int) Math.round(p.x),
                        (int) Math.round(p.y), (int) Math.round(p.z), "Death", null, null, null));
            }
        }
        return out;
    }

    /**
     * Reads the personal entries of one player in one world. Runs on the world thread. Null when the
     * player is not in that world.
     */
    static List<Entry> read(World world, UUID uuid) {
        PlayerRef ref = null;
        for (PlayerRef r : world.getPlayerRefs()) {
            if (uuid.equals(r.getUuid())) {
                ref = r;
                break;
            }
        }
        if (ref == null) {
            return null;
        }
        Ref<EntityStore> entity = ref.getReference();
        if (entity == null || !entity.isValid()) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Player player = store.getComponent(entity, Player.getComponentType());
        PlayerConfigData cfg = player == null ? null : player.getPlayerConfigData();
        PlayerWorldData data = cfg == null ? null : cfg.getPerWorldData(world.getName());
        if (data == null) {
            return List.of();
        }
        boolean homes = true;
        boolean deaths = true;
        try {
            WorldMapConfig map = world.getGameplayConfig().getWorldMapConfig();
            if (map != null) {
                homes = map.isDisplayHome();
                deaths = map.isDisplayDeathMarker();
            }
        } catch (RuntimeException ignored) {
            // No gameplay config. The engine defaults show both.
        }
        return build(data.getUserMapMarkers(), data.getRespawnPoints(), data.getDeathPositions(), homes, deaths);
    }

    /** The cached set of a player in a world, or null when none was read within the last hour. */
    public PersonalSet get(UUID uuid, String world) {
        Map<String, PersonalSet> m = uuid == null ? null : cache.get(uuid);
        PersonalSet s = m == null ? null : m.get(world);
        if (s != null && System.currentTimeMillis() - s.at() > KEEP_MS) {
            return null;
        }
        return s;
    }

    /** Stores a fresh read and returns the previous set of that player and world. */
    private PersonalSet put(UUID uuid, String world, List<Entry> entries) {
        PersonalSet next = new PersonalSet(System.currentTimeMillis(), List.copyOf(entries), entries.hashCode());
        return cache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(world, next);
    }

    /**
     * Queues a read of one player on the world thread. The future completes with the fresh set, or
     * with null when the player is not in that world or the world is going away.
     */
    public CompletableFuture<PersonalSet> refresh(World world, UUID uuid) {
        String name = world.getName();
        String key = uuid + "/" + name;
        CompletableFuture<PersonalSet> f = new CompletableFuture<>();
        // Requests for the same player and world while a read is queued share that read.
        CompletableFuture<PersonalSet> running = reads.putIfAbsent(key, f);
        if (running != null) {
            return running;
        }
        try {
            world.execute(() -> {
                try {
                    List<Entry> entries = read(world, uuid);
                    if (entries == null) {
                        f.complete(null);
                        return;
                    }
                    put(uuid, name, entries);
                    f.complete(get(uuid, name));
                } catch (Throwable t) {
                    f.complete(null);
                } finally {
                    reads.remove(key, f);
                }
            });
        } catch (RuntimeException e) {
            reads.remove(key, f);
            f.complete(null);
        }
        return f;
    }

    /**
     * Reads every online player in the given set on their world thread and hands each player and world
     * whose set changed to the listener on the executor. The first read of a player and world only
     * records it.
     */
    public void poll(Set<UUID> players, Executor executor, BiConsumer<UUID, String> changed) {
        if (players.isEmpty()) {
            return;
        }
        Universe u = Universe.get();
        if (u == null) {
            return;
        }
        for (World w : u.getWorlds().values()) {
            String name = w.getName();
            List<UUID> here = new ArrayList<>();
            for (PlayerRef r : w.getPlayerRefs()) {
                if (r.getUuid() != null && players.contains(r.getUuid())) {
                    here.add(r.getUuid());
                }
            }
            if (here.isEmpty() || !pollInFlight.add(name)) {
                // Nobody to read, or the previous poll of this world has not run yet.
                continue;
            }
            CompletableFuture<Map<UUID, List<Entry>>> read = new CompletableFuture<>();
            try {
                w.execute(() -> {
                    Map<UUID, List<Entry>> found = new java.util.HashMap<>();
                    try {
                        for (UUID id : here) {
                            try {
                                List<Entry> e = read(w, id);
                                if (e != null) {
                                    found.put(id, e);
                                }
                            } catch (RuntimeException ignored) {
                                // Player leaving while we read.
                            }
                        }
                    } finally {
                        pollInFlight.remove(name);
                        read.complete(found);
                    }
                });
            } catch (RuntimeException e) {
                pollInFlight.remove(name);
                continue;
            }
            read.thenAcceptAsync(found -> {
                for (Map.Entry<UUID, List<Entry>> e : found.entrySet()) {
                    put(e.getKey(), name, e.getValue());
                    long hash = e.getValue().hashCode();
                    Long prev = announced.computeIfAbsent(e.getKey(), k -> new ConcurrentHashMap<>()).put(name, hash);
                    if (prev != null && prev != hash) {
                        changed.accept(e.getKey(), name);
                    }
                }
            }, executor);
        }
    }

    /** Forgets sets older than an hour. */
    public void prune() {
        long floor = System.currentTimeMillis() - KEEP_MS;
        for (Map<String, PersonalSet> m : cache.values()) {
            m.values().removeIf(s -> s.at() < floor);
        }
        cache.values().removeIf(Map::isEmpty);
        // Players without a cached set start over with a silent first read.
        announced.keySet().removeIf(u -> !cache.containsKey(u));
    }

    /** Forgets every set of a removed world. */
    public void dropWorld(String world) {
        pollInFlight.remove(world);
        reads.entrySet().removeIf(e -> {
            if (e.getKey().endsWith("/" + world)) {
                e.getValue().complete(null);
                return true;
            }
            return false;
        });
        for (Map<String, PersonalSet> m : cache.values()) {
            m.remove(world);
        }
        cache.values().removeIf(Map::isEmpty);
        for (Map<String, Long> m : announced.values()) {
            m.remove(world);
        }
        announced.values().removeIf(Map::isEmpty);
    }
}
