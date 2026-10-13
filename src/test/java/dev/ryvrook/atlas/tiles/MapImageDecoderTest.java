package dev.ryvrook.atlas.tiles;

import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MapImageDecoderTest {
    private static final int RED = 0xFF0000FF;
    private static final int GREEN = 0x00FF00FF;
    private static final int HALF_BLUE = 0x0000FF80;

    @Test
    void decodesFourBitIndicesLeastSignificantFirst() {
        // Pixels 0, 1, 2, 1 in a 2 by 2 image at 4 bits each.
        byte[] packed = {(byte) 0x10, (byte) 0x12};
        int[] out = MapImageDecoder.decode(2, 2, new int[]{RED, GREEN, HALF_BLUE}, 4, packed);
        assertArrayEquals(new int[]{0xFFFF0000, 0xFF00FF00, 0x800000FF, 0xFF00FF00}, out);
    }

    @Test
    void decodesTwelveBitIndicesAcrossBytes() {
        int[] palette = new int[300];
        palette[1] = RED;
        palette[299] = GREEN;
        // Index 1 then 299 (0x12B) at 12 bits each, packed least significant bit first.
        long bits = 1L | (299L << 12);
        byte[] packed = {(byte) bits, (byte) (bits >> 8), (byte) (bits >> 16)};
        int[] out = MapImageDecoder.decode(2, 1, palette, 12, packed);
        assertArrayEquals(new int[]{0xFFFF0000, 0xFF00FF00}, out);
    }

    @Test
    void indexOutsideThePaletteIsTransparent() {
        int[] out = MapImageDecoder.decode(1, 1, new int[]{RED}, 8, new byte[]{5});
        assertArrayEquals(new int[]{0}, out);
    }

    @Test
    void malformedImagesGiveNull() {
        assertNull(MapImageDecoder.decode(2, 2, new int[]{RED}, 8, new byte[3]));
        assertNull(MapImageDecoder.decode(0, 2, new int[]{RED}, 8, new byte[4]));
        assertNull(MapImageDecoder.decode(1, 1, null, 8, new byte[1]));
        assertNull(MapImageDecoder.decode(1, 1, new int[]{RED}, 0, new byte[1]));
        assertNull(MapImageDecoder.decode((MapImage) null));
    }

    @Test
    void mapImageAndRawFieldsAgree() {
        MapImage img = new MapImage(2, 2, new int[]{RED, GREEN, HALF_BLUE}, (byte) 4, new byte[]{(byte) 0x10, (byte) 0x12});
        assertArrayEquals(MapImageDecoder.decode(2, 2, img.palette, 4, img.packedIndices), MapImageDecoder.decode(img));
        assertEquals(MapImageDecoder.hash(img), MapImageDecoder.hash(2, 2, img.palette, 4, img.packedIndices));
    }

    @Test
    void hashChangesWithAnyPixel() {
        int[] palette = {RED, GREEN};
        long a = MapImageDecoder.hash(2, 2, palette, 4, new byte[]{0x10, 0x10});
        long b = MapImageDecoder.hash(2, 2, palette, 4, new byte[]{0x10, 0x11});
        long c = MapImageDecoder.hash(2, 2, new int[]{RED, HALF_BLUE}, 4, new byte[]{0x10, 0x10});
        assertNotEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a, MapImageDecoder.hash(2, 2, palette.clone(), 4, new byte[]{0x10, 0x10}));
    }

    @Test
    void scaleWeightsColourByAlpha() {
        // One opaque red and one transparent pixel average to half alpha pure red.
        int[] src = {0xFFFF0000, 0x0000FF00};
        int[] out = MapImageDecoder.scale(src, 2, 1, 1);
        assertEquals(0x7FFF0000, out[0]);
    }

    @Test
    void halfAveragesTwoByTwo() {
        int[] src = {0xFF000000, 0xFF000000, 0xFF0000FF, 0xFF0000FF};
        int[] out = MapImageDecoder.half(src, 2);
        assertEquals(0xFF00007F, out[0]);
    }
}
