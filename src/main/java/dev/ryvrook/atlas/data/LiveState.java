package dev.ryvrook.atlas.data;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live data read from the Hytale API. Player positions are sampled from the scheduler thread
 * through PlayerRef transforms, which Feats has shown to be safe off thread. Health, time of
 * day and spawn points are read on each world's own thread through world.execute and cached.
 */
public final class LiveState {
    public record PlayerSample(UUID uuid, String name, String world, double x, double y, double z,
                               Float health, Float maxHealth) {
    }

    public record WorldInfo(String name, int players, double[] spawn, Integer hour, Float dayProgress,
                            boolean mapEnabled, long updatedAt, WorldKind kind) {
    }

    /**
     * What sort of world this is. Kind is world, portal or instance. Portal holds the portal state
     * object sent to browsers, or null for other kinds. Built on the world thread, read anywhere.
     */
    public record WorldKind(String kind, String displayName, com.google.gson.JsonObject portal) {
        public static WorldKind plain(String name) {
            return new WorldKind("world", name, null);
        }

        public boolean transientWorld() {
            return !"world".equals(kind);
        }
    }

    private static final int TRAIL_POINTS = 360;
    private static final long TRAIL_STEP_MS = 5_000;

    private volatile List<PlayerSample> players = List.of();
    private volatile long sampledAt;
    private final Map<String, WorldInfo> worldInfo = new ConcurrentHashMap<>();
    private final Map<UUID, float[]> health = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<double[]>> trails = new ConcurrentHashMap<>();
    private final Map<UUID, String> trailWorld = new ConcurrentHashMap<>();
    private long lastTrailAt;

    /** Samples every online player. Safe to call from the scheduler. */
    public List<PlayerSample> sample() {
        List<PlayerSample> out = new ArrayList<>();
        Universe universe = Universe.get();
        if (universe == null) {
            players = List.of();
            return players;
        }
        for (World world : universe.getWorlds().values()) {
            String wname = world.getName();
            for (PlayerRef ref : world.getPlayerRefs()) {
                try {
                    Transform t = ref.getTransform();
                    if (t == null || t.getPosition() == null) {
                        continue;
                    }
                    Vector3d pos = t.getPosition();
                    float[] hp = health.get(ref.getUuid());
                    out.add(new PlayerSample(ref.getUuid(), ref.getUsername(), wname, pos.x, pos.y, pos.z,
                            hp == null ? null : hp[0], hp == null ? null : hp[1]));
                } catch (RuntimeException ignored) {
                    // The player may be leaving while we sample.
                }
            }
        }
        long now = System.currentTimeMillis();
        players = Collections.unmodifiableList(out);
        sampledAt = now;
        if (now - lastTrailAt >= TRAIL_STEP_MS) {
            lastTrailAt = now;
            recordTrails(out, now);
        }
        return players;
    }

    private void recordTrails(List<PlayerSample> list, long now) {
        java.util.Set<UUID> online = new java.util.HashSet<>();
        for (PlayerSample p : list) {
            online.add(p.uuid());
            String prevWorld = trailWorld.put(p.uuid(), p.world());
            Deque<double[]> d = trails.computeIfAbsent(p.uuid(), k -> new ArrayDeque<>());
            synchronized (d) {
                if (prevWorld != null && !prevWorld.equals(p.world())) {
                    d.clear();
                }
                double[] last = d.peekLast();
                if (last == null || Math.abs(last[0] - p.x()) + Math.abs(last[1] - p.z()) >= 1.0) {
                    d.addLast(new double[]{Math.round(p.x()), Math.round(p.z()), now});
                }
                while (d.size() > TRAIL_POINTS) {
                    d.removeFirst();
                }
            }
        }
        trails.keySet().removeIf(u -> !online.contains(u));
        trailWorld.keySet().removeIf(u -> !online.contains(u));
        health.keySet().removeIf(u -> !online.contains(u));
    }

    public List<double[]> trail(UUID uuid) {
        Deque<double[]> d = trails.get(uuid);
        if (d == null) {
            return List.of();
        }
        synchronized (d) {
            return new ArrayList<>(d);
        }
    }

    public List<PlayerSample> players() {
        return players;
    }

    /** The most recent sample of one player, or null when the player was not online at the last sample. */
    public PlayerSample sampleOf(UUID uuid) {
        for (PlayerSample p : players) {
            if (p.uuid().equals(uuid)) {
                return p;
            }
        }
        return null;
    }

    public long sampledAt() {
        return sampledAt;
    }

    public Map<String, WorldInfo> worlds() {
        return worldInfo;
    }

    /**
     * Refreshes world info and player health on every world thread. Values land in the caches
     * when each world gets to the task, which is at most a tick later.
     */
    public void refreshOnWorldThreads(boolean wantHealth) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        java.util.Set<String> alive = new java.util.HashSet<>();
        for (World world : universe.getWorlds().values()) {
            String name = world.getName();
            alive.add(name);
            WorldInfo prev = worldInfo.get(name);
            int count = world.getPlayerCount();
            worldInfo.put(name, new WorldInfo(name, count,
                    prev == null ? null : prev.spawn(), prev == null ? null : prev.hour(),
                    prev == null ? null : prev.dayProgress(), mapEnabled(world), System.currentTimeMillis(),
                    prev == null ? WorldKind.plain(name) : prev.kind()));
            try {
                world.execute(() -> readOnWorldThread(world, wantHealth));
            } catch (RuntimeException ignored) {
                // World is shutting down.
            }
        }
        worldInfo.keySet().removeIf(n -> !alive.contains(n));
    }

    private static boolean mapEnabled(World world) {
        try {
            return world.getWorldMapManager() != null && world.getWorldMapManager().isWorldMapEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void readOnWorldThread(World world, boolean wantHealth) {
        String name = world.getName();
        double[] spawn = null;
        Integer hour = null;
        Float progress = null;
        try {
            ISpawnProvider provider = world.getWorldConfig().getSpawnProvider();
            if (provider != null) {
                Transform[] points = provider.getSpawnPoints();
                if (points != null && points.length > 0 && points[0] != null && points[0].getPosition() != null) {
                    Vector3d p = points[0].getPosition();
                    spawn = new double[]{p.x, p.y, p.z};
                }
            }
        } catch (RuntimeException ignored) {
            // Some spawn providers have no fixed points.
        }
        Store<EntityStore> store = null;
        try {
            store = world.getEntityStore().getStore();
            WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
            if (time != null) {
                hour = time.getCurrentHour();
                progress = time.getDayProgress();
            }
        } catch (RuntimeException ignored) {
            // Time resource missing on this world.
        }
        WorldKind kind = WorldKinds.read(world, store);
        WorldInfo prev = worldInfo.get(name);
        worldInfo.put(name, new WorldInfo(name, world.getPlayerCount(), spawn, hour, progress,
                prev != null && prev.mapEnabled(), System.currentTimeMillis(), kind));
        if (!wantHealth || store == null) {
            return;
        }
        for (PlayerRef ref : world.getPlayerRefs()) {
            try {
                Ref<EntityStore> entity = ref.getReference();
                if (entity == null || !entity.isValid()) {
                    continue;
                }
                EntityStatMap stats = store.getComponent(entity, EntityStatMap.getComponentType());
                if (stats == null) {
                    continue;
                }
                EntityStatValue hp = stats.get(DefaultEntityStatTypes.getHealth());
                if (hp != null) {
                    health.put(ref.getUuid(), new float[]{hp.get(), hp.getMax()});
                }
            } catch (RuntimeException ignored) {
                // Entity removed between scheduling and running.
            }
        }
    }
}
