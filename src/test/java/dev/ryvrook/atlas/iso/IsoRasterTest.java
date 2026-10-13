package dev.ryvrook.atlas.iso;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsoRasterTest {
    private static final int STONE = 1;
    private static final int LEAF = 2;
    private static final int TRUNK = 3;
    private static final int RED = 4;

    private static Palette palette() {
        Palette p = Palette.synthetic(8);
        p.base[LEAF] = 0x20d020;
        p.leaf[LEAF] = true;
        p.base[TRUNK] = 0x8a4a10;
        p.trunk[TRUNK] = true;
        p.base[RED] = 0xe02020;
        return p;
    }

    /** A flat square of one chunk at height 10. */
    private static Columns flat(int w) {
        Columns a = new Columns(w, 0, 0);
        for (int i = 0; i < w * w; i++) {
            a.ground(i, 10, STONE);
        }
        return a;
    }

    private static int count(IsoRaster.Image img, java.util.function.IntPredicate test) {
        int n = 0;
        for (int p : img.argb()) {
            if ((p >>> 24) != 0 && test.test(p & 0xFFFFFF)) {
                n++;
            }
        }
        return n;
    }

    private static boolean green(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        return g > r + 40 && g > b + 40;
    }

    private static boolean red(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        return r > g + 60;
    }

    private static boolean brown(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        return r > g + 20 && g > b + 10 && r < 200;
    }

    @Test
    void sameInputGivesSamePixels() {
        Columns a = flat(32);
        a.ground(5 * 32 + 7, 14, RED);
        IsoRaster.Image one = IsoRaster.render(a, palette(), 2, 90);
        IsoRaster.Image two = IsoRaster.render(a, palette(), 2, 90);
        assertEquals(one.width(), two.width());
        assertEquals(one.height(), two.height());
        assertArrayEquals(one.argb(), two.argb());
        byte[] p1 = PngEncoder.encode(one.argb(), one.width(), one.height());
        byte[] p2 = PngEncoder.encode(two.argb(), two.width(), two.height());
        assertArrayEquals(p1, p2);
    }

    @Test
    void cropFitsAFlatSquareExactly() {
        // Scale 1 draws a block top 4 pixels wide and 2 high. 32 columns span 2 * 32 * 2 pixels across.
        IsoRaster.Image img = IsoRaster.render(flat(32), palette(), 1, 0);
        assertEquals(128, img.width());
        // 62 rows of half steps from the back corner to the front one, then the 12 block skirt of the front corner.
        assertEquals(62 + 12 * 2 + 2, img.height());
        assertEquals(32 * 32, img.drawn());
        // Every edge pixel row and column holds something, so nothing was left uncropped.
        boolean top = false;
        boolean left = false;
        for (int x = 0; x < img.width(); x++) {
            top |= (img.argb()[x] >>> 24) != 0;
        }
        for (int y = 0; y < img.height(); y++) {
            left |= (img.argb()[y * img.width()] >>> 24) != 0;
        }
        assertTrue(top);
        assertTrue(left);
    }

    @Test
    void edgeColumnsDropAFixedSkirtAndNotToTheBottom() {
        int w = 32;
        Columns a = flat(w);
        int front = (w - 1) * w + (w - 1);
        a.ground(front, 60, RED);
        IsoRaster.Image img = IsoRaster.render(a, palette(), 1, 0);
        int reds = count(img, IsoRasterTest::red);
        // Top 8 pixels and two faces of 4 pixels per block, for the 12 block skirt only.
        assertTrue(reds > 0);
        assertTrue(reds <= 8 + IsoRaster.SKIRT * 8, "red pixels " + reds);
        // A skirt to the floor would have drawn the full 60 blocks.
        assertTrue(reds < 8 + 40 * 8);
    }

    @Test
    void canopyDropsAtMostThreeBlocksAndKeepsItsTrunk() {
        int w = 32;
        Columns a = flat(w);
        int i = 16 * w + 16;
        a.flags[i] = Columns.PRESENT | Columns.CANOPY;
        a.extra[i] = LEAF;
        a.canopyTop[i] = 40;
        a.canopyBottom[i] = 20;
        IsoRaster.Image img = IsoRaster.render(a, palette(), 1, 0);
        int leaves = count(img, IsoRasterTest::green);
        assertTrue(leaves > 0);
        assertTrue(leaves <= 8 + IsoRaster.CANOPY_CAP * 8, "leaf pixels " + leaves);
        assertEquals(0, count(img, IsoRasterTest::brown));

        a.flags[i] |= Columns.TRUNK;
        a.trunk[i] = TRUNK;
        IsoRaster.Image withTrunk = IsoRaster.render(a, palette(), 1, 0);
        assertTrue(count(withTrunk, IsoRasterTest::brown) > 0);
        assertTrue(count(withTrunk, IsoRasterTest::green) <= 8 + IsoRaster.CANOPY_CAP * 8);
    }

    @Test
    void missingColumnsBecomeAGreyFloor() {
        int w = 32;
        Columns a = new Columns(w, 0, 0);
        for (int z = 0; z < w; z++) {
            for (int x = 0; x < w / 2; x++) {
                a.ground(z * w + x, 10, STONE);
            }
        }
        IsoRaster.Image img = IsoRaster.render(a, palette(), 1, 0);
        assertEquals(w * w / 2, img.missing());
        int grey = IsoRaster.shade(IsoRaster.MISSING_GREY, 0.42f + 0.62f * (float) (0.85 / Math.sqrt(0.09 + 0.7225 + 0.2025)));
        boolean found = false;
        for (int p : img.argb()) {
            found |= (p & 0xFFFFFF) == grey && (p >>> 24) == 255;
        }
        assertTrue(found);
    }

    @Test
    void azimuthsTurnTheSquare() {
        int w = 32;
        Columns a = flat(w);
        a.ground(0, 30, RED);
        int[] zero = IsoRaster.render(a, palette(), 1, 0).argb();
        int[] ninety = IsoRaster.render(a, palette(), 1, 90).argb();
        assertFalse(Arrays.equals(zero, ninety));
        for (int az : new int[]{0, 90, 180, 270}) {
            int[] perm = IsoRaster.rotation(w, az);
            int[] sorted = perm.clone();
            Arrays.sort(sorted);
            for (int k = 0; k < sorted.length; k++) {
                assertEquals(k, sorted[k]);
            }
        }
        // From the south east the north west corner is at the back, from the north west it is at the front.
        assertEquals(0, IsoRaster.rotation(w, 0)[0]);
        assertEquals(w * w - 1, IsoRaster.rotation(w, 180)[0]);
    }

    @Test
    void pngDecodesToTheSamePixels() throws Exception {
        Columns a = flat(32);
        a.waterTop[40] = 12;
        IsoRaster.Image img = IsoRaster.render(a, palette(), 2, 270);
        byte[] png = PngEncoder.encode(img.argb(), img.width(), img.height());
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(img.width(), back.getWidth());
        assertEquals(img.height(), back.getHeight());
        for (int y = 0; y < img.height(); y += 7) {
            for (int x = 0; x < img.width(); x += 5) {
                int want = img.argb()[y * img.width() + x];
                int got = back.getRGB(x, y);
                if ((want >>> 24) == 0) {
                    assertEquals(0, got >>> 24);
                } else {
                    assertEquals(want, got);
                }
            }
        }
    }
    @Test
    void cropReusesTheCanvasAndClearsTheRest() {
        IsoRaster.Image img = IsoRaster.render(flat(32), palette(), 1, 0);
        assertTrue(img.argb().length >= img.width() * img.height());
        for (int k = img.width() * img.height(); k < img.argb().length; k++) {
            assertEquals(0, img.argb()[k]);
        }
        // The front corner pixel of the bottom row is drawn, so rows were moved and not cut short.
        assertTrue((img.argb()[(img.height() - 1) * img.width() + img.width() / 2 - 1] >>> 24) != 0);
    }
}
