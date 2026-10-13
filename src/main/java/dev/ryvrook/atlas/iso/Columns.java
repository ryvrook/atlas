package dev.ryvrook.atlas.iso;

import java.util.Arrays;

/**
 * Compact records for every block column of a square of chunks, about 27 bytes a column. The column
 * index is z times w plus x, with x and z counted from the north west corner of the square.
 *
 * <p>terrain is the height of the ground, below any plant, leaf canopy or water. A canopy column has
 * leaves at its top, from canopyBottom up to canopyTop, and a trunk column has a trunk block under
 * them. The four ids under terrain describe the ground from its top block down.
 */
final class Columns {
    static final int DEPTH = 4;
    static final byte PRESENT = 1;
    /** A plant or other thin model sits on the terrain, its id in extra. */
    static final byte DECOR = 2;
    /** Leaves at the top, their id in extra. */
    static final byte CANOPY = 4;
    /** A trunk block stands under the canopy, its id in trunk. */
    static final byte TRUNK = 8;

    final int w;
    final int minBlockX;
    final int minBlockZ;
    final byte[] flags;
    final short[] terrain;
    /** Top of the fluid above the terrain, or -1. */
    final short[] waterTop;
    final char[] fluid;
    final int[] tint;
    /** Block ids at terrain, terrain minus 1, minus 2 and minus 3. */
    final char[] ids;
    /** The decor id, or the leaf id of a canopy. */
    final char[] extra;
    final short[] canopyTop;
    final short[] canopyBottom;
    final char[] trunk;

    Columns(int w, int minBlockX, int minBlockZ) {
        this.w = w;
        this.minBlockX = minBlockX;
        this.minBlockZ = minBlockZ;
        int n = w * w;
        flags = new byte[n];
        terrain = new short[n];
        waterTop = new short[n];
        Arrays.fill(waterTop, (short) -1);
        fluid = new char[n];
        tint = new int[n];
        ids = new char[n * DEPTH];
        extra = new char[n];
        canopyTop = new short[n];
        canopyBottom = new short[n];
        trunk = new char[n];
    }

    boolean present(int i) {
        return (flags[i] & PRESENT) != 0;
    }

    boolean has(int i, byte flag) {
        return (flags[i] & flag) != 0;
    }

    /** Bytes held by the records, for status lines. */
    long bytes() {
        long n = (long) w * w;
        return n * (1 + 2 + 2 + 2 + 4 + 2 * DEPTH + 2 + 2 + 2 + 2);
    }

    /** Sets a plain column of ground with the given id stack, for tests and missing sections. */
    void ground(int i, int height, int... stack) {
        flags[i] = PRESENT;
        terrain[i] = (short) height;
        for (int k = 0; k < DEPTH; k++) {
            ids[i * DEPTH + k] = (char) (stack.length == 0 ? 0 : stack[Math.min(k, stack.length - 1)]);
        }
    }
}
