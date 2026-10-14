package dev.ryvrook.atlas.integration;

import dev.ryvrook.atlas.data.HeatTracker;
import dev.ryvrook.atlas.data.KillLog;
import dev.ryvrook.atlas.systems.DeathSystem;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.PlayerSnapshot;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerApi;
import dev.ryvrook.trackerapi.api.event.BlockBroken;
import dev.ryvrook.trackerapi.api.event.BlockPlaced;
import dev.ryvrook.trackerapi.api.event.EntityDied;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Feeds the heat layer and the kill feed from TrackerAPI events in place of {@code BlockActivitySystem}
 * and {@code DeathSystem}. The only class of Atlas that names a TrackerAPI type. The plugin loads it only
 * after the plugin manager reported TrackerAPI, so Atlas still loads on a server without it.
 *
 * <p>Every listener is ASYNC and calls exactly what the system it replaces called, with the facts read
 * from the event instead of the engine. Cancelled breaks and places never arrive, TrackerAPI drops them.
 */
public final class TrackerBridge implements TrackerLink {
    static final String OWNER = "Atlas";

    private final HeatTracker heat;
    private final Consumer<KillLog.Kill> kills;
    private final DeathSystem.PlaceRule rule;
    private final Consumer<String> warn;
    private final List<Subscription> subscriptions = new ArrayList<>();
    private volatile TrackerApi api;
    private Consumer<TrackerApi> enableHook;
    private Runnable disableHook;

    public TrackerBridge(HeatTracker heat, Consumer<KillLog.Kill> kills, DeathSystem.PlaceRule rule, Consumer<String> warn) {
        this.heat = Objects.requireNonNull(heat, "heat");
        this.kills = Objects.requireNonNull(kills, "kills");
        this.rule = rule == null ? (p, w, x, y, z) -> false : rule;
        this.warn = warn == null ? s -> { } : warn;
    }

    /** Builds the bridge and hooks it to TrackerAPI's enable and disable. Called from the plugin's start. */
    public static TrackerLink install(HeatTracker heat, Consumer<KillLog.Kill> kills, DeathSystem.PlaceRule rule,
                                      Consumer<String> warn) {
        TrackerBridge b = new TrackerBridge(heat, kills, rule, warn);
        b.enableHook = b::bind;
        b.disableHook = b::unbind;
        TrackerApi.onEnable(b.enableHook);
        TrackerApi.onDisable(b.disableHook);
        return b;
    }

    /** Subscribes to breaks, places and deaths. Runs when TrackerAPI is up and every time it starts. */
    public synchronized void bind(TrackerApi api) {
        unbind();
        this.api = api;
        subscriptions.add(api.subscribe(OWNER, BlockBroken.class, this::broken));
        subscriptions.add(api.subscribe(OWNER, BlockPlaced.class, this::placed));
        subscriptions.add(api.subscribe(OWNER, EntityDied.class, this::died));
        List<String> off = new ArrayList<>();
        for (EventKind k : List.of(EventKind.BLOCK_BROKEN, EventKind.BLOCK_PLACED, EventKind.ENTITY_DIED)) {
            if (!api.isTracked(k)) {
                off.add(k.name());
            }
        }
        if (!off.isEmpty()) {
            warn.accept("TrackerAPI has " + String.join(", ", off)
                    + " turned off, so the heat layer or the kill feed of Atlas stays empty for them");
        }
    }

    /** Closes every subscription. Runs when TrackerAPI stops and when Atlas shuts down. */
    public synchronized void unbind() {
        for (Subscription s : subscriptions) {
            try {
                s.close();
            } catch (RuntimeException ignored) {
                // Already closed by TrackerAPI.
            }
        }
        subscriptions.clear();
        api = null;
    }

    @Override
    public synchronized boolean bound() {
        return api != null && !subscriptions.isEmpty();
    }

    @Override
    public synchronized void close() {
        TrackerApi.removeHooks(enableHook, disableHook);
        unbind();
    }

    // ---- listeners -------------------------------------------------------------------------------------

    /** BlockActivitySystem.Breaks. */
    void broken(BlockBroken e) {
        if (e.world() != null) {
            heat.record(e.world(), e.x(), e.z(), false, e.at());
        }
    }

    /** BlockActivitySystem.Places. */
    void placed(BlockPlaced e) {
        if (e.world() != null) {
            heat.record(e.world(), e.x(), e.z(), true, e.at());
        }
    }

    /** DeathSystem, with the positions read from the event and the killer's last sample. */
    void died(EntityDied e) {
        KillLog.Kill k = kill(e, api);
        if (k != null) {
            kills.accept(k);
        }
    }

    /** The kill line DeathSystem would have built for this death, or null when it would have built none. */
    KillLog.Kill kill(EntityDied e, TrackerApi api) {
        Actor v = e.victim();
        // TrackerAPI's boss list is the Feats one. Atlas's own list names a few roles it lacks, so a creature
        // either list calls a boss stays one and the feed keeps every boss line it had.
        KillLog.Party victim = party(v, v != null && v.isCreature() && (e.boss() || KillLog.isBoss(v.typeId())));
        if (victim == null) {
            return null;
        }
        Actor k = e.killer();
        KillLog.Party killer = k == null || sameEntity(v, k) ? null
                : party(k, k.isCreature() && KillLog.isBoss(k.typeId()));
        if (!victim.player() && (killer == null || !killer.player())) {
            // NPC deaths only count when a player caused them.
            return null;
        }
        String world = e.world();
        boolean located = finite(e.x(), e.y(), e.z());
        // The place of the death is the victim's. It is kept only when the live map would show each player in
        // the kill where they stand.
        if (located && victim.player() && rule.withheld(victim.uuid(), world, e.x(), e.y(), e.z())) {
            located = false;
        }
        if (located && killer != null && killer.player()) {
            PlayerSnapshot near = api == null ? null : api.snapshot(killer.uuid());
            if (near == null || !Objects.equals(near.world(), world) || !finite(near.x(), near.y(), near.z())
                    || rule.withheld(killer.uuid(), world, near.x(), near.y(), near.z())) {
                located = false;
            }
        }
        return new KillLog.Kill(e.at(), world, victim, killer, e.causeId(), e.ranged(),
                located ? (int) Math.floor(e.x()) : null, located ? (int) Math.floor(e.z()) : null);
    }

    /** The kill log side of an actor, as DeathSystem.party reads it from the entity, or null for the world. */
    private static KillLog.Party party(Actor a, boolean boss) {
        if (a == null) {
            return null;
        }
        if (a.isPlayer()) {
            return new KillLog.Party(a.name(), "Player", true, false, a.uuid());
        }
        if (a.isCreature()) {
            String role = a.typeId();
            return new KillLog.Party(KillLog.readable(role), role, false, boss, null);
        }
        return null;
    }

    private static boolean sameEntity(Actor a, Actor b) {
        return a != null && b != null && a.kind() == b.kind() && a.uuid() != null && a.uuid().equals(b.uuid());
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
