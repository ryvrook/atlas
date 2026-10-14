package dev.ryvrook.atlas.timeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineTest {
    private static final IntSupplier NONE = () -> 0;
    private static final LongSupplier IDLE = () -> 0;
    private static final long HOUR = 60L * 60 * 1000;

    @TempDir
    Path dir;

    private static long at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 10, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static Timeline.Outcome scheduled(int blocks, IntSupplier events) {
        return Timeline.decide(true, true, true, false, blocks, true, events, IDLE);
    }

    @Test
    void aScheduledSnapshotWithNoBlockChangeAndNoEventIsSkipped() {
        assertEquals(Timeline.Outcome.SKIPPED, scheduled(0, NONE));
        assertEquals(Timeline.Outcome.KEPT_FOR_EVENTS, scheduled(0, () -> 2));
        assertEquals(Timeline.Outcome.CHANGED, scheduled(1, NONE));
    }

    @Test
    void aFailedFlushKeepsTheSnapshot() {
        assertEquals(Timeline.Outcome.KEPT_EVENTS_UNKNOWN, scheduled(0, () -> -1));
    }

    @Test
    void withTheEventLogOffTheActivityCountersDecide() {
        IntSupplier never = () -> {
            throw new AssertionError("the event log is off");
        };
        assertEquals(Timeline.Outcome.SKIPPED, Timeline.decide(true, true, true, false, 0, false, never, IDLE));
        assertEquals(Timeline.Outcome.KEPT_FOR_ACTIVITY, Timeline.decide(true, true, true, false, 0, false, never, () -> 1));
        assertEquals(0, Timeline.activityCount(new ActivityTracker.Window(0, 0, 0, 0, 0), 0, 0));
        assertEquals(1, Timeline.activityCount(new ActivityTracker.Window(0, 0, 1, 0, 0), 0, 0), "a kill");
        assertEquals(1, Timeline.activityCount(new ActivityTracker.Window(0, 0, 0, 0, 0), 0, 1), "a block placed");
        assertEquals(1, Timeline.activityCount(new ActivityTracker.Window(1, 1, 0, 0, 0), 0, 0), "a player online");
    }

    @Test
    void someSnapshotsAreAlwaysKept() {
        // An admin snapshot, the setting turned off, a slot that already holds one and the first snapshot of a world.
        assertEquals(Timeline.Outcome.UNCHANGED, Timeline.decide(false, true, true, false, 0, true, NONE, IDLE));
        assertEquals(Timeline.Outcome.UNCHANGED, Timeline.decide(true, false, true, false, 0, true, NONE, IDLE));
        assertEquals(Timeline.Outcome.UNCHANGED, Timeline.decide(true, true, true, true, 0, true, NONE, IDLE));
        assertEquals(Timeline.Outcome.CHANGED, Timeline.decide(true, true, false, false, 0, true, NONE, IDLE));
        assertEquals(Timeline.Outcome.CHANGED, Timeline.decide(false, true, true, false, 5, true, NONE, IDLE));
    }

    @Test
    void theCountersAreOnlyReadWhenTheAnswerDependsOnThem() {
        AtomicInteger asked = new AtomicInteger();
        IntSupplier events = () -> {
            asked.incrementAndGet();
            return 0;
        };
        LongSupplier activity = () -> {
            asked.incrementAndGet();
            return 0;
        };
        Timeline.decide(true, true, true, false, 3, true, events, activity);
        Timeline.decide(false, true, true, false, 0, false, events, activity);
        Timeline.decide(true, false, true, false, 0, true, events, activity);
        Timeline.decide(true, true, false, false, 0, false, events, activity);
        assertEquals(0, asked.get());
        Timeline.decide(true, true, true, false, 0, true, events, activity);
        Timeline.decide(true, true, true, false, 0, false, events, activity);
        assertEquals(2, asked.get());
    }

    @Test
    void aSlotMissedWhileDownRunsAsAScheduledSnapshotThatMayBeSkipped() {
        // The server was down from before 10:00 to 13:30 with perDay 8 from hour 4, so 10:00 was missed.
        long started = at(14, 13, 30);
        assertFalse(Timeline.due(started + 1000, started, "2026-10-14T07", ZoneOffset.UTC, 4, 8));
        long later = started + Timeline.CATCH_UP_DELAY_MS;
        assertTrue(Timeline.due(later, started, "2026-10-14T07", ZoneOffset.UTC, 4, 8));
        // runDaily queues the slot of the moment once, through scheduledRequests.
        String slot = Slots.current(Instant.ofEpochMilli(later).atZone(ZoneOffset.UTC), 4, 8);
        assertEquals("2026-10-14T13", slot);
        List<Timeline.Request> queued = Timeline.scheduledRequests(List.of("default", "orbis"), slot);
        assertEquals(2, queued.size());
        for (Timeline.Request r : queued) {
            assertEquals(slot, r.slot());
            assertNull(r.reply());
            // finish hands decide what scheduled says, so the catch up is skipped like any other when nothing changed.
            assertTrue(Timeline.scheduled(r));
            assertEquals(Timeline.Outcome.SKIPPED,
                    Timeline.decide(Timeline.scheduled(r), true, true, false, 0, true, NONE, IDLE));
        }
        assertFalse(Timeline.due(later, started, slot, ZoneOffset.UTC, 4, 8));
        assertTrue(Timeline.due(at(14, 16, 0), started, slot, ZoneOffset.UTC, 4, 8));
        // An admin request is never scheduled.
        assertFalse(Timeline.scheduled(new Timeline.Request("default", t -> { }, false, slot)));
    }

    @Test
    void statsReachBackToTheSnapshotKeptBefore() {
        long now = at(14, 13, 0);
        assertEquals(ActivityTracker.WINDOW_MS, Timeline.statsWindowMs(now, 0));
        assertEquals(ActivityTracker.WINDOW_MS, Timeline.statsWindowMs(now, now - 3 * HOUR));
        assertEquals(30 * HOUR, Timeline.statsWindowMs(now, now - 30 * HOUR));
        assertEquals(ActivityTracker.MAX_WINDOW_MS, Timeline.statsWindowMs(now, now - 20 * 24 * HOUR));
    }

    @Test
    void activityWindowsOfAnySpanMeetWithoutCountingAnHourTwice() throws Exception {
        ActivityTracker a = new ActivityTracker(dir.resolve("activity.json"));
        long prev = at(12, 13, 0);
        long now = at(14, 13, 0);
        a.death("w", true, false, prev - 10 * 60 * 1000);
        a.death("w", true, false, at(12, 20, 0));
        a.death("w", true, false, at(13, 20, 0));
        a.death("w", false, true, now - 60 * 1000);
        assertEquals(1, a.window("w", prev).kills());
        // 48 hours since the snapshot before, as after a quiet day was dropped.
        ActivityTracker.Window w = a.window("w", now, Timeline.statsWindowMs(now, prev));
        assertEquals(2, w.kills(), "the kill of the hour before prev belongs to the earlier stats");
        assertEquals(1, w.deaths());
        assertEquals(1, a.window("w", now).kills(), "24 hours alone would miss a day");
        // since counts the hour of its start, for the skip check that errs toward keeping.
        assertEquals(2, a.since("w", prev, now).kills());
        assertEquals(3, a.since("w", prev - 10 * 60 * 1000, now).kills());
        // Eight days of buckets survive the prune, enough for the longest window.
        a.prune(now);
        a.save();
        ActivityTracker b = new ActivityTracker(dir.resolve("activity.json"));
        b.load();
        assertEquals(3, b.since("w", prev - HOUR, now).kills());
        a.prune(now + 9 * 24 * HOUR);
        assertEquals(0, a.since("w", prev, now).kills());
    }

    @Test
    void slotText() {
        assertEquals("2026-10-14 13:00", Timeline.slotText("2026-10-14T13"));
    }
}
