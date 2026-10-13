package dev.ryvrook.atlas.tiles;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExploredIndexTest {
    @Test
    void addTracksTilesAtEveryZoom() {
        ExploredIndex ex = new ExploredIndex();
        assertNull(ex.bounds());
        assertTrue(ex.add(-1, 17));
        assertFalse(ex.add(-1, 17));
        assertEquals(1, ex.size());
        assertTrue(ex.hasChunk(-1, 17));
        assertTrue(ex.hasTile(0, -1, 1));
        assertTrue(ex.hasTile(-1, -1, 2));
        assertTrue(ex.hasTile(-2, -1, 4));
        assertTrue(ex.hasTile(4, -1, 0));
        assertFalse(ex.hasTile(0, 0, 1));
        assertFalse(ex.hasTile(5, -1, 0));
        assertArrayEquals(new int[]{-1, 17, -1, 17}, ex.bounds());
    }

    @Test
    void ringNeedsAllEightNeighbours() {
        ExploredIndex ex = new ExploredIndex();
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                if (x != 1 || z != 1) {
                    ex.add(x, z);
                }
            }
        }
        assertFalse(ex.hasRing(0, 0));
        ex.add(1, 1);
        assertTrue(ex.hasRing(0, 0));
        assertFalse(ex.hasRing(1, 1));
    }

    @Test
    void retainOnlyDropsGoneChunksAndRebuildsTiles() {
        ExploredIndex ex = new ExploredIndex();
        ex.add(0, 0);
        ex.add(20, 0);
        ex.add(40, 40);
        long before = ex.sequence();
        // Added after the refresh started, so it survives even though the scan missed it.
        ex.add(100, 100);
        List<Long> removed = ex.retainOnly(Set.of(TileKeys.key(0, 0)), before);
        assertEquals(2, removed.size());
        assertTrue(removed.contains(TileKeys.key(20, 0)));
        assertTrue(removed.contains(TileKeys.key(40, 40)));
        assertTrue(ex.hasChunk(0, 0));
        assertTrue(ex.hasChunk(100, 100));
        assertFalse(ex.hasChunk(20, 0));
        assertFalse(ex.hasTile(0, 1, 0));
        assertFalse(ex.hasTile(0, 2, 2));
        assertTrue(ex.hasTile(0, 0, 0));
        assertTrue(ex.hasTile(0, 6, 6));
        assertArrayEquals(new int[]{0, 0, 100, 100}, ex.bounds());
        assertEquals(2, ex.size());
        // Adding again after a rebuild still feeds the tile sets.
        assertTrue(ex.add(20, 0));
        assertTrue(ex.hasTile(0, 1, 0));
    }

    @Test
    void retainOnlyWithEverythingPresentChangesNothing() {
        ExploredIndex ex = new ExploredIndex();
        ex.add(1, 2);
        ex.add(3, 4);
        List<Long> removed = ex.retainOnly(Set.of(TileKeys.key(1, 2), TileKeys.key(3, 4)), ex.sequence());
        assertTrue(removed.isEmpty());
        assertEquals(2, ex.size());
    }

    @Test
    void tileListsAreCopies() {
        ExploredIndex ex = new ExploredIndex();
        ex.add(0, 0);
        ex.add(16, 0);
        List<Long> t = ex.tiles(0);
        assertEquals(2, t.size());
        t.clear();
        assertEquals(2, ex.tiles(0).size());
        assertEquals(List.of(), ex.tiles(9));
    }
}
