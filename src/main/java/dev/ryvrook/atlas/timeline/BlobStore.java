package dev.ryvrook.atlas.timeline;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Content addressed tile images. A blob is named by the SHA-256 of its bytes and lives under
 * blobs/ab/abcdef....png, so the same picture is stored once however many days use it. Blobs are
 * written once through a temporary file and a move, and never changed afterwards.
 */
public final class BlobStore {
    /** Full SHA-256 in lowercase hex. */
    static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final AtomicLong TMP = new AtomicLong();

    private final Path dir;

    public BlobStore(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    public static String hash(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java runtime", e);
        }
    }

    public static boolean validHash(String h) {
        return h != null && HASH.matcher(h).matches();
    }

    public Path path(String hash) {
        return dir.resolve(hash.substring(0, 2)).resolve(hash + ".png");
    }

    public boolean has(String hash) {
        return Files.isRegularFile(path(hash));
    }

    /**
     * Stores the bytes unless a blob with their hash exists. Returns true when a new blob was
     * written. The write goes to a unique temporary name and is moved into place, so a reader never
     * sees half a blob. No fsync, a blob lost to a power cut is written again by the next snapshot
     * that holds the tile, since has() then answers false.
     */
    public boolean put(String hash, byte[] data) throws IOException {
        Path target = path(hash);
        if (Files.isRegularFile(target)) {
            return false;
        }
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(hash + "." + TMP.incrementAndGet() + ".tmp");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                return false;
            }
            return true;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public byte[] read(String hash) throws IOException {
        try {
            return Files.readAllBytes(path(hash));
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /** Calls the visitor with the hash and size of every blob on disk. Temporary leftovers are removed. */
    public void forEach(BiConsumer<String, Long> visitor) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> shards = Files.newDirectoryStream(dir)) {
            for (Path shard : shards) {
                if (!Files.isDirectory(shard)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(shard)) {
                    for (Path f : files) {
                        String n = f.getFileName().toString();
                        if (n.endsWith(".tmp")) {
                            Files.deleteIfExists(f);
                            continue;
                        }
                        if (!n.endsWith(".png")) {
                            continue;
                        }
                        String h = n.substring(0, n.length() - 4);
                        if (validHash(h)) {
                            visitor.accept(h, Files.size(f));
                        }
                    }
                }
            }
        }
    }

    /**
     * Deletes every blob whose hash is not in keep. Returns {blobs removed, bytes freed}. Empty
     * shard folders are removed too.
     */
    public long[] collect(Set<String> keep) throws IOException {
        long[] out = new long[2];
        Map<String, Long> doomed = new java.util.HashMap<>();
        forEach((h, size) -> {
            if (!keep.contains(h)) {
                doomed.put(h, size);
            }
        });
        for (Map.Entry<String, Long> e : doomed.entrySet()) {
            if (Files.deleteIfExists(path(e.getKey()))) {
                out[0]++;
                out[1] += e.getValue();
            }
        }
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> shards = Files.newDirectoryStream(dir)) {
                for (Path shard : shards) {
                    try (var s = Files.list(shard)) {
                        if (s.findAny().isEmpty()) {
                            Files.deleteIfExists(shard);
                        }
                    } catch (IOException ignored) {
                        // Not empty or already gone.
                    }
                }
            }
        }
        return out;
    }
}
