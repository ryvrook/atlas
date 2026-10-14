package dev.ryvrook.atlas.iso;

import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.DrawType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;

/**
 * A flat colour table over every block type id, built once from the block assets and again after
 * an asset reload. Most block types carry a colour computed from their texture, the rest fall back
 * to their particle colour or a neutral grey. Face tints are pulled toward the biome tint of the
 * column by the share the block type asks for.
 *
 * <p>Ids are stored as char in column records, so ids at or above 65536 are drawn as air.
 */
public final class Palette {
    /** Ids the column records can hold. */
    public static final int MAX_IDS = 65_536;

    final int n;
    final int[] base;
    final int[] tintUp;
    final int[] tintSouth;
    final int[] tintEast;
    final float[] bioUp;
    final float[] bioSouth;
    final float[] bioEast;
    /** Not drawn at all. Air and the Empty draw type. */
    final boolean[] clear;
    /** Drawn as a thin slab. Plants and other models that are not cubes. */
    final boolean[] slab;
    /** Leaf blocks of trees, whose side faces are capped so trees do not turn into pillars. */
    final boolean[] leaf;
    /** Tree trunk blocks, drawn under a capped canopy. */
    final boolean[] trunk;
    /** Fluid id to colour. */
    final int[] fluid;
    /** Fluids that give off light, like lava, drawn at full brightness and never shaded. */
    final boolean[] fluidGlow;
    /** Block types with a colour, for status. */
    final int types;

    private Palette(int n, int fluids, int types) {
        this.n = n;
        base = new int[n];
        tintUp = new int[n];
        tintSouth = new int[n];
        tintEast = new int[n];
        bioUp = new float[n];
        bioSouth = new float[n];
        bioEast = new float[n];
        clear = new boolean[n];
        slab = new boolean[n];
        leaf = new boolean[n];
        trunk = new boolean[n];
        fluid = new int[Math.max(1, fluids)];
        fluidGlow = new boolean[fluid.length];
        java.util.Arrays.fill(tintUp, 0xFFFFFF);
        java.util.Arrays.fill(tintSouth, 0xFFFFFF);
        java.util.Arrays.fill(tintEast, 0xFFFFFF);
        java.util.Arrays.fill(fluid, 0x2f62c8);
        this.types = types;
    }

    /** Ids in the table. */
    public int size() {
        return n;
    }

    /** Block types with a colour. */
    public int types() {
        return types;
    }

    /** A test palette of n ids, every id a grey cube except id 0, which is air. */
    static Palette synthetic(int n) {
        Palette p = new Palette(n, 4, n - 1);
        p.clear[0] = true;
        for (int i = 1; i < n; i++) {
            p.base[i] = 0x808080;
        }
        return p;
    }

    /** Builds the table from the loaded block and fluid assets. */
    public static Palette fromAssets() {
        BlockTypeAssetMap<String, BlockType> map = BlockType.getAssetMap();
        int n = Math.min(MAX_IDS, map.getNextIndex());
        var fmap = Fluid.getAssetMap();
        int fluids = Math.min(MAX_IDS, Math.max(1, fmap.getNextIndex()));
        int types = 0;
        for (int id = 0; id < n; id++) {
            BlockType t = map.getAsset(id);
            if (t != null && id != BlockType.EMPTY_ID && t.getDrawType() != DrawType.Empty) {
                types++;
            }
        }
        Palette c = new Palette(n, fluids, types);
        for (int id = 0; id < n; id++) {
            BlockType t = map.getAsset(id);
            if (t == null || id == BlockType.EMPTY_ID) {
                c.clear[id] = true;
                continue;
            }
            DrawType dt = t.getDrawType();
            Color tex = t.getTextureComputedColor();
            Color par = t.getParticleColor();
            if (tex != null && rgb(tex) != 0) {
                c.base[id] = rgb(tex);
            } else if (par != null) {
                c.base[id] = rgb(par);
            } else {
                c.base[id] = 0x8a8a8a;
            }
            String model = t.getCustomModel();
            c.clear[id] = dt == DrawType.Empty;
            c.slab[id] = dt == DrawType.Model || (model != null && dt != DrawType.Cube && dt != DrawType.CubeWithModel);
            String name = t.getId();
            if (name != null) {
                c.leaf[id] = name.startsWith("Plant_Leaves");
                c.trunk[id] = name.startsWith("Wood_") && name.contains("Trunk");
            }
            c.tintUp[id] = first(t.getTintUp());
            c.tintSouth[id] = first(t.getTintSouth());
            c.tintEast[id] = first(t.getTintEast());
            c.bioUp[id] = t.getBiomeTintUp() / 100f;
            c.bioSouth[id] = t.getBiomeTintSouth() / 100f;
            c.bioEast[id] = t.getBiomeTintEast() / 100f;
        }
        for (int id = 0; id < c.fluid.length; id++) {
            Fluid f = fmap.getAsset(id);
            if (f != null && f.getParticleColor() != null) {
                c.fluid[id] = rgb(f.getParticleColor());
            }
            if (f != null && f.getLight() != null) {
                c.fluidGlow[id] = true;
            }
        }
        return c;
    }

    private static int first(Color[] arr) {
        return arr != null && arr.length > 0 && arr[0] != null ? rgb(arr[0]) : 0xFFFFFF;
    }

    static int rgb(Color c) {
        return ((c.red & 255) << 16) | ((c.green & 255) << 8) | (c.blue & 255);
    }

    int safe(int id) {
        return id >= 0 && id < n ? id : 0;
    }

    int top(int id, int biome) {
        return tinted(base[id], tintUp[id], bioUp[id], biome);
    }

    int south(int id, int biome) {
        return tinted(base[id], tintSouth[id], bioSouth[id], biome);
    }

    int east(int id, int biome) {
        return tinted(base[id], tintEast[id], bioEast[id], biome);
    }

    boolean glows(int fluidId) {
        return fluidId >= 0 && fluidId < fluidGlow.length && fluidGlow[fluidId];
    }

    int water(int fluidId) {
        return fluidId >= 0 && fluidId < fluid.length ? fluid[fluidId] : 0x2f62c8;
    }

    /** base times the face tint, the face tint first pulled toward the biome tint by the given share. */
    static int tinted(int base, int tint, float share, int biome) {
        int tr = (tint >> 16) & 255;
        int tg = (tint >> 8) & 255;
        int tb = tint & 255;
        if (share > 0) {
            tr = (int) (tr + (((biome >> 16) & 255) - tr) * share);
            tg = (int) (tg + (((biome >> 8) & 255) - tg) * share);
            tb = (int) (tb + ((biome & 255) - tb) * share);
        }
        int r = ((base >> 16) & 255) * tr / 255;
        int g = ((base >> 8) & 255) * tg / 255;
        int b = (base & 255) * tb / 255;
        return (r << 16) | (g << 8) | b;
    }
}
