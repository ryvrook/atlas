package dev.ryvrook.atlas.iso;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads the chunks of a view into column records and draws them. Runs on the Atlas-iso thread and
 * never on a world thread. Chunks are asked for in batches of BATCH with NO_GENERATE, so a chunk
 * that is not saved stays missing and nothing is ever generated. Chunks loaded this way never tick
 * and the engine unloads them again within about half a minute. Components are read with
 * getComponentConcurrent.
 *
 * <p>The height map counts the top block including leaves, but not plants or fluid, which sit one
 * above it. The scan starts SCAN_ABOVE blocks over the height to find plants.
 *
 * <p>The fixed world height of 0.7.0 (ten sections of 32 blocks) and the chunk column component are
 * marked deprecated in the engine, ahead of a change to variable heights, and are the only way to
 * reach the sections there is today.
 */
@SuppressWarnings("deprecation")
final class IsoRenderer {
    static final int BATCH = 16;
    static final int SCAN_ABOVE = 8;
    /** How far under a canopy the scan looks for the ground. */
    static final int UNDER_CANOPY = 48;
    private static final long BATCH_WAIT_MS = 30_000;
    private static final long BUDGET_POLL_MS = 200;

    /**
     * Takes one chunk read from the shared chunk image budget, false when it is spent for now.
     * Background work only takes from the upper half of the bucket.
     */
    interface ChunkBudget {
        boolean tryTake(boolean background);
    }

    /** True for chunks the view may read, explored ones inside the render limit. */
    interface ChunkFilter {
        boolean allowed(int cx, int cz);
    }

    /** What one render did. print holds the fingerprint of every chunk of the square as it was drawn. */
    record Result(byte[] png, int width, int height, boolean partial, int wanted, int read, int skipped, long readMs,
                  long drawMs, long encodeMs, IsoCache.Print print) {
    }

    private IsoRenderer() {
    }

    /**
     * Reads and draws the view. Only chunks not already in memory take a budget token, as background
     * work when background is set. loaded hears about every chunk this render brought into memory,
     * at the moment it arrived.
     */
    static Result render(World world, IsoView v, ChunkFilter filter, ChunkBudget budget, boolean background,
                         long deadline, Palette c, java.util.function.LongConsumer loaded) throws InterruptedException {
        Read r = read(world, v, filter, budget, background, deadline, c, loaded);
        long t1 = System.nanoTime();
        IsoRaster.Image img = IsoRaster.render(r.columns(), c, v.scale(), v.azimuth());
        // The column records are dropped before the encode, so they can be collected while it runs.
        r = new Read(null, r.print(), r.wanted(), r.read(), r.skipped(), r.t0());
        long t2 = System.nanoTime();
        return encode(img, r.skipped() > 0, r.wanted(), r.read(), r.skipped(), r.t0(), t1, t2, r.print());
    }

    /** The chunks of a view as read, before anything is drawn. */
    record Read(Columns columns, IsoCache.Print print, int wanted, int read, int skipped, long t0) {
        boolean partial() {
            return skipped > 0;
        }
    }

    /**
     * Reads the chunks of the view into column records and their fingerprint without drawing them,
     * the cheap half of a render. The daily key places use it to tell whether anything changed.
     */
    static Read read(World world, IsoView v, ChunkFilter filter, ChunkBudget budget, boolean background,
                     long deadline, Palette c, java.util.function.LongConsumer loaded) throws InterruptedException {
        long t0 = System.nanoTime();
        int side = v.side();
        Columns a = new Columns(side * 32, (v.cx() - v.radius()) * 32, (v.cz() - v.radius()) * 32);
        LongArrayList want = new LongArrayList();
        for (int dz = -v.radius(); dz <= v.radius(); dz++) {
            for (int dx = -v.radius(); dx <= v.radius(); dx++) {
                if (filter.allowed(v.cx() + dx, v.cz() + dz)) {
                    want.add(ChunkUtil.indexChunk(v.cx() + dx, v.cz() + dz));
                }
            }
        }
        ChunkStore cs = world.getChunkStore();
        Store<ChunkStore> st = cs.getStore();
        LongOpenHashSet loadedBefore = loaded(cs);
        int read = 0;
        int skipped = 0;
        boolean outOfBudget = false;
        long[] terrainPrint = new long[side * side];
        for (int i = 0; i < want.size(); i += BATCH) {
            int end = Math.min(want.size(), i + BATCH);
            List<Long> batch = new ArrayList<>();
            for (int k = i; k < end; k++) {
                long idx = want.getLong(k);
                boolean inMemory = loadedBefore != null && loadedBefore.contains(idx);
                if (!inMemory && !outOfBudget && !take(budget, background, deadline)) {
                    outOfBudget = true;
                }
                if (!inMemory && outOfBudget) {
                    skipped++;
                } else {
                    batch.add(idx);
                }
            }
            if (batch.isEmpty()) {
                continue;
            }
            List<CompletableFuture<Ref<ChunkStore>>> futures = new ArrayList<>();
            for (long idx : batch) {
                futures.add(cs.getChunkReferenceAsync(idx, GetChunkFlags.NO_GENERATE));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(BATCH_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                // Chunks that failed or did not arrive stay missing and are drawn as grey floor.
            }
            for (int k = 0; k < batch.size(); k++) {
                CompletableFuture<Ref<ChunkStore>> f = futures.get(k);
                Ref<ChunkStore> ref = null;
                if (f.isDone() && !f.isCompletedExceptionally()) {
                    ref = f.getNow(null);
                }
                if (ref == null || !ref.isValid()) {
                    skipped++;
                    continue;
                }
                long idx = batch.get(k);
                if (loadedBefore != null && !loadedBefore.contains(idx)) {
                    loaded.accept(idx);
                }
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                try {
                    int cx = ChunkUtil.xOfChunkIndex(idx);
                    int cz = ChunkUtil.zOfChunkIndex(idx);
                    if (readChunk(cs, st, ref, cx, cz, a, c)) {
                        read++;
                        terrainPrint[slot(v, cx, cz)] = a.hashChunk(cx * 32 - a.minBlockX, cz * 32 - a.minBlockZ);
                    } else {
                        skipped++;
                    }
                } catch (IllegalStateException | NullPointerException | IndexOutOfBoundsException e) {
                    // The chunk unloaded while it was read.
                    skipped++;
                }
            }
        }
        IsoCache.Print print = new IsoCache.Print(terrainPrint, imagePrint(world, v));
        return new Read(a, print, want.size(), read, skipped, t0);
    }

    /** The place of a chunk in the fingerprint of a view, row by row from the north west corner. */
    static int slot(IsoView v, int cx, int cz) {
        return (cz - v.cz() + v.radius()) * v.side() + (cx - v.cx() + v.radius());
    }

    /** The hash of the map image of every chunk of the square the engine holds in memory, 0 for the others. */
    private static long[] imagePrint(World world, IsoView v) {
        long[] out = new long[v.chunks()];
        com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager wmm;
        try {
            wmm = world.getWorldMapManager();
        } catch (RuntimeException e) {
            return out;
        }
        if (wmm == null) {
            return out;
        }
        for (int dz = -v.radius(); dz <= v.radius(); dz++) {
            for (int dx = -v.radius(); dx <= v.radius(); dx++) {
                out[slot(v, v.cx() + dx, v.cz() + dz)] = imageHash(wmm, v.cx() + dx, v.cz() + dz);
            }
        }
        return out;
    }

    private static long imageHash(com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager wmm, int cx,
                                  int cz) {
        try {
            return dev.ryvrook.atlas.tiles.MapImageDecoder.hash(wmm.getImageIfInMemory(cx, cz));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * True when none of the chunks, packed as IsoCache.chunk, changed since the view was drawn. A map
     * image the engine holds that differs from the one at render time counts as a change at once.
     * Otherwise the chunk is read again and the records it gives are compared, since the engine may
     * keep an outdated map image for a while. A chunk the view may not draw is drawn as floor either
     * way and is skipped. A chunk it may draw that was not read at render time, one that cannot be
     * read now and one not in memory while the budget is spent all count as changed.
     */
    static boolean unchanged(World world, IsoView v, IsoCache.Print print, java.util.Collection<Long> chunks,
                             ChunkFilter filter, ChunkBudget budget, Palette c) throws InterruptedException {
        com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager wmm = null;
        try {
            wmm = world.getWorldMapManager();
        } catch (RuntimeException ignored) {
            // Only the records are compared then.
        }
        ChunkStore cs = world.getChunkStore();
        Store<ChunkStore> st = cs.getStore();
        LongOpenHashSet inMemory = loaded(cs);
        for (long packed : chunks) {
            int cx = IsoCache.chunkX(packed);
            int cz = IsoCache.chunkZ(packed);
            if (!v.covers(cx, cz) || !filter.allowed(cx, cz)) {
                continue;
            }
            int k = slot(v, cx, cz);
            if (print.terrain()[k] == 0) {
                return false;
            }
            long img = wmm == null ? 0 : imageHash(wmm, cx, cz);
            if (img != 0 && print.image()[k] != 0 && img != print.image()[k]) {
                return false;
            }
            long idx = ChunkUtil.indexChunk(cx, cz);
            if ((inMemory == null || !inMemory.contains(idx)) && !budget.tryTake(true)) {
                return false;
            }
            Ref<ChunkStore> ref;
            try {
                ref = cs.getChunkReferenceAsync(idx, GetChunkFlags.NO_GENERATE).get(BATCH_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException e) {
                return false;
            }
            if (ref == null || !ref.isValid()) {
                return false;
            }
            Columns one = new Columns(32, cx * 32, cz * 32);
            try {
                if (!readChunk(cs, st, ref, cx, cz, one, c) || one.hashChunk(0, 0) != print.terrain()[k]) {
                    return false;
                }
            } catch (IllegalStateException | NullPointerException | IndexOutOfBoundsException e) {
                return false;
            }
        }
        return true;
    }

    /** Encodes the picture. The column records are no longer referenced, so they can be collected while it runs. */
    private static Result encode(IsoRaster.Image img, boolean partial, int wanted, int read, int skipped, long t0, long t1,
                                 long t2, IsoCache.Print print) {
        byte[] png = PngEncoder.encode(img.argb(), img.width(), img.height());
        long t3 = System.nanoTime();
        return new Result(png, img.width(), img.height(), partial, wanted, read, skipped,
                (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, print);
    }

    /** The chunks the world holds in memory now, or null when the set kept changing while it was copied. */
    private static LongOpenHashSet loaded(ChunkStore cs) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return new LongOpenHashSet(cs.getChunkIndexes());
            } catch (RuntimeException e) {
                // Changed while copied, try again.
            }
        }
        return null;
    }

    /** Waits for a budget token until the deadline. False when the deadline passed first. */
    private static boolean take(ChunkBudget budget, boolean background, long deadline) throws InterruptedException {
        while (!budget.tryTake(background)) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(BUDGET_POLL_MS);
        }
        return true;
    }

    /** Reads one chunk into the records. False when a component is missing. */
    private static boolean readChunk(ChunkStore cs, Store<ChunkStore> st, Ref<ChunkStore> ref, int cx, int cz, Columns a,
                                     Palette c) throws InterruptedException {
        BlockChunk bc = st.getComponentConcurrent(ref, BlockChunk.getComponentType());
        HeightmapColumn hm = st.getComponentConcurrent(ref, HeightmapColumn.getComponentType());
        ChunkColumn col = st.getComponentConcurrent(ref, ChunkColumn.getComponentType());
        if (bc == null || hm == null || col == null) {
            return false;
        }
        BlockSection[] bs = new BlockSection[ChunkUtil.HEIGHT_SECTIONS];
        FluidSection[] fs = new FluidSection[ChunkUtil.HEIGHT_SECTIONS];
        for (int sy = 0; sy < ChunkUtil.HEIGHT_SECTIONS; sy++) {
            Ref<ChunkStore> sr = col.getSection(sy);
            if (sr == null || !sr.isValid()) {
                try {
                    sr = cs.getChunkSectionReferenceAsync(cx, sy, cz, GetChunkFlags.NO_GENERATE).get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException
                         | RuntimeException e) {
                    sr = null;
                }
            }
            if (sr != null && sr.isValid()) {
                bs[sy] = st.getComponentConcurrent(sr, BlockSection.getComponentType());
                fs[sy] = st.getComponentConcurrent(sr, FluidSection.getComponentType());
            }
        }
        int ox = cx * 32 - a.minBlockX;
        int oz = cz * 32 - a.minBlockZ;
        for (int lz = 0; lz < 32; lz++) {
            for (int lx = 0; lx < 32; lx++) {
                column(a, (oz + lz) * a.w + ox + lx, bs, fs, hm.getHeight(lx, lz), bc.getTint(lx, lz), lx, lz, c);
            }
        }
        return true;
    }

    private static void column(Columns a, int i, BlockSection[] bs, FluidSection[] fs, int h, int tint, int lx, int lz,
                               Palette c) {
        int y = Math.min(ChunkUtil.HEIGHT_MINUS_1, h + SCAN_ABOVE);
        while (y >= 0 && c.clear[c.safe(block(bs, lx, y, lz))]) {
            y--;
        }
        if (y < 0) {
            return;
        }
        int topId = c.safe(block(bs, lx, y, lz));
        byte flags = Columns.PRESENT;
        int ground = y;
        if (c.leaf[topId]) {
            int bottom = y;
            while (bottom - 1 >= 0 && c.leaf[c.safe(block(bs, lx, bottom - 1, lz))]) {
                bottom--;
            }
            int limit = Math.max(0, bottom - 1 - UNDER_CANOPY);
            int trunkId = -1;
            boolean inTrunk = false;
            int k = bottom - 1;
            for (; k >= limit; k--) {
                int id = c.safe(block(bs, lx, k, lz));
                if (c.trunk[id]) {
                    if (!inTrunk) {
                        trunkId = id;
                        inTrunk = true;
                    }
                    continue;
                }
                if (c.clear[id] || c.leaf[id] || c.slab[id]) {
                    // A trunk piece with air under it was a branch.
                    inTrunk = false;
                    trunkId = -1;
                    continue;
                }
                break;
            }
            if (k >= limit && k >= 0) {
                ground = k;
                flags |= Columns.CANOPY;
                a.extra[i] = (char) topId;
                a.canopyTop[i] = (short) y;
                a.canopyBottom[i] = (short) bottom;
                if (inTrunk && trunkId >= 0) {
                    flags |= Columns.TRUNK;
                    a.trunk[i] = (char) trunkId;
                }
            }
        } else if (y > h && c.slab[topId]) {
            flags |= Columns.DECOR;
            a.extra[i] = (char) topId;
            ground = y - 1;
            while (ground >= 0 && c.clear[c.safe(block(bs, lx, ground, lz))]) {
                ground--;
            }
            if (ground < 0) {
                ground = y;
                flags = Columns.PRESENT;
            }
        }
        a.flags[i] = flags;
        a.terrain[i] = (short) ground;
        for (int k = 0; k < Columns.DEPTH; k++) {
            a.ids[i * Columns.DEPTH + k] = (char) c.safe(block(bs, lx, ground - k, lz));
        }
        int wy = -1;
        int fid = 0;
        for (int yy = ground + 1; yy < ChunkUtil.HEIGHT; yy++) {
            int f = fluid(fs, lx, yy, lz);
            if (f == 0) {
                break;
            }
            wy = yy;
            fid = f;
        }
        a.waterTop[i] = (short) wy;
        a.fluid[i] = (char) Math.max(0, Math.min(Character.MAX_VALUE, fid));
        a.tint[i] = tint;
    }

    private static int block(BlockSection[] bs, int x, int y, int z) {
        if (y < 0 || y >= ChunkUtil.HEIGHT) {
            return 0;
        }
        BlockSection s = bs[y >> 5];
        return s == null ? 0 : s.get(x, y, z);
    }

    private static int fluid(FluidSection[] fs, int x, int y, int z) {
        if (y < 0 || y >= ChunkUtil.HEIGHT) {
            return 0;
        }
        FluidSection s = fs[y >> 5];
        return s == null ? 0 : s.getFluidId(x, y, z);
    }
}
