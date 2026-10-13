package dev.ryvrook.atlas.iso;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * A small PNG writer for ARGB pixels, 8 bit RGBA with the Sub filter on every row and the fastest
 * deflate level. Isometric views are mostly flat colour runs, which the Sub filter turns into zeros,
 * so the files stay small while encoding takes a fraction of the default ImageIO time.
 */
final class PngEncoder {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private PngEncoder() {
    }

    static byte[] encode(int[] argb, int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, width * height / 3));
            out.write(SIGNATURE);
            byte[] ihdr = new byte[13];
            putInt(ihdr, 0, width);
            putInt(ihdr, 4, height);
            ihdr[8] = 8;
            ihdr[9] = 6;
            chunk(out, "IHDR", ihdr, ihdr.length);
            ByteArrayOutputStream idat = new ByteArrayOutputStream(Math.max(1024, width * height / 4));
            Deflater def = new Deflater(Deflater.BEST_SPEED);
            try (DeflaterOutputStream dos = new DeflaterOutputStream(idat, def, 1 << 16)) {
                byte[] row = new byte[1 + width * 4];
                row[0] = 1;
                for (int y = 0; y < height; y++) {
                    int base = y * width;
                    int pr = 0;
                    int pg = 0;
                    int pb = 0;
                    int pa = 0;
                    for (int x = 0; x < width; x++) {
                        int p = argb[base + x];
                        int a = (p >>> 24) & 255;
                        int r = (p >> 16) & 255;
                        int g = (p >> 8) & 255;
                        int b = p & 255;
                        if (a == 0) {
                            r = g = b = 0;
                        }
                        int o = 1 + x * 4;
                        row[o] = (byte) (r - pr);
                        row[o + 1] = (byte) (g - pg);
                        row[o + 2] = (byte) (b - pb);
                        row[o + 3] = (byte) (a - pa);
                        pr = r;
                        pg = g;
                        pb = b;
                        pa = a;
                    }
                    dos.write(row);
                }
            } finally {
                def.end();
            }
            byte[] data = idat.toByteArray();
            chunk(out, "IDAT", data, data.length);
            chunk(out, "IEND", new byte[0], 0);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PNG encoding failed", e);
        }
    }

    private static void chunk(OutputStream out, String type, byte[] data, int len) throws IOException {
        byte[] head = new byte[4];
        putInt(head, 0, len);
        out.write(head);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data, 0, len);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data, 0, len);
        putInt(head, 0, (int) crc.getValue());
        out.write(head);
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }
}
