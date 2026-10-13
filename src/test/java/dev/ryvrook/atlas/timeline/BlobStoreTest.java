package dev.ryvrook.atlas.timeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlobStoreTest {
    @TempDir
    Path dir;

    @Test
    void storesOnceByContent() throws Exception {
        BlobStore s = new BlobStore(dir.resolve("blobs"));
        byte[] a = "tile a".getBytes(StandardCharsets.UTF_8);
        String h = BlobStore.hash(a);
        assertTrue(BlobStore.validHash(h));
        assertTrue(s.put(h, a));
        assertFalse(s.put(h, a), "the same bytes are not written twice");
        assertEquals(dir.resolve("blobs").resolve(h.substring(0, 2)).resolve(h + ".png"), s.path(h));
        assertArrayEquals(a, s.read(h));
        assertNull(s.read(BlobStore.hash(new byte[]{1})));
    }

    @Test
    void collectKeepsOnlyReferencedBlobsAndClearsTemporaryFiles() throws Exception {
        BlobStore s = new BlobStore(dir.resolve("blobs"));
        byte[] a = {1, 2, 3};
        byte[] b = {4, 5, 6};
        String ha = BlobStore.hash(a);
        String hb = BlobStore.hash(b);
        s.put(ha, a);
        s.put(hb, b);
        Path tmp = s.path(ha).resolveSibling(ha + ".7.tmp");
        Files.write(tmp, a);
        long[] r = s.collect(Set.of(ha));
        assertEquals(1, r[0]);
        assertEquals(3, r[1]);
        assertTrue(s.has(ha));
        assertFalse(s.has(hb));
        assertFalse(Files.exists(tmp));
        Map<String, Long> left = new HashMap<>();
        s.forEach(left::put);
        assertEquals(Map.of(ha, 3L), left);
    }

    @Test
    void rejectsMalformedHashes() {
        assertFalse(BlobStore.validHash(null));
        assertFalse(BlobStore.validHash("../../etc"));
        assertFalse(BlobStore.validHash("A".repeat(64)));
    }
}
