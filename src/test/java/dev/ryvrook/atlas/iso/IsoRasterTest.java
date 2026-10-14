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

    /** A flat height field of side w at height 10. */
    private static int[] field(int w) {
        int[] h = new int[w * w];
        Arrays.fill(h, 10);
        return h;
    }

    @Test
    void flatGroundCastsNoShadow() {
        int[] h = field(16);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(0f, IsoRaster.shadow(h, 16, x, z, 10, 11));
                assertEquals(1f, IsoRaster.topAo(h, 16, x, z, 10));
            }
        }
    }

    @Test
    void aTallColumnShadesTheGroundAwayFromTheLight() {
        // The light comes from the west and the south of the screen grid, so a pillar shades cells to its east and north.
        int w = 32;
        int[] h = field(w);
        int px = 10;
        int pz = 20;
        h[pz * w + px] = 20;
        int top = 21;
        assertEquals(1f, IsoRaster.shadow(h, w, px + 2, pz - 3, 10, top), 1e-6);
        assertTrue(IsoRaster.shadow(h, w, px + 1, pz - 1, 10, top) > 0.5f);
        // On the lit side, and far beyond the reach of a ten block pillar, the ground stays lit.
        assertEquals(0f, IsoRaster.shadow(h, w, px - 2, pz + 3, 10, top));
        assertEquals(0f, IsoRaster.shadow(h, w, px + 12, pz - 18, 10, top));
        // The pillar top itself is lit.
        assertEquals(0f, IsoRaster.shadow(h, w, px, pz, 20, top));
    }

    @Test
    void shadowsHaveASoftEdgeAndAreBounded() {
        int w = 64;
        int[] h = field(w);
        for (int z = 49; z <= 51; z++) {
            for (int x = 4; x <= 6; x++) {
                h[z * w + x] = 60;
            }
        }
        // A fifty block tower still stops at SHADOW_REACH blocks along the ground.
        double rx = -0.30 / Math.hypot(0.30, 0.45);
        double rz = 0.45 / Math.hypot(0.30, 0.45);
        int far = IsoRaster.SHADOW_REACH + 3;
        assertEquals(0f, IsoRaster.shadow(h, w, (int) Math.round(5 - rx * far), (int) Math.round(50 - rz * far), 10, 61));
        assertEquals(1f, IsoRaster.shadow(h, w, (int) Math.round(5 - rx * 10), (int) Math.round(50 - rz * 10), 10, 61), 1e-6);
        // A step just at the height of the ray gives a partial shadow.
        int[] step = field(w);
        step[20 * w + 20] = 11;
        float soft = IsoRaster.shadow(step, w, 20, 19, 10, 12);
        assertTrue(soft > 0f && soft < 1f, "soft " + soft);
    }

    @Test
    void theTileBoundGivesTheSameShadowsAsTheHighestSurface() {
        int w = 96;
        int[] h = field(w);
        java.util.Random rnd = new java.util.Random(7);
        int max = 0;
        for (int k = 0; k < 400; k++) {
            int i = rnd.nextInt(w * w);
            h[i] = 10 + rnd.nextInt(40);
            max = Math.max(max, h[i]);
        }
        int[] reach = IsoRaster.reachTops(h, w);
        int tiles = (w + IsoRaster.TILE - 1) / IsoRaster.TILE;
        for (int z = 0; z < w; z++) {
            for (int x = 0; x < w; x++) {
                int t = h[z * w + x];
                int bound = reach[(z / IsoRaster.TILE) * tiles + x / IsoRaster.TILE];
                assertEquals(IsoRaster.shadow(h, w, x, z, t, max + 1), IsoRaster.shadow(h, w, x, z, t, bound), "at " + x + "," + z);
            }
        }
    }

    @Test
    void groundUnderItsOwnCanopyIsInShadowAndTheCanopyIsLit() {
        int w = 8;
        int[] h = field(w);
        h[3 * w + 3] = 18;
        assertEquals(1f, IsoRaster.shadow(h, w, 3, 3, 10, 19), 1e-6);
        assertEquals(0f, IsoRaster.shadow(h, w, 3, 3, 18, 19));
    }

    @Test
    void ambientOcclusionDarkensCornersMoreThanWalls() {
        int w = 8;
        int[] wall = field(w);
        int[] corner = field(w);
        for (int k = 0; k < w; k++) {
            wall[2 * w + k] = 14;
            corner[2 * w + k] = 14;
            corner[k * w + 2] = 14;
        }
        float foot = IsoRaster.topAo(wall, w, 4, 3, 10);
        float nook = IsoRaster.topAo(corner, w, 3, 3, 10);
        assertTrue(foot < 1f, "foot " + foot);
        assertTrue(nook < foot, "nook " + nook + " foot " + foot);
        assertTrue(nook >= IsoRaster.AO_FLOOR);
        // A column on a ridge takes no occlusion from the lower ground around it.
        assertEquals(1f, IsoRaster.topAo(wall, w, 4, 2, 14));
        // Only two blocks of a higher neighbour count, so a cliff is no darker than a two block step.
        int[] cliff = field(w);
        int[] stepTwo = field(w);
        cliff[2 * w + 4] = 40;
        stepTwo[2 * w + 4] = 12;
        assertEquals(IsoRaster.topAo(stepTwo, w, 4, 3, 10), IsoRaster.topAo(cliff, w, 4, 3, 10));
    }

    @Test
    void shadowKeepsBrightGroundBrightAndCool() {
        int snow = 0xf0f0f4;
        int grass = 0x4a8a2a;
        int s = IsoRaster.shadowed(snow, 1f);
        int g = IsoRaster.shadowed(grass, 1f);
        assertEquals(snow, IsoRaster.shadowed(snow, 0f));
        // Snow in shadow stays lighter than grass in sun and leans blue rather than brown.
        int luma = ((s >> 16) & 255) + ((s >> 8) & 255) + (s & 255);
        int grassLuma = ((grass >> 16) & 255) + ((grass >> 8) & 255) + (grass & 255);
        assertTrue(luma > grassLuma);
        assertTrue((s & 255) >= ((s >> 16) & 255));
        assertTrue(((g >> 8) & 255) > ((g >> 16) & 255), "grass keeps its hue");
    }

    @Test
    void waterTakesItsShadowOnTheSurfaceAndLightensAtTheShore() {
        int w = 32;
        Columns a = flat(w);
        // A pond two blocks deep, its surface level with the bank at 10.
        for (int z = 8; z < 24; z++) {
            for (int x = 8; x < 24; x++) {
                int i = z * w + x;
                a.ground(i, 8, STONE);
                a.waterTop[i] = 10;
            }
        }
        IsoRaster.Image calm = IsoRaster.render(a, palette(), 2, 0);
        // Every cell has the same depth, so only the shore makes some of the water lighter.
        assertTrue(count(calm, IsoRasterTest::blue) > 0);
        assertTrue(brightestBlue(calm) > darkestBlue(calm) + 10);
        // A tall column on the bank, on the light side of the pond, shades the water surface.
        a.ground(20 * w + 7, 26, STONE);
        IsoRaster.Image shaded = IsoRaster.render(a, palette(), 2, 0);
        int dark = darkestBlue(calm) - 4;
        assertTrue(count(shaded, (rgb) -> blue(rgb) && (rgb & 255) < dark) > 0, "shadow on the water surface");
    }

    @Test
    void glowingFluidIsNeverShaded() {
        int w = 32;
        Columns a = flat(w);
        Palette p = palette();
        p.fluid[1] = 0xf94e11;
        p.fluidGlow[1] = true;
        for (int z = 8; z < 24; z++) {
            for (int x = 8; x < 24; x++) {
                int i = z * w + x;
                a.ground(i, 8, STONE);
                a.waterTop[i] = 10;
                a.fluid[i] = 1;
            }
        }
        a.ground(20 * w + 7, 26, STONE);
        IsoRaster.Image img = IsoRaster.render(a, p, 2, 0);
        int lava = 0;
        int other = 0;
        for (int px : img.argb()) {
            int rgb = px & 0xFFFFFF;
            if ((px >>> 24) != 0 && ((rgb >> 16) & 255) > 150 && (rgb & 255) < 60) {
                if (rgb == 0xf94e11) {
                    lava++;
                } else {
                    other++;
                }
            }
        }
        assertTrue(lava > 0);
        assertEquals(0, other, "lava tops keep their own colour");
    }

    private static boolean blue(int rgb) {
        int r = (rgb >> 16) & 255;
        int b = rgb & 255;
        return b > r + 40;
    }

    private static int darkestBlue(IsoRaster.Image img) {
        int min = 255;
        for (int p : img.argb()) {
            if ((p >>> 24) != 0 && blue(p & 0xFFFFFF)) {
                min = Math.min(min, p & 255);
            }
        }
        return min;
    }

    private static int brightestBlue(IsoRaster.Image img) {
        int max = 0;
        for (int p : img.argb()) {
            if ((p >>> 24) != 0 && blue(p & 0xFFFFFF)) {
                max = Math.max(max, p & 255);
            }
        }
        return max;
    }
}
