package dev.ryvrook.atlas.systems;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.ryvrook.atlas.data.KillLog;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Fires once per death of any entity on the world thread. Records every player death and every
 * NPC death caused by a player for the kill feed. Built like the death system in Feats, which is
 * verified on 0.7.0. Only cheap lookups run here, the rest happens in the consumer.
 */
public final class DeathSystem extends DeathSystems.OnDeathSystem {
    private final Consumer<KillLog.Kill> sink;
    private final PlaceRule rule;

    /** Tells whether a player's place must be stored as unknown, as the live map would not show it. */
    @FunctionalInterface
    public interface PlaceRule {
        boolean withheld(UUID player, String world, double x, double y, double z);
    }

    public DeathSystem(Consumer<KillLog.Kill> sink) {
        this(sink, (p, w, x, y, z) -> false);
    }

    public DeathSystem(Consumer<KillLog.Kill> sink, PlaceRule rule) {
        this.sink = sink;
        this.rule = rule;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref, DeathComponent death, Store<EntityStore> store,
                                 CommandBuffer<EntityStore> commandBuffer) {
        try {
            KillLog.Party victim = party(store, ref);
            if (victim == null) {
                return;
            }
            KillLog.Party killer = null;
            Ref<EntityStore> killerRef = null;
            boolean ranged = false;
            Damage info = death.getDeathInfo();
            if (info != null && info.getSource() instanceof Damage.EntitySource source) {
                Ref<EntityStore> k = source.getRef();
                if (k != null && k.isValid() && !k.equals(ref)) {
                    killer = party(store, k);
                    killerRef = k;
                }
                ranged = source instanceof Damage.ProjectileSource;
            }
            if (!victim.player() && (killer == null || !killer.player())) {
                // NPC deaths only count when a player caused them.
                return;
            }
            DamageCause cause = death.getDeathCause();
            World world = store.getExternalData().getWorld();
            String worldName = world == null ? null : world.getName();
            double[] at = position(store, ref);
            // The place of the death is the victim's. It is kept only when the live map would show each
            // player in the kill where they stand.
            if (at != null && victim.player() && rule.withheld(victim.uuid(), worldName, at[0], at[1], at[2])) {
                at = null;
            }
            if (at != null && killer != null && killer.player()) {
                double[] near = position(store, killerRef);
                if (near == null || rule.withheld(killer.uuid(), worldName, near[0], near[1], near[2])) {
                    at = null;
                }
            }
            sink.accept(new KillLog.Kill(System.currentTimeMillis(), world == null ? null : world.getName(), victim,
                    killer, cause == null ? null : cause.getId(), ranged, at == null ? null : (int) Math.floor(at[0]),
                    at == null ? null : (int) Math.floor(at[2])));
        } catch (RuntimeException ignored) {
            // Never break the death pipeline for the map.
        }
    }

    /** Where the entity stands as {x, y, z}, or null when its position cannot be read. */
    private static double[] position(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            if (ref == null || !ref.isValid()) {
                return null;
            }
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            if (t == null || t.getPosition() == null) {
                return null;
            }
            double x = t.getPosition().x;
            double y = t.getPosition().y;
            double z = t.getPosition().z;
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                return null;
            }
            return new double[]{x, y, z};
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static KillLog.Party party(Store<EntityStore> store, Ref<EntityStore> ref) {
        PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
        if (player != null) {
            return new KillLog.Party(player.getUsername(), "Player", true, false, player.getUuid());
        }
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null) {
            return null;
        }
        String role = npc.getNPCTypeId();
        return new KillLog.Party(KillLog.readable(role), role, false, KillLog.isBoss(role), null);
    }
}
