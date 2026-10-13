package dev.ryvrook.atlas.tiles;

import com.hypixel.hytale.protocol.packets.worldmap.MapImage;

/**
 * Turns a world map chunk image into ARGB pixels.
 *
 * <p>A MapImage has a width, a height, an int palette, a bitsPerIndex byte and packedIndices bytes.
 * Palette entries are RGBA, with red in bits 24 to 31, green in 16 to 23, blue in 8 to 15 and
 * alpha in 0 to 7. bitsPerIndex is 4, 8, 12 or 16 depending on the palette size. Index i sits at
 * bit offset i times bitsPerIndex, least significant bit first, and spills into the following
 * bytes when it crosses a byte boundary. Pixels are row major, so index z times width plus x.
 * The default world map provider draws three pixels per block, so a 32 block chunk is a 96 by 96
 * image. This layout held on server 0.6.8 and 0.7.0.
 */
public final class MapImageDecoder {
    private MapImageDecoder() {
    }

    /** Decodes to ARGB, or returns null when the image is malformed. */
    public static int[] decode(MapImage img) {
        if (img == null) {
            return null;
        }
        return decode(img.width, img.height, img.palette, img.bitsPerIndex & 0xFF, img.packedIndices);
    }

    /** Decodes raw image fields to ARGB, or returns null when they are malformed. */
    public static int[] decode(int w, int h, int[] palette, int bits, byte[] packed) {
        if (w <= 0 || h <= 0 || palette == null || packed == null || bits <= 0 || bits > 31
                || (long) w * h > Integer.MAX_VALUE) {
            return null;
        }
        int[] argb = new int[palette.length];
        for (int i = 0; i < palette.length; i++) {
            int rgba = palette[i];
            argb[i] = ((rgba & 0xFF) << 24) | (rgba >>> 8);
        }
        int n = w * h;
        if ((long) n * bits > (long) packed.length * 8) {
            return null;
        }
        int[] out = new int[n];
        int mask = (1 << bits) - 1;
        for (int i = 0; i < n; i++) {
            long bitOffset = (long) i * bits;
            int byteIndex = (int) (bitOffset >>> 3);
            int shift = (int) (bitOffset & 7);
            int idx;
            if (shift + bits <= 8) {
                idx = ((packed[byteIndex] & 0xFF) >>> shift) & mask;
            } else {
                idx = 0;
                int got = 0;
                int remaining = bits;
                while (remaining > 0) {
                    int take = Math.min(8 - shift, remaining);
                    idx |= (((packed[byteIndex] & 0xFF) >>> shift) & ((1 << take) - 1)) << got;
                    got += take;
                    remaining -= take;
                    byteIndex++;
                    shift = 0;
                }
            }
            out[i] = idx < argb.length ? argb[idx] : 0;
        }
        return out;
    }

    /**
     * A 64 bit FNV-1a hash over the size, palette and packed indices of an image, used to tell
     * whether a chunk picture changed without keeping the picture. Returns 0 for null.
     */
    public static long hash(MapImage img) {
        if (img == null) {
            return 0;
        }
        return hash(img.width, img.height, img.palette, img.bitsPerIndex & 0xFF, img.packedIndices);
    }

    public static long hash(int w, int h, int[] palette, int bits, byte[] packed) {
        long x = 0xcbf29ce484222325L;
        x = mix(x, w);
        x = mix(x, h);
        x = mix(x, bits);
        if (palette != null) {
            x = mix(x, palette.length);
            for (int p : palette) {
                x = mix(x, p);
            }
        }
        if (packed != null) {
            x = mix(x, packed.length);
            for (byte b : packed) {
                x = (x ^ (b & 0xFF)) * 0x100000001b3L;
            }
        }
        return x;
    }

    private static long mix(long x, int v) {
        for (int i = 0; i < 4; i++) {
            x = (x ^ ((v >>> (i * 8)) & 0xFF)) * 0x100000001b3L;
        }
        return x;
    }

    /** Box filters an ARGB image to size by size, weighting colour by alpha. */
    public static int[] scale(int[] src, int w, int h, int size) {
        if (w == size && h == size) {
            return src;
        }
        int[] out = new int[size * size];
        for (int oy = 0; oy < size; oy++) {
            int y0 = oy * h / size;
            int y1 = Math.max(y0 + 1, (oy + 1) * h / size);
            for (int ox = 0; ox < size; ox++) {
                int x0 = ox * w / size;
                int x1 = Math.max(x0 + 1, (ox + 1) * w / size);
                long a = 0, r = 0, g = 0, b = 0;
                int count = 0;
                for (int y = y0; y < y1 && y < h; y++) {
                    for (int x = x0; x < x1 && x < w; x++) {
                        int c = src[y * w + x];
                        int ca = c >>> 24;
                        a += ca;
                        r += (long) ((c >> 16) & 0xFF) * ca;
                        g += (long) ((c >> 8) & 0xFF) * ca;
                        b += (long) (c & 0xFF) * ca;
                        count++;
                    }
                }
                out[oy * size + ox] = pack(a, r, g, b, count);
            }
        }
        return out;
    }

    /** Halves a square ARGB image with a two by two box filter. */
    public static int[] half(int[] src, int size) {
        int hs = size / 2;
        int[] out = new int[hs * hs];
        for (int y = 0; y < hs; y++) {
            for (int x = 0; x < hs; x++) {
                long a = 0, r = 0, g = 0, b = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int c = src[(y * 2 + dy) * size + x * 2 + dx];
                        int ca = c >>> 24;
                        a += ca;
                        r += (long) ((c >> 16) & 0xFF) * ca;
                        g += (long) ((c >> 8) & 0xFF) * ca;
                        b += (long) (c & 0xFF) * ca;
                    }
                }
                out[y * hs + x] = pack(a, r, g, b, 4);
            }
        }
        return out;
    }

    private static int pack(long a, long r, long g, long b, int count) {
        if (a == 0 || count == 0) {
            return 0;
        }
        int oa = (int) (a / count);
        int or = (int) (r / a);
        int og = (int) (g / a);
        int ob = (int) (b / a);
        return (oa << 24) | (or << 16) | (og << 8) | ob;
    }
}
