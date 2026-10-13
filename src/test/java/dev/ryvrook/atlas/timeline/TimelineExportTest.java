package dev.ryvrook.atlas.timeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineExportTest {
    @Test
    void rangesAreCheckedBeforeAnythingIsSent() {
        assertNull(TimelineRoutes.exportRangeError("2026-10-01", "2026-10-13"));
        assertNull(TimelineRoutes.exportRangeError("2026-10-13", "2026-10-13"));
        // 90 days inclusive is the most.
        assertNull(TimelineRoutes.exportRangeError("2026-01-01", "2026-03-31"));
        assertNotNull(TimelineRoutes.exportRangeError("2026-01-01", "2026-04-01"));
        assertNotNull(TimelineRoutes.exportRangeError("2026-10-13", "2026-10-01"));
        assertNotNull(TimelineRoutes.exportRangeError(null, "2026-10-01"));
        assertNotNull(TimelineRoutes.exportRangeError("2026-10-01", null));
        assertNotNull(TimelineRoutes.exportRangeError("2026-13-01", "2026-12-01"));
        assertNotNull(TimelineRoutes.exportRangeError("2026-1-1", "2026-12-01"));
        assertNotNull(TimelineRoutes.exportRangeError("../../x", "2026-12-01"));
    }

    @Test
    void onlyOneExportRunsAtATime() {
        TimelineRoutes r = new TimelineRoutes(null, () -> null);
        assertTrue(r.beginExport());
        assertFalse(r.beginExport());
        r.endExport();
        assertTrue(r.beginExport());
        r.endExport();
    }

    @Test
    void exportPathIsRecognised() {
        assertTrue(TimelineRoutes.isExport(List.of("timeline", "default", "export")));
        assertFalse(TimelineRoutes.isExport(List.of("timeline", "default", "events")));
        assertFalse(TimelineRoutes.isExport(List.of("timeline", "default", "2026-10-13", "export")));
    }
}
