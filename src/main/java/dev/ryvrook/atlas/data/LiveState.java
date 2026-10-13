package dev.ryvrook.atlas.data;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HiddenFromAdventurePlayers;
import com.hypixel.hytale.protocol.MovementStates;
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
    /** Worlds with a world info read queued on their thread. */
    private final java.util.Set<String> readInFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, WorldInfo> worldInfo = new ConcurrentHashMap<>();
    private final Map<UUID, float[]> health = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<double[]>> trails = new ConcurrentHashMap<>();
    /** Players read as crouching or invisible on the last world thread read, with which of the two. */
    private final Map<UUID, String> autoHidden = new ConcurrentHashMap<>();
    /** Effect indexes already judged, true for effects whose id names invisibility. */
    private final Map<Integer, Boolean> invisibleEffects = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<PlayerSample> joinListener = p -> { };
    /** Players of the previous sample, null before the first one, so players online at start are no joins. */
    private java.util.Set<UUID> lastOnline;
    private volatile boolean hideCrouching;
    private volatile boolean hideInvisible;
    private final Map<UUID, String> trailWorld = new ConcurrentHashMap<>();
    private long lastTrailAt;

    /** Sets the automatic hide rules from atlas.json. Takes effect on the next world thread read. */
    public void setHideRules(boolean crouching, boolean invisible) {
        hideCrouching = crouching;
        hideInvisible = invisible;
        if (!crouching && !invisible) {
            autoHidden.clear();
        }
    }

    /**
     * crouching or invisible when an automatic hide rule that is switched on applies to the player, read on
     * the world thread at most two seconds ago, otherwise null.
     */
    public String autoHideReason(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        String r = autoHidden.get(uuid);
        if (r == null) {
            return null;
        }
        if ("crouching".equals(r) ? hideCrouching : hideInvisible) {
            return r;
        }
        return null;
    }

    /** Told about each player that appears in a sample after the first one, for the timeline. */
    public void onJoin(java.util.function.Consumer<PlayerSample> listener) {
        joinListener = listener == null ? p -> { } : listener;
    }

    /** Samples every online player. Safe to call from the scheduler. */
    public synchronized List<PlayerSample> sample() {
        List<PlayerSample> out = new ArrayList<>();
        Universe universe = Universe.get();
        if (universe == null) {
            players = List.of();
            return players;
        }
        for (World world : universe.getWorlds().values()) {
            String wname = world.getName();
            WorldInfo info = worldInfo.get(wname);
            if (info == null || info.kind() == null) {
                // The world kind is not known yet, so it may be a portal world some viewers must not see.
                // Its players are listed once the first read on the world thread has told the kind.
                continue;
            }
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
        java.util.Set<UUID> online = new java.util.HashSet<>(out.size() * 2);
        for (PlayerSample p : out) {
            online.add(p.uuid());
            if (lastOnline != null && !lastOnline.contains(p.uuid())) {
                try {
                    joinListener.accept(p);
                } catch (RuntimeException ignored) {
                    // The timeline must never break the player sample.
                }
            }
        }
        lastOnline = online;
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
        autoHidden.keySet().removeIf(u -> !online.contains(u));
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
            int count = world.getPlayerCount();
            boolean map = mapEnabled(world);
            long now = System.currentTimeMillis();
            // The kind stays null until the world thread has read it. updatedAt moves only when the world
            // thread answers, so a world whose thread stopped answering shows up as stalled. compute keeps
            // a read that lands at the same moment from being overwritten with older values.
            worldInfo.compute(name, (k, prev) -> new WorldInfo(name, count,
                    prev == null ? null : prev.spawn(), prev == null ? null : prev.hour(),
                    prev == null ? null : prev.dayProgress(), map, prev == null ? now : prev.updatedAt(),
                    prev == null ? null : prev.kind()));
            if (!readInFlight.add(name)) {
                // The previous read has not run yet. A busy world gets one queued read at a time.
                continue;
            }
            try {
                world.execute(() -> {
                    try {
                        readOnWorldThread(world, wantHealth);
                    } finally {
                        readInFlight.remove(name);
                    }
                });
            } catch (RuntimeException ignored) {
                // World is shutting down.
                readInFlight.remove(name);
            }
        }
        worldInfo.keySet().removeIf(n -> !alive.contains(n));
        readInFlight.removeIf(n -> !alive.contains(n));
    }

    /** Forgets a removed world. */
    public void forget(String world) {
        worldInfo.remove(world);
        readInFlight.remove(world);
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
        int count = world.getPlayerCount();
        double[] spawnAt = spawn;
        Integer hourNow = hour;
        Float progressNow = progress;
        // A world removed while this read waited on its thread stays removed.
        WorldInfo next = worldInfo.computeIfPresent(name, (k, prev) -> new WorldInfo(name, count, spawnAt, hourNow,
                progressNow, prev.mapEnabled(), System.currentTimeMillis(), kind));
        if (next == null) {
            return;
        }
        boolean hideRules = hideCrouching || hideInvisible;
        if ((!wantHealth && !hideRules) || store == null) {
            return;
        }
        for (PlayerRef ref : world.getPlayerRefs()) {
            try {
                Ref<EntityStore> entity = ref.getReference();
                if (entity == null || !entity.isValid()) {
                    continue;
                }
                if (hideRules) {
                    String reason = hideReason(store, entity);
                    if (reason == null) {
                        autoHidden.remove(ref.getUuid());
                    } else {
                        autoHidden.put(ref.getUuid(), reason);
                    }
                }
                if (!wantHealth) {
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

    /**
     * Reads the automatic hide state of one player on the world thread. Crouching comes from the movement
     * states component. Invisible means the entity carries HiddenFromAdventurePlayers, the engine's own
     * way to hide an entity from players, or has an active entity effect whose id names invisibility,
     * which is how plugins add invisibility potions. Three component reads, so it stays cheap.
     */
    private String hideReason(Store<EntityStore> store, Ref<EntityStore> entity) {
        if (hideInvisible) {
            if (store.getComponent(entity, HiddenFromAdventurePlayers.getComponentType()) != null) {
                return "invisible";
            }
            EffectControllerComponent effects = store.getComponent(entity, EffectControllerComponent.getComponentType());
            if (effects != null) {
                int[] active = effects.getActiveEffectIndexes();
                if (active != null) {
                    for (int index : active) {
                        if (invisibleEffect(index)) {
                            return "invisible";
                        }
                    }
                }
            }
        }
        if (hideCrouching) {
            MovementStatesComponent move = store.getComponent(entity, MovementStatesComponent.getComponentType());
            MovementStates states = move == null ? null : move.getMovementStates();
            if (states != null && (states.crouching || states.forcedCrouching)) {
                return "crouching";
            }
        }
        return null;
    }

    private boolean invisibleEffect(int index) {
        Boolean known = invisibleEffects.get(index);
        if (known != null) {
            return known;
        }
        boolean invisible = false;
        try {
            EntityEffect e = EntityEffect.getAssetMap().getAsset(index);
            String id = e == null ? null : e.getId();
            invisible = id != null && id.toLowerCase(java.util.Locale.ROOT).contains("invisib");
        } catch (RuntimeException ignored) {
            // Asset map not ready. Judged again on the next read.
            return false;
        }
        invisibleEffects.put(index, invisible);
        return invisible;
    }
}
