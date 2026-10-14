package dev.ryvrook.atlas.timeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EventLogTest {
    @TempDir
    Path dir;

    private static final long DAY = LocalDate.of(2026, 10, 13).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();

    @Test
    void boundedPerDayWithOneDroppedLine() throws Exception {
        TimelineStore store = new TimelineStore(dir);
        EventLog log = new EventLog(store, ZoneOffset.UTC, 5, () -> true);
        for (int i = 0; i < 9; i++) {
            log.add("default", DAY + i, "join", 1.4, -2.6, "Alice", null, "Alice joined");
        }
        log.flush();
        for (int i = 0; i < 3; i++) {
            log.add("default", DAY + 100 + i, "leave", null, null, "Alice", null, "Alice left");
        }
        log.flush();
        List<EventLog.Line> lines = log.read("default", "2026-10-13");
        assertEquals(6, lines.size());
        assertEquals(EventLog.DROPPED, lines.get(5).kind());
        assertEquals(1, lines.get(0).x());
        assertEquals(-3, lines.get(0).z());
        assertEquals(6, log.count("default", "2026-10-13"));
    }

    @Test
    void boundSurvivesARestart() throws Exception {
        TimelineStore store = new TimelineStore(dir);
        EventLog first = new EventLog(store, ZoneOffset.UTC, 3, () -> true);
        first.add("default", DAY, "join", null, null, "A", null, "x");
        first.add("default", DAY, "join", null, null, "B", null, "x");
        first.flush();
        EventLog second = new EventLog(store, ZoneOffset.UTC, 3, () -> true);
        for (int i = 0; i < 4; i++) {
            second.add("default", DAY + 1, "join", null, null, "C", null, "x");
        }
        second.flush();
        assertEquals(4, second.read("default", "2026-10-13").size(), "three events and one dropped line");
    }

    @Test
    void daysAndWorldsGoToTheirOwnFiles() throws Exception {
        TimelineStore store = new TimelineStore(dir);
        EventLog log = new EventLog(store, ZoneOffset.UTC, 10, () -> true);
        UUID id = UUID.randomUUID();
        log.add("default", DAY - 1, "death", 5.0, 5.0, "Bob", id, "Bob died");
        log.add("default", DAY, "kill", null, null, "Bob", id, "Bob won");
        log.add("other", DAY, "worldevent.start", null, null, null, null, "A storm began");
        log.flush();
        assertEquals(1, log.read("default", "2026-10-12").size());
        assertEquals(id.toString(), log.read("default", "2026-10-13").get(0).uuid());
        assertEquals("worldevent.start", log.read("other", "2026-10-13").get(0).kind());
        assertEquals(List.of("2026-10-12", "2026-10-13"), store.dates("default"));
    }

    @Test
    void switchedOffWritesNothingAndBadInputIsCleaned() throws Exception {
        TimelineStore store = new TimelineStore(dir);
        AtomicBoolean on = new AtomicBoolean(false);
        EventLog log = new EventLog(store, ZoneOffset.UTC, 10, on::get);
        log.add("default", DAY, "join", null, null, "A", null, "x");
        assertEquals(0, log.flush());
        on.set(true);
        log.add("default", DAY, "Not A Kind!", Double.NaN, 3.0, "A", null, "line one\nline two " + "y".repeat(400));
        log.add(null, DAY, "join", null, null, null, null, "no world");
        log.flush();
        List<EventLog.Line> lines = log.read("default", "2026-10-13");
        assertEquals(1, lines.size());
        assertEquals("other", lines.get(0).kind());
        assertNull(lines.get(0).x(), "a place needs both coordinates");
        assertEquals(EventLog.MAX_TEXT, lines.get(0).text().length());
    }

    @Test
    void aFailedWriteDoesNotUseUpTheDay() throws Exception {
        TimelineStore store = new TimelineStore(dir);
        EventLog log = new EventLog(store, ZoneOffset.UTC, 2, () -> true);
        Path file = store.eventsFile("default", "2026-10-13");
        java.nio.file.Files.createDirectories(file);
        log.add("default", DAY, "join", null, null, "A", null, "x");
        log.add("default", DAY, "join", null, null, "B", null, "x");
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, log::flush);
        java.nio.file.Files.delete(file);
        log.add("default", DAY + 1, "join", null, null, "C", null, "x");
        log.flush();
        assertEquals(1, log.read("default", "2026-10-13").size());
        assertEquals("C", log.read("default", "2026-10-13").get(0).who());
    }

    @Test
    void countBetweenSpansDaysAndIgnoresSnapshots() throws Exception {
        TimelineStore store = new TimelineStore(dir);
        EventLog log = new EventLog(store, ZoneOffset.UTC, 3, () -> true);
        long hour = 60L * 60 * 1000;
        long prev = DAY + 22 * hour;
        log.add("default", prev, EventLog.SNAPSHOT, null, null, null, null, "The scheduled snapshot of the map was taken.");
        log.add("default", prev + 1000, EventLog.SNAPSHOT, null, null, null, null, "An admin took a snapshot of the map.");
        log.flush();
        long next = DAY + 28 * hour;
        assertEquals(0, log.count("default", "2026-10-14"));
        assertEquals(0, log.countBetween("default", prev, next), "snapshot lines are no events");
        log.add("default", DAY + 27 * hour, "join", null, null, "Alice", null, "Alice joined");
        log.add("other", DAY + 27 * hour, "join", null, null, "Bob", null, "Bob joined");
        log.flush();
        assertEquals(1, log.countBetween("default", prev, next), "an event of the next day counts");
        assertEquals(0, log.countBetween("default", next, next + hour));
        assertEquals(0, log.countBetween("default", DAY + 27 * hour, next), "the bounds are exclusive");
        assertEquals(0, log.countBetween("default", 0, next), "no snapshot before means no span");
        // A full day ends with the dropped line at the time of the first event not kept, and it counts.
        for (int i = 0; i < 3; i++) {
            log.add("busy", DAY + hour + i, "leave", null, null, "Alice", null, "Alice left");
        }
        log.add("busy", DAY + 5 * hour, "join", null, null, "Alice", null, "Alice joined");
        log.flush();
        assertEquals(EventLog.DROPPED, log.read("busy", "2026-10-13").get(3).kind());
        assertEquals(4, log.countBetween("busy", DAY, DAY + 6 * hour));
        assertEquals(1, log.countBetween("busy", DAY + 4 * hour, DAY + 6 * hour), "a slot whose window hit the cap is kept");
    }
}
