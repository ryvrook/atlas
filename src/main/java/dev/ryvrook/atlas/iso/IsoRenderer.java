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

    /** Takes one chunk read from the shared chunk image budget, false when it is spent for now. */
    interface ChunkBudget {
        boolean tryTake();
    }

    /** True for chunks the view may read, explored ones inside the render limit. */
    interface ChunkFilter {
        boolean allowed(int cx, int cz);
    }

    /** What one render did. */
    record Result(byte[] png, int width, int height, boolean partial, int wanted, int read, int skipped, long readMs,
                  long drawMs, long encodeMs) {
    }

    private IsoRenderer() {
    }

    /**
     * Reads and draws the view. loaded hears about every chunk this render brought into memory, at
     * the moment it arrived.
     */
    static Result render(World world, IsoView v, ChunkFilter filter, ChunkBudget budget, long deadline, Palette c,
                         java.util.function.LongConsumer loaded) throws InterruptedException {
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
        for (int i = 0; i < want.size(); i += BATCH) {
            int end = Math.min(want.size(), i + BATCH);
            List<Long> batch = new ArrayList<>();
            for (int k = i; k < end; k++) {
                long idx = want.getLong(k);
                boolean inMemory = loadedBefore != null && loadedBefore.contains(idx);
                if (!inMemory && !outOfBudget && !take(budget, deadline)) {
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
                    if (readChunk(cs, st, ref, ChunkUtil.xOfChunkIndex(idx), ChunkUtil.zOfChunkIndex(idx), a, c)) {
                        read++;
                    } else {
                        skipped++;
                    }
                } catch (IllegalStateException | NullPointerException | IndexOutOfBoundsException e) {
                    // The chunk unloaded while it was read.
                    skipped++;
                }
            }
        }
        long t1 = System.nanoTime();
        int wanted = want.size();
        IsoRaster.Image img = IsoRaster.render(a, c, v.scale(), v.azimuth());
        a = null;
        loadedBefore = null;
        long t2 = System.nanoTime();
        return encode(img, skipped > 0, wanted, read, skipped, t0, t1, t2);
    }

    /** Encodes the picture. The column records are no longer referenced, so they can be collected while it runs. */
    private static Result encode(IsoRaster.Image img, boolean partial, int wanted, int read, int skipped, long t0, long t1,
                                 long t2) {
        byte[] png = PngEncoder.encode(img.argb(), img.width(), img.height());
        long t3 = System.nanoTime();
        return new Result(png, img.width(), img.height(), partial, wanted, read, skipped,
                (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000);
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
    private static boolean take(ChunkBudget budget, long deadline) throws InterruptedException {
        while (!budget.tryTake()) {
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
