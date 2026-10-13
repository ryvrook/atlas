package dev.ryvrook.atlas.http;

import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The world map marker images of the game, Common/UI/WorldMap/MapMarkers/{Name}.png. They are read
 * from the base asset pack the server was started with (its --assets argument, which AssetModule
 * exposes as the base pack root), or from the common asset registry when the pack has no such file.
 * Each image is read once and kept in memory. Only names that exist are kept, and the folder holds
 * about two dozen small files. Misses are remembered in a separate set of at most MAX_MISSES names,
 * so unknown names can never push real images out.
 */
public final class MarkerIcons {
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,40}");
    static final int MAX_ENTRIES = 128;
    static final int MAX_MISSES = 256;
    private static final String FOLDER = "UI/WorldMap/MapMarkers/";

    public record Icon(byte[] png, String etag) {
    }

    private final Map<String, Icon> cache = new ConcurrentHashMap<>();
    private final java.util.Set<String> misses = ConcurrentHashMap.newKeySet();

    /** True for names the route accepts. Anything else is answered with 404 before any lookup. */
    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** The image of a marker, or null when the name is invalid, unknown or the assets are not available. */
    public Icon get(String name) {
        if (!validName(name)) {
            return null;
        }
        Icon cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        if (misses.contains(name)) {
            return null;
        }
        Lookup found = load(name);
        if (found.bytes == null) {
            if (found.searched && misses.size() < MAX_MISSES) {
                // Only remember a miss when the assets were there to search.
                misses.add(name);
            }
            return null;
        }
        Icon icon = new Icon(found.bytes, etagOf(found.bytes));
        if (cache.size() < MAX_ENTRIES) {
            cache.put(name, icon);
        }
        return icon;
    }

    private record Lookup(byte[] bytes, boolean searched) {
    }

    private static Lookup load(String name) {
        boolean searched = false;
        String rel = FOLDER + name + ".png";
        try {
            AssetModule module = AssetModule.get();
            AssetPack base = module == null ? null : module.getBaseAssetPack();
            Path root = base == null ? null : base.getRoot();
            if (root != null) {
                searched = true;
                Path file = root.resolve("Common").resolve(rel);
                if (Files.isRegularFile(file)) {
                    byte[] b = Files.readAllBytes(file);
                    if (isPng(b)) {
                        return new Lookup(b, true);
                    }
                }
            }
        } catch (Exception | LinkageError ignored) {
            // Fall through to the registry.
        }
        try {
            CommonAsset asset = CommonAssetRegistry.getByName(rel);
            if (asset != null) {
                searched = true;
                byte[] b = asset.getBlob().get(2, TimeUnit.SECONDS);
                if (isPng(b)) {
                    return new Lookup(b, true);
                }
            }
        } catch (Exception | LinkageError ignored) {
            // No registry entry.
        }
        return new Lookup(null, searched);
    }

    static boolean isPng(byte[] b) {
        return b != null && b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    static String etagOf(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            return "\"" + HexFormat.of().formatHex(d, 0, 12) + "\"";
        } catch (Exception e) {
            return "\"" + Integer.toHexString(java.util.Arrays.hashCode(b)) + "\"";
        }
    }
}
