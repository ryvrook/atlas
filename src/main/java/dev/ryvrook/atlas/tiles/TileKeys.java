package dev.ryvrook.atlas.tiles;

import java.util.ArrayList;
import java.util.List;

/**
 * Key and coordinate math shared by the explored index and the tile service. Chunk and tile keys
 * pack x in the high 32 bits and z in the low 32 bits, the same layout as DominionSnapshot.chunkKey.
 * Nothing here touches the engine, so it is plain to test.
 */
public final class TileKeys {
    public static final int MIN_ZOOM = -2;
    public static final int MAX_ZOOM = 4;
    /** Tile side in pixels. */
    public static final int TILE = 256;
    /** Chunks along one side of a zoom 0 tile. */
    public static final int BASE_CHUNKS = 16;

    private TileKeys() {
    }

    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int x(long key) {
        return (int) (key >> 32);
    }

    public static int z(long key) {
        return (int) key;
    }

    /** Chunks along one side of a tile at the zoom. Zoom 0 is 16, each level up doubles and each level down halves. */
    public static int chunksPerTile(int zoom) {
        return zoom >= 0 ? BASE_CHUNKS << zoom : BASE_CHUNKS >> -zoom;
    }

    /** Pixels per chunk side for a base zoom, 64 at zoom -2, 32 at -1 and 16 at 0. */
    public static int chunkPixels(int zoom) {
        return TILE / chunksPerTile(zoom);
    }

    /** The tile at the zoom that holds the chunk, as a key. */
    public static long tileOf(int zoom, int cx, int cz) {
        int span = chunksPerTile(zoom);
        return key(Math.floorDiv(cx, span), Math.floorDiv(cz, span));
    }

    /** The zoom 0 tile that holds the chunk key. */
    public static long baseTileOfChunk(long chunkKey) {
        return tileOf(0, x(chunkKey), z(chunkKey));
    }

    /**
     * Every tile that shows part of a zoom 0 tile, as [zoom, x, z]. That is the tile itself, its
     * four zoom -1 and sixteen zoom -2 tiles, and one parent at each zoom from 1 to 4.
     */
    public static List<int[]> related(int tx, int tz) {
        List<int[]> out = new ArrayList<>(1 + 4 + 16 + MAX_ZOOM);
        out.add(new int[]{0, tx, tz});
        for (int zoom = -1; zoom >= MIN_ZOOM; zoom--) {
            int n = 1 << -zoom;
            for (int dz = 0; dz < n; dz++) {
                for (int dx = 0; dx < n; dx++) {
                    out.add(new int[]{zoom, tx * n + dx, tz * n + dz});
                }
            }
        }
        for (int zoom = 1; zoom <= MAX_ZOOM; zoom++) {
            out.add(new int[]{zoom, Math.floorDiv(tx, 1 << zoom), Math.floorDiv(tz, 1 << zoom)});
        }
        return out;
    }

    /** Chunk keys inside a circle of radius chunks around the chunk that holds block x and z. */
    public static List<Long> chunksInRadius(int blockX, int blockZ, int radius) {
        int cx = Math.floorDiv(blockX, 32);
        int cz = Math.floorDiv(blockZ, 32);
        List<Long> out = new ArrayList<>();
        long r2 = (long) radius * radius;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if ((long) dx * dx + (long) dz * dz <= r2) {
                    out.add(key(cx + dx, cz + dz));
                }
            }
        }
        return out;
    }

    /** Cache key of one tile. */
    public static String tileKey(String world, int zoom, int x, int z) {
        return world + "|" + zoom + "|" + x + "|" + z;
    }

    /**
     * A world name turned into a safe folder name. Anything but letters, digits, dot, dash and underscore
     * becomes an underscore. A name that had to be changed gets a short hash of the original appended,
     * so two worlds such as "a b" and "a_b" never share a folder. Plain names stay as they are.
     */
    public static String safe(String world) {
        String s = world.replaceAll("[^A-Za-z0-9_.-]", "_");
        // Dots alone would climb out of the tile directory.
        if (s.isEmpty() || s.chars().allMatch(c -> c == '.')) {
            s = "_" + s.length();
        }
        return s.equals(world) ? s : s + "-" + shortHash(world);
    }

    /** The first eight hex digits of the SHA-256 of a name. */
    private static String shortHash(String name) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(name.hashCode());
        }
    }
}
