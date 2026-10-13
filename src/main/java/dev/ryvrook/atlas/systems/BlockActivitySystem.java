package dev.ryvrook.atlas.systems;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.CancellableEcsEvent;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.atlas.data.HeatTracker;
import org.joml.Vector3i;

/**
 * Counts block breaks and places per chunk for the heat layer. Runs on the world thread and
 * only does a few map increments, so it never slows the tick. The engine registers systems by
 * class, so breaks and places each get their own subclass.
 */
public abstract class BlockActivitySystem<E extends CancellableEcsEvent> extends EntityEventSystem<EntityStore, E> {
    private final HeatTracker heat;
    private final boolean place;

    private BlockActivitySystem(Class<E> type, HeatTracker heat, boolean place) {
        super(type);
        this.heat = heat;
        this.place = place;
    }

    public static BlockActivitySystem<BreakBlockEvent> breaks(HeatTracker heat) {
        return new Breaks(heat);
    }

    public static BlockActivitySystem<PlaceBlockEvent> places(HeatTracker heat) {
        return new Places(heat);
    }

    static final class Breaks extends BlockActivitySystem<BreakBlockEvent> {
        Breaks(HeatTracker heat) {
            super(BreakBlockEvent.class, heat, false);
        }
    }

    static final class Places extends BlockActivitySystem<PlaceBlockEvent> {
        Places(HeatTracker heat) {
            super(PlaceBlockEvent.class, heat, true);
        }
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer, E event) {
        if (event.isCancelled()) {
            return;
        }
        Vector3i target = event instanceof BreakBlockEvent b ? b.getTargetBlock()
                : event instanceof PlaceBlockEvent p ? p.getTargetBlock() : null;
        if (target == null) {
            return;
        }
        World world = store.getExternalData().getWorld();
        if (world != null) {
            heat.record(world.getName(), target.x, target.z, place);
        }
    }
}
