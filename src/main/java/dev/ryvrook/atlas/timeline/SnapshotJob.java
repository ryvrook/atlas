package dev.ryvrook.atlas.timeline;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One snapshot of one world's tiles, done in small steps so it never holds a thread for long and
 * never holds more than one tile image in memory. The file list is taken first, then each step
 * reads, hashes and stores a batch of tiles.
 *
 * <p>A tile that is being written while it is read is skipped. That is a tile with a temporary
 * file next to it, or one whose size, time or identity changed between the look before and after
 * the read. A skipped tile keeps the hash it had in the earlier snapshot of the same slot or the slot
 * before, and the manifest is marked partial.
 *
 * <p>The live renderer deletes the tiles of the other zooms above and below a zoom 0 tile it draws
 * again, and draws them again only when a browser asks. So the tile folder rarely holds every tile at
 * once. Once the files are read, the job draws the missing tiles of zoom 1 to 4 itself from the four
 * tiles of the zoom below when those are fresh in this snapshot, one tile per unit of a step. Every
 * tile of the earlier snapshot that is still missing then keeps its earlier hash and is counted as
 * carried, since explored terrain never goes away. The manifest is therefore complete.
 */
final class SnapshotJob {
    private static final Pattern TILE = Pattern.compile("(-?\\d+)_(-?\\d+)\\.png");

    record TileRef(int zoom, int x, int z, Path file) {
        String key() {
            return Manifest.key(zoom, x, z);
        }
    }

    final String world;
    final String date;
    final long startedAt;
    private final Path tileDir;
    private final BlobStore blobs;
    private final java.util.function.Supplier<Manifest> previousSource;
    private final String previousSlot;
    private Manifest previous;
    private String previousDate;
    private final Manifest sameDay;
    private final int minZoom;
    private final int maxZoom;
    private final Explored explored;
    private final Set<String> fresh = new HashSet<>();
    private final List<int[]> uppers = new ArrayList<>();
    private boolean planned;
    private int nextUpper;
    private int carried;
    private int composed;
    private boolean carriedOver;
    private final List<TileRef> refs = new ArrayList<>();
    private final Set<String> busy = new HashSet<>();
    private final Map<String, String> tiles = new HashMap<>();
    private int next;
    private int skipped;
    private int skippedBase;
    private int newBlobs;
    private boolean listed;

    /** Tells whether the live map has explored chunks under a tile. */
    @FunctionalInterface
    interface Explored {
        boolean hasTile(int zoom, int x, int z);
    }

    /** Lowest zoom the job draws itself, the first one built from the zoom below. */
    static final int FIRST_UPPER = 1;
    /**
     * Each tile drawn counts as this many tiles read against the batch of a step. A draw reads and
     * decodes up to four images, halves them, encodes one and hashes it, which costs about as much as
     * 20 to 50 reads.
     */
    static final int COMPOSE_COST = 32;

    SnapshotJob(String world, String date, long startedAt, Path tileDir, BlobStore blobs, String previousDate,
                Manifest previous, Manifest sameDay, int minZoom, int maxZoom) {
        this(world, date, startedAt, tileDir, blobs, previousDate, () -> previous, sameDay, minZoom, maxZoom, null);
    }

    SnapshotJob(String world, String date, long startedAt, Path tileDir, BlobStore blobs, String previousDate,
                Manifest previous, Manifest sameDay, int minZoom, int maxZoom, Explored explored) {
        this(world, date, startedAt, tileDir, blobs, previousDate, () -> previous, sameDay, minZoom, maxZoom, explored);
    }

    /**
     * previous gives the complete tiles of the slot before, see TimelineStore.effective. It is asked
     * once, in list, so a caller holding a lock does not wait for it. explored may be null, and then no
     * tile is drawn and every missing tile is carried over.
     */
    SnapshotJob(String world, String date, long startedAt, Path tileDir, BlobStore blobs, String previousDate,
                java.util.function.Supplier<Manifest> previous, Manifest sameDay, int minZoom, int maxZoom,
                Explored explored) {
        this.world = world;
        this.date = date;
        this.startedAt = startedAt;
        this.tileDir = tileDir;
        this.blobs = blobs;
        this.previousSource = previous;
        this.previousSlot = previousDate;
        this.sameDay = sameDay;
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.explored = explored;
    }

    /** Lists the tile files of every zoom folder. Folders and files that are not tiles are ignored. */
    void list() throws IOException {
        if (!listed) {
            try {
                previous = previousSource == null ? null : previousSource.get();
            } catch (RuntimeException e) {
                // Without the slot before nothing could be carried over, so the snapshot fails instead.
                throw new IOException("the snapshot before could not be read", e);
            }
            previousDate = previous == null ? null : previousSlot;
        }
        listed = true;
        if (tileDir == null || !Files.isDirectory(tileDir)) {
            return;
        }
        for (int zoom = minZoom; zoom <= maxZoom; zoom++) {
            Path zd = tileDir.resolve(Integer.toString(zoom));
            if (!Files.isDirectory(zd)) {
                continue;
            }
            try (DirectoryStream<Path> s = Files.newDirectoryStream(zd)) {
                for (Path f : s) {
                    String n = f.getFileName().toString();
                    if (n.endsWith(".tmp")) {
                        int cut = n.indexOf(".png");
                        if (cut > 0) {
                            busy.add(zoom + "|" + n.substring(0, cut + 4));
                        }
                        continue;
                    }
                    Matcher m = TILE.matcher(n);
                    if (m.matches()) {
                        try {
                            refs.add(new TileRef(zoom, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), f));
                        } catch (NumberFormatException ignored) {
                            // Out of int range, not a tile Atlas wrote.
                        }
                    }
                }
            }
        }
    }

    int total() {
        return refs.size();
    }

    int done() {
        return next;
    }

    boolean finished() {
        return listed && next >= refs.size() && planned && nextUpper >= uppers.size();
    }

    /** Reads, hashes and stores up to batch tiles, then draws the missing upper tiles a few at a time. */
    void step(int batch) {
        if (next >= refs.size()) {
            if (!planned) {
                plan();
            }
            int end = Math.min(uppers.size(), nextUpper + Math.max(1, batch / COMPOSE_COST));
            for (; nextUpper < end; nextUpper++) {
                int[] u = uppers.get(nextUpper);
                compose(u[0], u[1], u[2]);
            }
            return;
        }
        int end = Math.min(refs.size(), next + batch);
        for (; next < end; next++) {
            TileRef r = refs.get(next);
            if (busy.contains(r.zoom() + "|" + r.file().getFileName())) {
                skip(r.key());
                continue;
            }
            try {
                BasicFileAttributes before = Files.readAttributes(r.file(), BasicFileAttributes.class);
                byte[] data = Files.readAllBytes(r.file());
                BasicFileAttributes after = Files.readAttributes(r.file(), BasicFileAttributes.class);
                if (data.length == 0 || data.length != before.size() || before.size() != after.size()
                        || !before.lastModifiedTime().equals(after.lastModifiedTime())
                        || !Objects.equals(before.fileKey(), after.fileKey())) {
                    skip(r.key());
                    continue;
                }
                String h = BlobStore.hash(data);
                if (blobs.put(h, data)) {
                    newBlobs++;
                }
                tiles.put(r.key(), h);
                fresh.add(r.key());
            } catch (NoSuchFileException e) {
                // Removed while the snapshot ran, for a redraw or a purge.
                skip(r.key());
            } catch (IOException e) {
                skip(r.key());
            }
        }
    }

    private void skip(String key) {
        skipped++;
        int[] p = Manifest.parseKey(key);
        if (p != null && p[0] == Manifest.BASE_ZOOM) {
            skippedBase++;
        }
        String h = sameDay != null ? sameDay.tiles().get(key) : null;
        if (h == null && previous != null) {
            h = previous.tiles().get(key);
        }
        if (h != null && blobs.has(h)) {
            tiles.put(key, h);
        }
    }

    /** The hash a tile had in the earlier snapshot of this slot, or else in the slot before, or null. */
    private String earlier(String key) {
        String h = sameDay != null ? sameDay.tiles().get(key) : null;
        if (h == null && previous != null) {
            h = previous.tiles().get(key);
        }
        return h;
    }

    /**
     * Lists the tiles of zoom 1 to 4 that the folder lacks, lowest zoom first. Those are the tiles of
     * the earlier snapshots and the parents of every tile of the zoom below, so newly explored ground
     * gets its upper tiles too. It runs in one step. That splits each known key once per zoom, a few
     * thousand keys on a large world, which is quick.
     */
    private void plan() {
        planned = true;
        if (explored == null) {
            return;
        }
        Set<String> known = new HashSet<>(tiles.keySet());
        if (sameDay != null) {
            known.addAll(sameDay.tiles().keySet());
        }
        if (previous != null) {
            known.addAll(previous.tiles().keySet());
        }
        Set<String> live = new HashSet<>();
        for (TileRef r : refs) {
            live.add(r.key());
        }
        Set<String> below = new HashSet<>();
        for (String k : known) {
            int[] p = Manifest.parseKey(k);
            if (p != null && p[0] == FIRST_UPPER - 1) {
                below.add(k);
            }
        }
        for (int zoom = Math.max(FIRST_UPPER, minZoom); zoom <= maxZoom; zoom++) {
            Set<String> level = new HashSet<>();
            for (String k : known) {
                int[] p = Manifest.parseKey(k);
                if (p != null && p[0] == zoom) {
                    level.add(k);
                }
            }
            for (String k : below) {
                int[] p = Manifest.parseKey(k);
                level.add(Manifest.key(zoom, Math.floorDiv(p[1], 2), Math.floorDiv(p[2], 2)));
            }
            List<int[]> todo = new ArrayList<>();
            for (String k : level) {
                if (!live.contains(k)) {
                    todo.add(Manifest.parseKey(k));
                }
            }
            todo.sort((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[2], b[2]));
            uppers.addAll(todo);
            below = level;
        }
    }

    /**
     * Draws one missing upper tile from the four tiles below it. A quarter is used when this snapshot
     * read or drew it, and left empty when it holds no explored ground and no earlier snapshot had it.
     * Any other quarter is unknown, and then the tile is left to be carried over.
     */
    private void compose(int zoom, int x, int z) {
        byte[][] children = new byte[4][];
        for (int i = 0; i < 4; i++) {
            int cx = x * 2 + i % 2;
            int cz = z * 2 + i / 2;
            String ck = Manifest.key(zoom - 1, cx, cz);
            if (fresh.contains(ck)) {
                try {
                    children[i] = blobs.read(tiles.get(ck));
                } catch (IOException e) {
                    children[i] = null;
                }
                if (children[i] == null) {
                    return;
                }
            } else if (explored.hasTile(zoom - 1, cx, cz) || earlier(ck) != null) {
                return;
            }
        }
        byte[] png;
        try {
            png = dev.ryvrook.atlas.tiles.TileService.composeUpper(children);
        } catch (RuntimeException e) {
            // A damaged image. The tile is carried over instead.
            png = null;
        }
        if (png == null) {
            return;
        }
        try {
            String h = BlobStore.hash(png);
            if (blobs.put(h, png)) {
                newBlobs++;
            }
            String key = Manifest.key(zoom, x, z);
            tiles.put(key, h);
            fresh.add(key);
            composed++;
        } catch (IOException ignored) {
            // Carried over instead.
        }
    }

    /** Gives every tile of the earlier snapshots that is still missing its earlier hash. */
    private void carryOver() {
        if (carriedOver) {
            return;
        }
        carriedOver = true;
        Set<String> keys = new HashSet<>();
        if (sameDay != null) {
            keys.addAll(sameDay.tiles().keySet());
        }
        if (previous != null) {
            keys.addAll(previous.tiles().keySet());
        }
        for (String k : keys) {
            if (tiles.containsKey(k)) {
                continue;
            }
            String h = earlier(k);
            if (h != null && blobs.has(h)) {
                tiles.put(k, h);
                carried++;
            }
        }
    }

    Manifest result(long takenAt) {
        carryOver();
        List<String> changed = Manifest.diff(previous == null ? null : previous.tiles(), tiles);
        return new Manifest(world, date, takenAt, previousDate, skipped > 0, skipped,
                newBlobs, tiles, changed, carried, composed, true);
    }

    /**
     * Base zoom tiles that show a block change against the slot before, see Manifest.blockChanges. A
     * base tile that was being redrawn while it was read counts too, since the redraw may be a change.
     * Counts every base tile when there is no slot before.
     */
    int blockChanges() {
        carryOver();
        return Manifest.blockChanges(previous == null ? null : previous.tiles(), tiles) + skippedBase;
    }

    /** The slot this snapshot was compared with, or null when there was none. Known after list. */
    String previousSlot() {
        return previousDate;
    }

    /** When the slot before was taken, or 0 when there was none. Known after list. */
    long previousTakenAt() {
        return previous == null ? 0 : previous.takenAt();
    }

    int carried() {
        return carried;
    }

    int composed() {
        return composed;
    }

    int newBlobs() {
        return newBlobs;
    }

    int skipped() {
        return skipped;
    }
}
