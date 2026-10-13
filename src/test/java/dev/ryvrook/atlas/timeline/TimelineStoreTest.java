package dev.ryvrook.atlas.timeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineStoreTest {
    @TempDir
    Path dir;

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    /** Writes a day whose tiles are the given payloads, each stored as a blob. */
    private static void day(TimelineStore s, String world, LocalDate date, byte[]... tiles) throws Exception {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < tiles.length; i++) {
            String h = BlobStore.hash(tiles[i]);
            s.blobs().put(h, tiles[i]);
            map.put(Manifest.key(0, i, 0), h);
        }
        String prev = s.previousSlot(world, date.toString());
        Manifest p = prev == null ? null : s.manifest(world, prev);
        s.write(new Manifest(world, date.toString(), 1L, prev, false, 0, 0, map,
                Manifest.diff(p == null ? null : p.tiles(), map)), null);
    }

    @Test
    void retentionRemovesOldDaysLeavesTombstonesAndCollectsBlobs() throws Exception {
        TimelineStore s = new TimelineStore(dir);
        byte[] shared = {1};
        byte[] old = {2};
        byte[] fresh = {3};
        day(s, "default", TODAY.minusDays(10), shared, old);
        day(s, "default", TODAY.minusDays(1), shared, fresh);
        day(s, "default", TODAY, shared, fresh);
        Files.createDirectories(s.eventsFile("default", TODAY.minusDays(10).toString()).getParent());
        Files.writeString(s.eventsFile("default", TODAY.minusDays(10).toString()), "{}\n");

        TimelineStore.PruneResult r = s.prune(TODAY, 5, 0);
        assertEquals(1, r.days());
        assertEquals(1, r.blobs(), "only the blob of the removed day goes");
        String gone = TODAY.minusDays(10).toString();
        assertFalse(s.hasSnapshot("default", gone));
        assertTrue(s.tombstone("default", gone), "a pruned day leaves a tombstone");
        assertFalse(Files.exists(s.eventsFile("default", gone)));
        assertTrue(s.dates("default").contains(gone), "the tombstone keeps the date listed");
        assertTrue(s.blobs().has(BlobStore.hash(shared)));
        assertFalse(s.blobs().has(BlobStore.hash(old)));
        assertTrue(s.blobs().has(BlobStore.hash(fresh)));
    }

    @Test
    void zeroRetentionKeepsTheNewestDayOfEachWorld() throws Exception {
        TimelineStore s = new TimelineStore(dir);
        day(s, "default", TODAY.minusDays(2), new byte[]{1});
        day(s, "default", TODAY.minusDays(1), new byte[]{2});
        day(s, "other", TODAY.minusDays(5), new byte[]{3});
        s.prune(TODAY, 0, 0);
        assertEquals(List.of(TODAY.minusDays(1).toString()), s.snapshotDates("default"));
        assertEquals(List.of(TODAY.minusDays(5).toString()), s.snapshotDates("other"));
        assertFalse(s.blobs().has(BlobStore.hash(new byte[]{1})));
    }

    @Test
    void diskCapRemovesOldestDaysFirst() throws Exception {
        TimelineStore s = new TimelineStore(dir);
        byte[] big1 = new byte[40_000];
        byte[] big2 = new byte[40_000];
        byte[] big3 = new byte[40_000];
        big1[0] = 1;
        big2[0] = 2;
        big3[0] = 3;
        day(s, "default", TODAY.minusDays(3), big1);
        day(s, "default", TODAY.minusDays(2), big2);
        day(s, "default", TODAY.minusDays(1), big3);
        TimelineStore.PruneResult r = s.prune(TODAY, 90, 100_000);
        assertEquals(1, r.days());
        assertTrue(r.totalBytes() <= 100_000);
        assertEquals(List.of(TODAY.minusDays(2).toString(), TODAY.minusDays(1).toString()), s.snapshotDates("default"));
        assertFalse(s.blobs().has(BlobStore.hash(big1)));
        assertTrue(s.blobs().has(BlobStore.hash(big2)));
    }

    @Test
    void previousDateAndWorldNames() throws Exception {
        TimelineStore s = new TimelineStore(dir);
        day(s, "a b", TODAY.minusDays(3), new byte[]{1});
        day(s, "a b", TODAY.minusDays(1), new byte[]{1});
        assertEquals(TODAY.minusDays(3).toString(), s.previousSlot("a b", TODAY.minusDays(1).toString()));
        assertEquals(List.of("a b"), s.worlds());
        Manifest m = s.manifest("a b", TODAY.minusDays(1).toString());
        assertNotNull(m);
        assertTrue(m.changed().isEmpty(), "an unchanged day lists nothing as changed");
    }

    @Test
    void zeroRetentionKeepsTodaysEventsBeforeItsSnapshot() throws Exception {
        TimelineStore s = new TimelineStore(dir);
        day(s, "default", TODAY.minusDays(1), new byte[]{1});
        Path today = s.eventsFile("default", TODAY.toString());
        Files.createDirectories(today.getParent());
        Files.writeString(today, "{}\n");
        s.prune(TODAY, 0, 0);
        assertTrue(Files.exists(today), "today is still being written");
        assertEquals(List.of(TODAY.minusDays(1).toString()), s.snapshotDates("default"));
    }

    @Test
    void oldTombstonesAreForgotten() throws Exception {
        TimelineStore s = new TimelineStore(dir);
        LocalDate old = TODAY.minusDays(600);
        day(s, "default", old, new byte[]{1});
        day(s, "default", TODAY.minusDays(1), new byte[]{2});
        s.prune(TODAY.minusDays(500), 30, 0);
        assertTrue(s.tombstone("default", old.toString()));
        s.prune(TODAY, 30, 0);
        assertFalse(s.dates("default").contains(old.toString()), "a tombstone far past the retention goes");
    }
}
