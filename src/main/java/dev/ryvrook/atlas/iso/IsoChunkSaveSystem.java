package dev.ryvrook.atlas.iso;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ecs.ChunkSaveEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/** Drops cached isometric views that hold a chunk the engine saved. Registered on the chunk store registry. */
public final class IsoChunkSaveSystem extends EntityEventSystem<ChunkStore, ChunkSaveEvent> {
    private final IsoService iso;

    public IsoChunkSaveSystem(IsoService iso) {
        super(ChunkSaveEvent.class);
        this.iso = iso;
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(int index, ArchetypeChunk<ChunkStore> chunk, Store<ChunkStore> store,
                       CommandBuffer<ChunkStore> commandBuffer, ChunkSaveEvent event) {
        if (event.isCancelled()) {
            return;
        }
        WorldChunk wc = event.getChunk();
        if (wc == null) {
            return;
        }
        World world = store.getExternalData().getWorld();
        if (world != null) {
            iso.chunkSaved(world.getName(), wc.getX(), wc.getZ());
        }
    }
}
