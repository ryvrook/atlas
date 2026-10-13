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
    private final Manifest previous;
    private final String previousDate;
    private final Manifest sameDay;
    private final int minZoom;
    private final int maxZoom;
    private final List<TileRef> refs = new ArrayList<>();
    private final Set<String> busy = new HashSet<>();
    private final Map<String, String> tiles = new HashMap<>();
    private int next;
    private int skipped;
    private int newBlobs;
    private boolean listed;

    SnapshotJob(String world, String date, long startedAt, Path tileDir, BlobStore blobs, String previousDate,
                Manifest previous, Manifest sameDay, int minZoom, int maxZoom) {
        this.world = world;
        this.date = date;
        this.startedAt = startedAt;
        this.tileDir = tileDir;
        this.blobs = blobs;
        this.previous = previous;
        this.previousDate = previous == null ? null : previousDate;
        this.sameDay = sameDay;
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
    }

    /** Lists the tile files of every zoom folder. Folders and files that are not tiles are ignored. */
    void list() throws IOException {
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
        return listed && next >= refs.size();
    }

    /** Reads, hashes and stores up to batch tiles. */
    void step(int batch) {
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
        String h = sameDay != null ? sameDay.tiles().get(key) : null;
        if (h == null && previous != null) {
            h = previous.tiles().get(key);
        }
        if (h != null && blobs.has(h)) {
            tiles.put(key, h);
        }
    }

    Manifest result(long takenAt) {
        List<String> changed = Manifest.diff(previous == null ? null : previous.tiles(), tiles);
        return new Manifest(world, date, takenAt, previousDate, skipped > 0, skipped,
                newBlobs, tiles, changed);
    }

    int newBlobs() {
        return newBlobs;
    }

    int skipped() {
        return skipped;
    }
}
