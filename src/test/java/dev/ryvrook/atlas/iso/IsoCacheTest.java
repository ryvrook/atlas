package dev.ryvrook.atlas.iso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsoCacheTest {
    private static IsoCache.Entry entry(int size, long at, boolean partial) {
        byte[] b = new byte[size];
        b[0] = (byte) size;
        return new IsoCache.Entry(b, "\"" + size + "\"", at, partial);
    }

    @Test
    void keyNamesWorldCentreRadiusDirectionScaleAndBucket() {
        IsoView v = new IsoView("my world", 1, 2, 3, 90, 2);
        String k = IsoCache.key(IsoCache.folder(v.world()), v, 7);
        assertTrue(k.startsWith("my_world-"));
        assertTrue(k.endsWith("/1_2_r3_a90_s2_t7.png"));
        assertFalse(k.equals(IsoCache.key(IsoCache.folder(v.world()), v, 8)));
    }

    @Test
    void diskCopySurvivesARestartAndBucketsSeparate(@TempDir Path dir) throws Exception {
        IsoView v = new IsoView("default", 0, 0, 4, 0, 2);
        IsoCache c = new IsoCache(dir, 1 << 20);
        c.put(v, 5, entry(100, 1000, false), true);
        assertNotNull(c.get(v, 5, 2000));
        assertNull(c.get(v, 6, 2000));
        IsoCache again = new IsoCache(dir, 1 << 20);
        again.load();
        IsoCache.Entry e = again.get(v, 5, 2000);
        assertNotNull(e);
        assertEquals(100, e.png().length);
        assertEquals(1, again.diskFiles());
    }

    @Test
    void partialViewsStayInMemoryOnlyAndExpire(@TempDir Path dir) throws Exception {
        IsoView v = new IsoView("default", 0, 0, 4, 0, 2);
        IsoCache c = new IsoCache(dir, 1 << 20);
        c.put(v, 1, entry(50, 1000, true), true);
        assertEquals(0, c.diskFiles());
        assertNotNull(c.get(v, 1, 1000 + IsoCache.PARTIAL_MS - 1));
        assertNull(c.get(v, 1, 1000 + IsoCache.PARTIAL_MS + 1));
    }

    @Test
    void aSavedChunkDropsEveryViewThatHoldsIt(@TempDir Path dir) throws Exception {
        IsoCache c = new IsoCache(dir, 1 << 20);
        IsoView near = new IsoView("default", 0, 0, 2, 0, 2);
        IsoView far = new IsoView("default", 20, 20, 2, 0, 2);
        IsoView other = new IsoView("other", 0, 0, 2, 0, 2);
        c.put(near, 1, entry(10, 1, false), true);
        c.put(far, 1, entry(11, 1, false), true);
        c.put(other, 1, entry(12, 1, false), true);
        assertEquals(1, c.invalidate("default", 2, -2));
        assertNull(c.get(near, 1, 2));
        assertNotNull(c.get(far, 1, 2));
        assertNotNull(c.get(other, 1, 2));
        assertEquals(2, c.diskFiles());
        assertFalse(Files.exists(dir.resolve("default").resolve(near.fileName(1))));
    }

    @Test
    void pruneRemovesOldBucketsThenTheOldestFiles(@TempDir Path dir) throws Exception {
        IsoCache c = new IsoCache(dir, 1 << 20);
        IsoView a = new IsoView("default", 0, 0, 1, 0, 2);
        IsoView b = new IsoView("default", 1, 0, 1, 0, 2);
        IsoView d = new IsoView("default", 2, 0, 1, 0, 2);
        IsoView stale = new IsoView("default", 3, 0, 1, 0, 2);
        c.put(a, 10, entry(100, 1000, false), true);
        c.put(b, 10, entry(100, 3000, false), true);
        c.put(d, 9, entry(100, 2000, false), true);
        c.put(stale, 7, entry(100, 5000, false), true);
        // Bucket 7 is older than the one before the current 10. Then 300 bytes are over a 250 byte cap,
        // so the oldest file, a at 1000, goes as well.
        assertEquals(2, c.prune(10, 250));
        assertNull(c.get(stale, 7, 6000));
        assertNull(c.get(a, 10, 6000));
        assertNotNull(c.get(b, 10, 6000));
        assertNotNull(c.get(d, 9, 6000));
        assertEquals(200, c.diskBytes());
    }

    @Test
    void memoryKeepsTheRecentlyUsedWithinItsBudget(@TempDir Path dir) throws Exception {
        IsoCache c = new IsoCache(dir, 250);
        IsoView a = new IsoView("default", 0, 0, 1, 0, 2);
        IsoView b = new IsoView("default", 1, 0, 1, 0, 2);
        IsoView d = new IsoView("default", 2, 0, 1, 0, 2);
        c.put(a, 1, entry(100, 1, false), false);
        c.put(b, 1, entry(100, 1, false), false);
        c.get(a, 1, 2);
        c.put(d, 1, entry(100, 1, false), false);
        assertTrue(c.memoryBytes() <= 250);
        assertNotNull(c.get(a, 1, 2));
        assertNull(c.get(b, 1, 2));
    }

    @Test
    void leftoverTemporaryFilesAreRemovedOnLoad(@TempDir Path dir) throws Exception {
        Path w = Files.createDirectories(dir.resolve("default"));
        Path tmp = Files.writeString(w.resolve("0_0_r1_a0_s2_t1.png.123-4.tmp"), "x");
        Files.setLastModifiedTime(tmp, FileTime.fromMillis(1));
        new IsoCache(dir, 1000).load();
        assertFalse(Files.exists(tmp));
    }
    @Test
    void viewsWithoutAFileLeaveTheIndexWhenTheyLeaveMemory(@TempDir Path dir) throws Exception {
        IsoCache c = new IsoCache(dir, 250);
        for (int i = 0; i < 50; i++) {
            c.put(new IsoView("default", i, 0, 1, 0, 2), 1, entry(100, 1000, false), false);
        }
        assertEquals(2, c.memoryEntries());
        assertEquals(2, c.keys().size());
    }

    @Test
    void aViewLargerThanTheMemoryBudgetIsKeptUntilTheNextOne(@TempDir Path dir) throws Exception {
        IsoCache c = new IsoCache(dir, 100);
        IsoView big = new IsoView("default", 0, 0, 16, 0, 1);
        c.put(big, 1, entry(500, 1000, true), false);
        assertNotNull(c.get(big, 1, 1000));
        c.put(new IsoView("default", 1, 0, 16, 0, 1), 1, entry(500, 1000, true), false);
        assertNull(c.get(big, 1, 1000));
        assertEquals(1, c.memoryEntries());
    }
}
