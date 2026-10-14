package dev.ryvrook.atlas.timeline;

import dev.ryvrook.atlas.tiles.TileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotJobTest {
    @TempDir
    Path dir;

    private static byte[] png(int argb) throws Exception {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                img.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static void tile(Path tiles, int zoom, int x, int z, byte[] data) throws Exception {
        Path f = tiles.resolve(Integer.toString(zoom)).resolve(x + "_" + z + ".png");
        Files.createDirectories(f.getParent());
        Files.write(f, data);
    }

    private static Manifest run(SnapshotJob j) throws Exception {
        j.list();
        while (!j.finished()) {
            j.step(4);
        }
        return j.result(10L);
    }

    @Test
    void aTileMissingFromTheFolderIsCarriedOverAndNotCountedAsChanged() throws Exception {
        BlobStore blobs = new BlobStore(dir.resolve("blobs"));
        byte[] a = png(0xFF112233);
        byte[] b = png(0xFF445566);
        String ha = BlobStore.hash(a);
        String hb = BlobStore.hash(b);
        blobs.put(ha, a);
        blobs.put(hb, b);
        Path tiles = dir.resolve("tiles");
        tile(tiles, 0, 0, 0, a);
        Manifest prev = new Manifest("w", "2026-10-13T22", 1L, null, false, 0, 0,
                Map.of("0/0/0", ha, "-1/0/0", hb, "-2/1/1", hb, "1/0/0", hb), List.of());
        Manifest m = run(new SnapshotJob("w", "2026-10-14T04", 0L, tiles, blobs, "2026-10-13T22", prev, null,
                -2, 4));
        assertTrue(m.complete());
        assertEquals(4, m.tiles().size());
        assertEquals(3, m.carried());
        assertEquals(hb, m.tiles().get("-1/0/0"));
        assertEquals(hb, m.tiles().get("1/0/0"));
        assertEquals(List.of(), m.changed(), "the zoom 0 tile is the same and carried tiles never count");
        Manifest back = Manifest.fromJson(m.toJson());
        assertEquals(3, back.carried());
        assertTrue(back.complete());
        assertEquals(3, (int) m.summary().get("carried").getAsInt());
    }

    @Test
    void aCarriedTileWhoseImageIsGoneIsLeftOut() throws Exception {
        BlobStore blobs = new BlobStore(dir.resolve("blobs"));
        Manifest prev = new Manifest("w", "2026-10-13T22", 1L, null, false, 0, 0,
                Map.of("-1/0/0", "d".repeat(64)), List.of());
        Manifest m = run(new SnapshotJob("w", "2026-10-14T04", 0L, dir.resolve("none"), blobs, "2026-10-13T22", prev,
                null, -2, 4));
        assertEquals(0, m.tiles().size());
        assertEquals(0, m.carried());
    }

    @Test
    void missingUpperTilesAreDrawnFromFreshTilesBelow() throws Exception {
        BlobStore blobs = new BlobStore(dir.resolve("blobs"));
        byte[] a = png(0xFF112233);
        byte[] b = png(0xFF445566);
        byte[] old = png(0xFF000000);
        String hold = BlobStore.hash(old);
        blobs.put(hold, old);
        Path tiles = dir.resolve("tiles");
        tile(tiles, 0, 0, 0, a);
        tile(tiles, 0, 1, 0, b);
        Manifest prev = new Manifest("w", "2026-10-13T22", 1L, null, false, 0, 0,
                Map.of("1/0/0", hold, "0/0/0", hold, "0/1/0", hold), List.of());
        Set<String> explored = Set.of("0/0/0", "0/1/0");
        SnapshotJob.Explored ex = (zoom, x, z) -> zoom == 0 ? explored.contains(Manifest.key(zoom, x, z))
                : x == 0 && z == 0;
        Manifest m = run(new SnapshotJob("w", "2026-10-14T04", 0L, tiles, blobs, "2026-10-13T22", prev, null,
                -2, 4, ex));
        byte[] expected = TileService.composeUpper(new byte[][]{a, b, null, null});
        assertNotNull(expected);
        assertEquals(BlobStore.hash(expected), m.tiles().get("1/0/0"));
        // Zoom 2 to 4 follow from the tile just drawn.
        assertTrue(m.tiles().containsKey("2/0/0"));
        assertTrue(m.tiles().containsKey("4/0/0"));
        assertEquals(4, m.composed());
        assertEquals(0, m.carried());
        assertTrue(m.changed().contains("1/0/0"));
        assertNotEquals(hold, m.tiles().get("1/0/0"));
        assertArrayEquals(expected, blobs.read(m.tiles().get("1/0/0")));
    }

    @Test
    void anUpperTileWithAnUnknownQuarterIsCarriedOver() throws Exception {
        BlobStore blobs = new BlobStore(dir.resolve("blobs"));
        byte[] a = png(0xFF112233);
        byte[] old = png(0xFF000000);
        String hold = BlobStore.hash(old);
        blobs.put(hold, old);
        Path tiles = dir.resolve("tiles");
        tile(tiles, 0, 0, 0, a);
        // 0/1/0 is explored but not in the folder, it is being drawn again.
        Manifest prev = new Manifest("w", "2026-10-13T22", 1L, null, false, 0, 0,
                Map.of("1/0/0", hold, "0/1/0", hold), List.of());
        SnapshotJob.Explored ex = (zoom, x, z) -> true;
        Manifest m = run(new SnapshotJob("w", "2026-10-14T04", 0L, tiles, blobs, "2026-10-13T22", prev, null,
                -2, 4, ex));
        assertEquals(hold, m.tiles().get("1/0/0"));
        assertEquals(hold, m.tiles().get("0/1/0"));
        assertEquals(0, m.composed());
        assertEquals(2, m.carried());
        assertFalse(m.changed().contains("1/0/0"));
    }
}
