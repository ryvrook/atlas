package dev.ryvrook.atlas.iso;

/**
 * The isometric painter. Turns column records into ARGB pixels with a 2 to 1 projection, one
 * column per block, drawn in rising depth so nearer columns cover the ones behind them. Each
 * column is drawn from the bottom up, its ground with two visible side faces and a top, then a
 * trunk and a leaf canopy when it has them.
 *
 * <p>With azimuth 0 the viewer looks from the south east, so east runs down to the right and south
 * down to the left. Azimuth 90, 180 and 270 turn the viewer clockwise to the south west, the north
 * west and the north east. The light stays fixed on screen.
 *
 * <p>Three rules keep the picture readable. Side faces at the edge of the area and next to a missing
 * chunk drop a fixed skirt of SKIRT blocks, so the edge reads as a cut instead of a comb. Leaf
 * canopies drop at most CANOPY_CAP blocks, so trees read as crowns rather than pillars, and a
 * column with a trunk under its leaves draws the trunk below the crown. Chunks that are missing,
 * unexplored or outside the render limit are drawn as a flat grey floor.
 */
final class IsoRaster {
    static final int SKIRT = 12;
    static final int CANOPY_CAP = 3;
    static final int MAX_DROP = 48;
    static final int MISSING_GREY = 0x6b6f74;
    private static final int NONE = Integer.MIN_VALUE;

    private static final double LX = -0.30;
    private static final double LY = 0.85;
    private static final double LZ = 0.45;
    private static final double LEN = Math.sqrt(LX * LX + LY * LY + LZ * LZ);
    private static final float TOP = light(LY);
    private static final float SOUTH = light(LZ);
    private static final float EAST = light(LX);

    /**
     * The cropped picture in the first width times height entries of argb, the rest of the array is
     * transparent. drawn and missing count columns.
     */
    record Image(int[] argb, int width, int height, int drawn, int missing) {
    }

    private IsoRaster() {
    }

    private static float light(double d) {
        return (float) Math.min(1.0, 0.42 + 0.62 * Math.max(0, d / LEN));
    }

    /** Source column of every screen cell, for the azimuth. Screen cell index is row times w plus column. */
    static int[] rotation(int w, int azimuth) {
        int[] perm = new int[w * w];
        for (int zz = 0; zz < w; zz++) {
            for (int xx = 0; xx < w; xx++) {
                int x;
                int z;
                switch (azimuth) {
                    case 90 -> {
                        x = w - 1 - zz;
                        z = xx;
                    }
                    case 180 -> {
                        x = w - 1 - xx;
                        z = w - 1 - zz;
                    }
                    case 270 -> {
                        x = zz;
                        z = w - 1 - xx;
                    }
                    default -> {
                        x = xx;
                        z = zz;
                    }
                }
                perm[zz * w + xx] = z * w + x;
            }
        }
        return perm;
    }

    static Image render(Columns a, Palette c, int scale, int azimuth) {
        int w = a.w;
        int n = w * w;
        int u = 2 * scale;
        int v = u;
        int half = u / 2;
        int[] perm = rotation(w, azimuth);
        int[] tsurf = new int[n];
        int[] surf = new int[n];
        int minTerrain = Integer.MAX_VALUE;
        int maxSurf = Integer.MIN_VALUE;
        int[] hist = new int[512];
        int count = 0;
        for (int i = 0; i < n; i++) {
            int s = perm[i];
            if (!a.present(s)) {
                tsurf[i] = surf[i] = NONE;
                continue;
            }
            int g = a.terrain[s];
            int ts = Math.max(g, a.waterTop[s]);
            tsurf[i] = ts;
            surf[i] = a.has(s, Columns.CANOPY) ? Math.max(ts, a.canopyTop[s]) : ts;
            minTerrain = Math.min(minTerrain, g);
            maxSurf = Math.max(maxSurf, surf[i] + 1);
            hist[Math.max(0, Math.min(511, g))]++;
            count++;
        }
        int floorY = 0;
        if (count == 0) {
            minTerrain = 0;
            maxSurf = 1;
        } else {
            int acc = 0;
            for (int y = 0; y < hist.length; y++) {
                acc += hist[y];
                if (acc > count / 50) {
                    floorY = y;
                    break;
                }
            }
        }
        int base = Math.min(minTerrain - SKIRT - 1, floorY - 1);
        int lift = (maxSurf - base + 2) * v;
        int imgW = 2 * w * u;
        int imgH = lift + w * u + u + 2;
        int[] px = new int[imgW * imgH];
        Canvas cv = new Canvas(px, imgW, imgH);
        int drawn = 0;
        int missing = 0;
        int grey = shade(MISSING_GREY, TOP) | 0xFF000000;

        for (int d = 0; d <= 2 * w - 2; d++) {
            int x0 = Math.max(0, d - w + 1);
            int x1 = Math.min(w - 1, d);
            for (int x = x0; x <= x1; x++) {
                int z = d - x;
                int i = z * w + x;
                int cx = (x - z + w - 1) * u + u;
                int row = lift + (x + z) * half + half;
                if (tsurf[i] == NONE) {
                    missing++;
                    cv.rhombus(cx, row - (floorY - base) * v, u, grey);
                    continue;
                }
                drawn++;
                int s = perm[i];
                int south = z + 1 < w ? i + w : -1;
                int east = x + 1 < w ? i + 1 : -1;
                int north = z > 0 ? i - w : -1;
                int west = x > 0 ? i - 1 : -1;
                ground(cv, a, c, s, tsurf, i, south, east, north, west, cx, row, base, u, v);
                if (a.has(s, Columns.CANOPY)) {
                    canopy(cv, a, c, s, surf, i, south, east, north, west, cx, row, base, u, v);
                }
            }
        }
        return cv.crop(drawn, missing);
    }

    private static int drop(int top, int[] heights, int neighbour, int edge, int cap) {
        if (neighbour < 0 || heights[neighbour] == NONE) {
            return edge;
        }
        return Math.max(0, Math.min(top - heights[neighbour], cap));
    }

    private static void ground(Canvas cv, Columns a, Palette c, int s, int[] tsurf, int i, int south, int east, int north,
                               int west, int cx, int row, int base, int u, int v) {
        int t = tsurf[i];
        int g = a.terrain[s];
        int tint = a.tint[s];
        int at = s * Columns.DEPTH;
        int groundId = c.safe(a.ids[at]);
        boolean water = a.waterTop[s] >= 0;
        int wc = water ? c.water(a.fluid[s]) : 0;
        int dropS = drop(t, tsurf, south, SKIRT, MAX_DROP);
        int dropE = drop(t, tsurf, east, SKIRT, MAX_DROP);
        int cy = row - (t - base) * v;
        for (int k = 0; k < Math.max(dropS, dropE); k++) {
            int y = t - k;
            float ao = 1f - Math.min(0.35f, k * 0.03f);
            int cs;
            int ce;
            if (y > g) {
                cs = shade(wc, SOUTH * 0.9f * ao);
                ce = shade(wc, EAST * 0.9f * ao);
            } else {
                int j = g - y;
                int id = c.safe(a.ids[at + Math.min(Columns.DEPTH - 1, j)]);
                float deep = j >= Columns.DEPTH ? 0.8f : 1f;
                if (c.clear[id]) {
                    id = groundId;
                }
                cs = shade(c.south(id, tint), SOUTH * ao * deep);
                ce = shade(c.east(id, tint), EAST * ao * deep);
            }
            int fy = cy + k * v;
            if (k < dropS) {
                cv.faceLeft(cx, fy, u, v, cs | 0xFF000000);
            }
            if (k < dropE) {
                cv.faceRight(cx, fy, u, v, ce | 0xFF000000);
            }
        }
        float slope = slope(t, tsurf, north, west);
        int top;
        if (water) {
            int floor = shade(c.top(groundId, tint), TOP);
            int depth = t - g;
            float alpha = Math.min(0.9f, 0.55f + depth * 0.05f);
            top = shade(mix(floor, wc, alpha), TOP * (1f - Math.min(0.35f, depth * 0.02f)));
        } else {
            top = shade(c.top(groundId, tint), TOP * slope);
        }
        cv.rhombus(cx, cy, u, top | 0xFF000000);
        if (a.has(s, Columns.DECOR) && !water) {
            int lite = mix(c.top(c.safe(a.extra[s]), tint), 0xFFFFFF, 0.25f);
            int sh = Math.max(1, v / 2);
            int sy = cy - sh;
            cv.faceLeft(cx, sy, u, sh, shade(lite, SOUTH) | 0xFF000000);
            cv.faceRight(cx, sy, u, sh, shade(lite, EAST) | 0xFF000000);
            cv.rhombus(cx, sy, u, shade(lite, TOP * slope) | 0xFF000000);
        }
    }

    private static void canopy(Canvas cv, Columns a, Palette c, int s, int[] surf, int i, int south, int east, int north,
                               int west, int cx, int row, int base, int u, int v) {
        int ct = a.canopyTop[s];
        int g = a.terrain[s];
        int tint = a.tint[s];
        int leafId = c.safe(a.extra[s]);
        int lowest = Math.max(a.canopyBottom[s], ct - CANOPY_CAP + 1);
        if (a.has(s, Columns.TRUNK)) {
            int trunkId = c.safe(a.trunk[s]);
            int cs = shade(c.south(trunkId, tint), SOUTH) | 0xFF000000;
            int ce = shade(c.east(trunkId, tint), EAST) | 0xFF000000;
            for (int y = lowest - 1; y > g; y--) {
                int fy = row - (y - base) * v;
                cv.faceLeft(cx, fy, u, v, cs);
                cv.faceRight(cx, fy, u, v, ce);
            }
        }
        int thick = ct - lowest + 1;
        int dropS = Math.min(thick, drop(ct, surf, south, CANOPY_CAP, CANOPY_CAP));
        int dropE = Math.min(thick, drop(ct, surf, east, CANOPY_CAP, CANOPY_CAP));
        int cy = row - (ct - base) * v;
        int ls = c.south(leafId, tint);
        int le = c.east(leafId, tint);
        for (int k = 0; k < Math.max(dropS, dropE); k++) {
            float ao = 1f - k * 0.06f;
            int fy = cy + k * v;
            if (k < dropS) {
                cv.faceLeft(cx, fy, u, v, shade(ls, SOUTH * ao) | 0xFF000000);
            }
            if (k < dropE) {
                cv.faceRight(cx, fy, u, v, shade(le, EAST * ao) | 0xFF000000);
            }
        }
        cv.rhombus(cx, cy, u, shade(c.top(leafId, tint), TOP * slope(ct, surf, north, west)) | 0xFF000000);
    }

    /** Lighter when the column stands above the two behind it, darker when it sits below them. */
    private static float slope(int t, int[] heights, int north, int west) {
        int nh = north < 0 || heights[north] == NONE ? t : heights[north];
        int wh = west < 0 || heights[west] == NONE ? t : heights[west];
        return 1f + Math.max(-0.22f, Math.min(0.12f, (t - Math.max(nh, wh)) * 0.06f));
    }

    static int shade(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 255) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 255) * f));
        int b = Math.min(255, (int) ((rgb & 255) * f));
        return (r << 16) | (g << 8) | b;
    }

    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** The pixel buffer with the bounds of everything drawn on it. */
    private static final class Canvas {
        final int[] px;
        final int iw;
        final int ih;
        int bx0 = Integer.MAX_VALUE;
        int by0 = Integer.MAX_VALUE;
        int bx1 = Integer.MIN_VALUE;
        int by1 = Integer.MIN_VALUE;

        Canvas(int[] px, int iw, int ih) {
            this.px = px;
            this.iw = iw;
            this.ih = ih;
        }

        private void mark(int x0, int y0, int x1, int y1) {
            bx0 = Math.min(bx0, x0);
            by0 = Math.min(by0, y0);
            bx1 = Math.max(bx1, x1);
            by1 = Math.max(by1, y1);
        }

        /** The top face, a rhombus 2u wide and u high centred on cx, cy. */
        void rhombus(int cx, int cy, int u, int col) {
            int half = u / 2;
            mark(cx - u, cy - half, cx + u, cy + half);
            for (int r = 0; r < u; r++) {
                int y = cy - half + r;
                if (y < 0 || y >= ih) {
                    continue;
                }
                int t = r < half ? r + 1 : u - r;
                int hw = Math.min(u, 2 * t);
                int from = Math.max(0, cx - hw);
                int to = Math.min(iw, cx + hw);
                int base = y * iw;
                for (int x = from; x < to; x++) {
                    px[base + x] = col;
                }
            }
        }

        /** A strip h pixels high under the lower left edge of the rhombus at cx, cy. */
        void faceLeft(int cx, int cy, int u, int h, int col) {
            mark(cx - u, cy + 1, cx, cy + (u - 1) / 2 + 1 + h);
            for (int i = 0; i < u; i++) {
                int x = cx - u + i;
                if (x < 0 || x >= iw) {
                    continue;
                }
                int y0 = cy + i / 2 + 1;
                for (int y = Math.max(0, y0); y < Math.min(ih, y0 + h); y++) {
                    px[y * iw + x] = col;
                }
            }
        }

        /** A strip h pixels high under the lower right edge of the rhombus at cx, cy. */
        void faceRight(int cx, int cy, int u, int h, int col) {
            mark(cx, cy + 1, cx + u, cy + (u - 1) / 2 + 1 + h);
            for (int i = 0; i < u; i++) {
                int x = cx + i;
                if (x < 0 || x >= iw) {
                    continue;
                }
                int y0 = cy + (u - 1 - i) / 2 + 1;
                for (int y = Math.max(0, y0); y < Math.min(ih, y0 + h); y++) {
                    px[y * iw + x] = col;
                }
            }
        }

        Image crop(int drawn, int missing) {
            if (bx0 == Integer.MAX_VALUE) {
                return new Image(new int[1], 1, 1, drawn, missing);
            }
            int x0 = Math.max(0, bx0);
            int y0 = Math.max(0, by0);
            int x1 = Math.min(iw, bx1);
            int y1 = Math.min(ih, by1);
            int cw = Math.max(1, Math.min(iw - x0, x1 - x0));
            int ch = Math.max(1, Math.min(ih - y0, y1 - y0));
            // Rows move to lower or equal offsets in the same buffer, so a wide view never holds two copies.
            for (int y = 0; y < ch; y++) {
                System.arraycopy(px, (y0 + y) * iw + x0, px, y * cw, cw);
            }
            java.util.Arrays.fill(px, cw * ch, px.length, 0);
            return new Image(px, cw, ch, drawn, missing);
        }
    }
}
