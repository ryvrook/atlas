package dev.ryvrook.atlas.tiles;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TileKeysTest {
    @Test
    void keysRoundTripIncludingNegatives() {
        int[][] cases = {{0, 0}, {1, -1}, {-1, 1}, {Integer.MIN_VALUE, Integer.MAX_VALUE}, {-123456, 789}};
        for (int[] c : cases) {
            long k = TileKeys.key(c[0], c[1]);
            assertEquals(c[0], TileKeys.x(k));
            assertEquals(c[1], TileKeys.z(k));
        }
        assertEquals(dev.ryvrook.atlas.data.DominionSnapshot.chunkKey(-5, 7), TileKeys.key(-5, 7));
    }

    @Test
    void chunksPerTileAndPixels() {
        assertEquals(4, TileKeys.chunksPerTile(-2));
        assertEquals(8, TileKeys.chunksPerTile(-1));
        assertEquals(16, TileKeys.chunksPerTile(0));
        assertEquals(256, TileKeys.chunksPerTile(4));
        assertEquals(64, TileKeys.chunkPixels(-2));
        assertEquals(32, TileKeys.chunkPixels(-1));
        assertEquals(16, TileKeys.chunkPixels(0));
    }

    @Test
    void tileOfFloorsNegativeChunks() {
        assertEquals(TileKeys.key(-1, -1), TileKeys.tileOf(0, -1, -16));
        assertEquals(TileKeys.key(-2, 0), TileKeys.tileOf(0, -17, 15));
        assertEquals(TileKeys.key(0, 0), TileKeys.tileOf(0, 15, 0));
        assertEquals(TileKeys.key(-1, 0), TileKeys.tileOf(4, -1, 255));
        assertEquals(TileKeys.key(-1, 3), TileKeys.tileOf(-2, -4, 12));
        assertEquals(TileKeys.key(-1, -1), TileKeys.baseTileOfChunk(TileKeys.key(-3, -3)));
    }

    @Test
    void relatedCoversChildrenAndParents() {
        List<int[]> r = TileKeys.related(-3, 5);
        assertEquals(1 + 4 + 16 + 4, r.size());
        Set<String> seen = new HashSet<>();
        for (int[] t : r) {
            seen.add(t[0] + "/" + t[1] + "/" + t[2]);
        }
        assertTrue(seen.contains("0/-3/5"));
        assertTrue(seen.contains("-1/-6/10"));
        assertTrue(seen.contains("-1/-5/11"));
        assertTrue(seen.contains("-2/-12/20"));
        assertTrue(seen.contains("-2/-9/23"));
        assertTrue(seen.contains("1/-2/2"));
        assertTrue(seen.contains("4/-1/0"));
        for (int[] t : r) {
            if (t[0] < 0) {
                // Every child lies inside the zoom 0 tile.
                int span = 1 << -t[0];
                assertEquals(-3, Math.floorDiv(t[1], span));
                assertEquals(5, Math.floorDiv(t[2], span));
            }
        }
    }

    @Test
    void radiusIsACircleAroundTheBlockChunk() {
        List<Long> r0 = TileKeys.chunksInRadius(-1, 31, 0);
        assertEquals(List.of(TileKeys.key(-1, 0)), r0);
        List<Long> r2 = TileKeys.chunksInRadius(64, 64, 2);
        assertEquals(13, r2.size());
        assertTrue(r2.contains(TileKeys.key(4, 2)));
        assertFalse(r2.contains(TileKeys.key(4, 4)));
    }

    @Test
    void safeNamesNeverClimbOut() {
        assertEquals("default", TileKeys.safe("default"));
        assertEquals("instance-Portal_1.x", TileKeys.safe("instance-Portal_1.x"));
        for (String name : new String[]{"a/b c", "..", "", ".", "instance-Portal 1"}) {
            String s = TileKeys.safe(name);
            org.junit.jupiter.api.Assertions.assertTrue(s.matches("[A-Za-z0-9_.-]+") && !s.chars().allMatch(c -> c == '.'), s);
            org.junit.jupiter.api.Assertions.assertTrue(!s.contains("/") && !s.equals("..") && !s.equals("."), s);
        }
        org.junit.jupiter.api.Assertions.assertTrue(TileKeys.safe("a/b c").startsWith("a_b_c-"));
        org.junit.jupiter.api.Assertions.assertTrue(TileKeys.safe("..").startsWith("_2-"));
    }

    @Test
    void changedNamesNeverShareAFolder() {
        org.junit.jupiter.api.Assertions.assertNotEquals(TileKeys.safe("a b"), TileKeys.safe("a_b"));
        org.junit.jupiter.api.Assertions.assertNotEquals(TileKeys.safe("a b"), TileKeys.safe("a/b"));
        assertEquals(TileKeys.safe("a b"), TileKeys.safe("a b"));
    }

    @Test
    void tileNamesParse() {
        long[] xz = TileService.parseTileName("-3_12");
        assertEquals(-3, xz[0]);
        assertEquals(12, xz[1]);
        assertEquals(null, TileService.parseTileName("abc"));
        assertEquals(null, TileService.parseTileName("1_x"));
    }

    @Test
    void etagIsStrongAndStable() {
        byte[] a = {1, 2, 3};
        String e = TileService.etag(a);
        assertTrue(e.startsWith("\"") && e.endsWith("\""));
        assertEquals(34, e.length());
        assertEquals(e, TileService.etag(new byte[]{1, 2, 3}));
        assertFalse(e.equals(TileService.etag(new byte[]{1, 2, 4})));
    }
}
