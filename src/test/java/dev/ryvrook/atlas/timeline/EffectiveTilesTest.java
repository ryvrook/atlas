package dev.ryvrook.atlas.timeline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Slots written before tiles were carried over, read with the tiles of the slots before them. */
class EffectiveTilesTest {
    @TempDir
    Path dir;

    private static String put(TimelineStore s, byte[] data) throws Exception {
        String h = BlobStore.hash(data);
        s.blobs().put(h, data);
        return h;
    }

    /** A slot as Atlas wrote them before, holding only the tiles that were in the folder. */
    private static void legacy(TimelineStore s, String slot, Map<String, String> tiles) throws Exception {
        String prev = s.previousSlot("w", slot);
        Manifest p = prev == null ? null : s.manifest("w", prev);
        s.write(new Manifest("w", slot, 1L, prev, false, 0, 0, tiles, Manifest.diff(p == null ? null : p.tiles(), tiles)),
                null);
    }

    @Test
    void aSlotShowsTheTilesOfTheSlotsBeforeIt() throws Exception {
        TimelineStore s = new TimelineStore(dir);
        String a = put(s, new byte[]{1});
        String b = put(s, new byte[]{2});
        String c = put(s, new byte[]{3});
        legacy(s, "2026-10-13", Map.of("0/0/0", a, "-2/0/0", a, "1/0/0", a));
        legacy(s, "2026-10-13T22", Map.of("0/0/0", a, "-1/0/0", b));
        legacy(s, "2026-10-14T04", Map.of("0/0/0", c));
        assertEquals(a, s.tileHash("w", "2026-10-14T04", "-2/0/0"));
        assertEquals(b, s.tileHash("w", "2026-10-14T04", "-1/0/0"));
        assertEquals(c, s.tileHash("w", "2026-10-14T04", "0/0/0"));
        assertEquals(a, s.tileHash("w", "2026-10-13T22", "1/0/0"));
        assertNull(s.tileHash("w", "2026-10-13T22", "-1/5/5"));
        // The stored list counts the zoom -2 and zoom 1 tiles of 22:00 as new, the effective one does not.
        assertEquals(List.of("-1/0/0"), s.changed("w", "2026-10-13T22"));
        assertEquals(List.of("0/0/0"), s.changed("w", "2026-10-14T04"));
        TimelineStore.Counts n = s.counts("w", "2026-10-14T04");
        assertEquals(4, n.tiles());
        assertEquals(3, n.carried());
        assertEquals(1, n.changed());
    }

    @Test
    void aCompleteSlotEndsTheWalk() throws Exception {
        TimelineStore s = new TimelineStore(dir);
        String a = put(s, new byte[]{1});
        String b = put(s, new byte[]{2});
        legacy(s, "2026-10-12", Map.of("1/0/0", a, "0/0/0", a));
        // A complete slot that no longer shows 1/0/0, as after a purge.
        s.write(new Manifest("w", "2026-10-13T04", 1L, "2026-10-12", false, 0, 0, Map.of("0/0/0", a), List.of(), 0, 0,
                true), null);
        legacy(s, "2026-10-13T10", Map.of("0/0/0", b));
        assertNull(s.tileHash("w", "2026-10-13T10", "1/0/0"));
        assertEquals(b, s.tileHash("w", "2026-10-13T10", "0/0/0"));
        TimelineStore.Counts n = s.counts("w", "2026-10-13T04");
        assertEquals(new TimelineStore.Counts(1, 0, 0), n);
    }

    @Test
    void anotherWriteIsSeenAtOnce() throws Exception {
        TimelineStore s = new TimelineStore(dir);
        String a = put(s, new byte[]{1});
        String b = put(s, new byte[]{2});
        legacy(s, "2026-10-13T04", Map.of("1/0/0", a));
        legacy(s, "2026-10-13T10", Map.of("0/0/0", a));
        assertEquals(a, s.tileHash("w", "2026-10-13T10", "1/0/0"));
        legacy(s, "2026-10-13T04", Map.of("1/0/0", b));
        assertEquals(b, s.tileHash("w", "2026-10-13T10", "1/0/0"));
    }

    @Test
    void theReachDoesNotDependOnTheOrderOfRequests() throws Exception {
        String a;
        {
            TimelineStore s = new TimelineStore(dir);
            a = put(s, new byte[]{1});
            legacy(s, "2026-09-01T00", Map.of("1/0/0", a));
            // Seventy slots later the tile is still shown, read cold or after every slot before it.
            LocalDate d = LocalDate.of(2026, 9, 1);
            for (int i = 1; i <= 70; i++) {
                String slot = Slots.id(d.plusDays(i / 4).toString(), (i % 4) * 6);
                legacy(s, slot, Map.of("0/0/0", put(s, new byte[]{(byte) (i + 10)})));
            }
        }
        List<String> slots = new TimelineStore(dir).snapshotSlots("w");
        String last = slots.get(slots.size() - 1);
        TimelineStore cold = new TimelineStore(dir);
        assertEquals(a, cold.tileHash("w", last, "1/0/0"));
        TimelineStore warm = new TimelineStore(dir);
        for (String slot : slots) {
            assertEquals(a, warm.tileHash("w", slot, "1/0/0"), slot);
        }
        assertEquals(cold.effectiveTiles("w", last), warm.effectiveTiles("w", last));
    }

    @Test
    void aSlotWhoseNamedPreviousIsGoneIsComparedWithTheOneBeforeIt() throws Exception {
        TimelineStore s = new TimelineStore(dir);
        String a = put(s, new byte[]{1});
        String b = put(s, new byte[]{2});
        legacy(s, "2026-10-12T04", Map.of("0/0/0", a, "0/1/0", a));
        legacy(s, "2026-10-13T04", Map.of("0/0/0", b));
        legacy(s, "2026-10-14T04", Map.of("0/0/0", b, "0/1/0", a));
        java.nio.file.Files.delete(s.worldDir("w").resolve("2026-10-13T04").resolve(TimelineStore.TILES));
        TimelineStore fresh = new TimelineStore(dir);
        assertEquals("2026-10-12T04", fresh.comparedWith("w", "2026-10-14T04"));
        assertEquals(List.of("0/0/0"), fresh.changed("w", "2026-10-14T04"));
        assertNull(fresh.comparedWith("w", "2026-10-12T04"));
    }

    @Test
    void theExportHoldsEveryTileASlotShows() throws Exception {
        TimelineStore s = new TimelineStore(dir);
        String a = put(s, new byte[]{1});
        String b = put(s, new byte[]{2});
        legacy(s, "2026-10-12T22", Map.of("-1/0/0", a));
        legacy(s, "2026-10-13T04", Map.of("0/0/0", b));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] counts;
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            // Only the second day is exported. Its tile from the day before must come with it.
            counts = TimelineRoutes.writeExport(zip, s, "w", LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 13));
        }
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                entries.put(e.getName(), in.readAllBytes());
            }
        }
        assertEquals(1, counts[0]);
        assertEquals(2, counts[1]);
        JsonObject m = JsonParser.parseString(new String(entries.get("days/2026-10-13T04/tiles.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        Set<String> keys = new HashSet<>(m.getAsJsonObject("tiles").keySet());
        assertEquals(Set.of("-1/0/0", "0/0/0"), keys);
        assertEquals(1, m.get("carried").getAsInt());
        assertTrue(entries.containsKey("blobs/" + a + ".png"));
        assertTrue(entries.containsKey("blobs/" + b + ".png"));
    }
}
