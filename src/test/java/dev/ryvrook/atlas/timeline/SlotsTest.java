package dev.ryvrook.atlas.timeline;

import dev.ryvrook.atlas.config.AtlasConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotsTest {
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static ZonedDateTime at(String date, int hour, int minute) {
        return LocalDate.parse(date).atTime(hour, minute).atZone(UTC);
    }

    @Test
    void slotNamesAreADateAndAnHourOrAPlainDate() {
        assertTrue(Slots.valid("2026-10-13T16"));
        assertTrue(Slots.valid("2026-10-13T00"));
        assertTrue(Slots.valid("2026-10-13"));
        assertFalse(Slots.valid("2026-10-13T24"));
        assertFalse(Slots.valid("2026-10-13T7"));
        assertFalse(Slots.valid("2026-10-13 16"));
        assertFalse(Slots.valid("2026-02-30T04"));
        assertFalse(Slots.valid("../2026-10-13"));
        assertFalse(Slots.valid(null));
        assertEquals("2026-10-13", Slots.date("2026-10-13T16"));
        assertEquals(16, Slots.hour("2026-10-13T16"));
        assertEquals(-1, Slots.hour("2026-10-13"));
        assertEquals("2026-10-13T04", Slots.id("2026-10-13", 4));
    }

    @Test
    void theHoursAreEvenlySpacedFromTheStartHour() {
        assertArrayEquals(new int[]{4, 10, 16, 22}, Slots.hours(4, 4));
        assertArrayEquals(new int[]{4, 10, 16, 22}, Slots.hours(10, 4));
        assertArrayEquals(new int[]{4}, Slots.hours(4, 1));
        assertArrayEquals(new int[]{1, 9, 17}, Slots.hours(17, 3));
        assertEquals(24, Slots.hours(4, 24).length);
        assertEquals("04:00, 10:00, 16:00 and 22:00", Slots.schedule(Slots.hours(4, 4)));
        assertEquals("04:00", Slots.schedule(Slots.hours(4, 1)));
    }

    @Test
    void theCurrentSlotIsTheLatestStartAndBeforeTheFirstItIsYesterdaysLast() {
        assertEquals("2026-10-13T10", Slots.current(at("2026-10-13", 15, 59), 4, 4));
        assertEquals("2026-10-13T16", Slots.current(at("2026-10-13", 16, 0), 4, 4));
        assertEquals("2026-10-12T22", Slots.current(at("2026-10-13", 2, 30), 4, 4));
        assertEquals("2026-10-13T13", Slots.current(at("2026-10-13", 13, 5), 4, 24));
        assertEquals(at("2026-10-13", 16, 0), Slots.next(at("2026-10-13", 10, 0), 4, 4));
        assertEquals(at("2026-10-14", 4, 0), Slots.next(at("2026-10-13", 23, 0), 4, 4));
    }

    @Test
    void eachEventOfADayBelongsToOneSlot() {
        List<String> day = List.of("2026-10-13T04", "2026-10-13T10", "2026-10-13T16");
        assertArrayEquals(new int[]{0, 10}, Slots.window("2026-10-13T04", day));
        assertArrayEquals(new int[]{10, 16}, Slots.window("2026-10-13T10", day));
        assertArrayEquals(new int[]{16, 24}, Slots.window("2026-10-13T16", day));
        assertArrayEquals(new int[]{0, 24}, Slots.window("2026-10-13", day));
        assertArrayEquals(new int[]{0, 24}, Slots.window("2026-10-13T16", List.of("2026-10-13T16")));
    }

    @Test
    void perDayFailsClosed() throws Exception {
        assertEquals(4, AtlasConfig.parse(null).timelinePerDay());
        assertEquals(24, AtlasConfig.parse("{\"timeline\":{\"perDay\":24}}").timelinePerDay());
        for (String v : new String[]{"0", "5", "7", "25", "-4", "2.5", "\"4\""}) {
            assertThrows(AtlasConfig.InvalidConfigException.class, () -> AtlasConfig.parse("{\"timeline\":{\"perDay\":" + v + "}}"), v);
        }
        assertTrue(AtlasConfig.parse("{\"timeline\":{\"hour\":4}}").addedKeys.contains("timeline.perDay"));
    }

    // ---- the store ----------------------------------------------------------------

    @TempDir
    Path dir;

    private static void slot(TimelineStore s, String world, String slot, byte[]... tiles) throws Exception {
        Map<String, String> map = new java.util.HashMap<>();
        for (int i = 0; i < tiles.length; i++) {
            String h = BlobStore.hash(tiles[i]);
            s.blobs().put(h, tiles[i]);
            map.put(Manifest.key(0, i, 0), h);
        }
        String prev = s.previousSlot(world, slot);
        Manifest p = prev == null ? null : s.manifest(world, prev);
        s.write(new Manifest(world, slot, 1L, prev, false, 0, 0, map, Manifest.diff(p == null ? null : p.tiles(), map)), null);
    }

    @Test
    void aPlainDateMeansTheLastSlotAndAnOldDayFolderIsASlotOfItsOwn() throws Exception {
        TimelineStore s = new TimelineStore(dir);
        slot(s, "w", "2026-10-11", new byte[]{1});
        slot(s, "w", "2026-10-12T04", new byte[]{1});
        slot(s, "w", "2026-10-12T10", new byte[]{2});
        assertEquals(List.of("2026-10-11", "2026-10-12T04", "2026-10-12T10"), s.snapshotSlots("w"));
        assertEquals(List.of("2026-10-11", "2026-10-12"), s.snapshotDates("w"));
        assertEquals("2026-10-12T10", s.resolve("w", "2026-10-12"));
        assertEquals("2026-10-11", s.resolve("w", "2026-10-11"));
        assertEquals(s.manifest("w", "2026-10-12T10").tiles(), s.manifest("w", "2026-10-12").tiles());
        assertEquals("2026-10-12T04", s.previousSlot("w", "2026-10-12T10"));
        assertEquals("2026-10-11", s.previousSlot("w", "2026-10-12T04"));
        Manifest m = s.manifest("w", "2026-10-12T10");
        assertEquals("2026-10-12T04", m.previous());
        assertEquals(1, m.changed().size());
        assertEquals(Map.of("w", 2), s.daysHeld());
        assertEquals(Map.of("w", 3), s.slotsHeld());
    }

    @Test
    void retentionCountsDaysAndRemovesEverySlotOfADay() throws Exception {
        TimelineStore s = new TimelineStore(dir);
        LocalDate today = LocalDate.of(2026, 10, 13);
        slot(s, "w", "2026-10-01T04", new byte[]{1});
        slot(s, "w", "2026-10-01T16", new byte[]{2});
        slot(s, "w", "2026-10-12T04", new byte[]{3});
        slot(s, "w", "2026-10-13T04", new byte[]{3});
        TimelineStore.PruneResult r = s.prune(today, 5, 0);
        assertEquals(1, r.days());
        assertFalse(s.slotHasSnapshot("w", "2026-10-01T04"));
        assertFalse(s.slotHasSnapshot("w", "2026-10-01T16"));
        assertTrue(s.slotPruned("w", "2026-10-01T04"));
        assertTrue(s.slotPruned("w", "2026-10-01T16"));
        assertTrue(s.slotHasSnapshot("w", "2026-10-12T04"));
        assertFalse(s.blobs().has(BlobStore.hash(new byte[]{1})));
        assertFalse(s.blobs().has(BlobStore.hash(new byte[]{2})));
        assertTrue(s.blobs().has(BlobStore.hash(new byte[]{3})));
        assertTrue(s.dates("w").contains("2026-10-01"));
    }

    @Test
    void theManifestKeepsItsSlotAndItsDate() {
        Manifest m = new Manifest("w", "2026-10-13T16", 5L, "2026-10-13T10", false, 0, 0, Map.of(), List.of());
        assertEquals("2026-10-13", m.toJson().get("date").getAsString());
        assertEquals("2026-10-13T16", m.toJson().get("slot").getAsString());
        assertEquals("2026-10-13T16", Manifest.fromJson(m.toJson()).date());
        com.google.gson.JsonObject old = m.toJson();
        old.remove("slot");
        old.addProperty("date", "2026-10-11");
        assertEquals("2026-10-11", Manifest.fromJson(old).date());
        assertNull(new TimelineStore(dir).previousSlot("none", "2026-10-13T16"));
        assertTrue(Files.notExists(dir.resolve("worlds").resolve("none")));
    }
}
