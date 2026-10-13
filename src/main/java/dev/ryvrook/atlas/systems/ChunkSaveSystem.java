package dev.ryvrook.atlas.systems;

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
import dev.ryvrook.atlas.tiles.TileService;

/**
 * Marks map tiles dirty when the engine saves a chunk. ChunkSavingSystems invokes the event on
 * the chunk entity in the chunk store, so this is registered on the chunk store registry.
 */
public final class ChunkSaveSystem extends EntityEventSystem<ChunkStore, ChunkSaveEvent> {
    private final TileService tiles;

    public ChunkSaveSystem(TileService tiles) {
        super(ChunkSaveEvent.class);
        this.tiles = tiles;
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
            tiles.chunkSaved(world.getName(), wc.getX(), wc.getZ());
        }
    }
}
