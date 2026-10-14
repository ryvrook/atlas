package dev.ryvrook.atlas.timeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineRoutesTest {
    @Test
    void hiddenNamesAreReplacedAsWholeWords() {
        assertEquals("A player killed Bobby", TimelineRoutes.replaceName("Bob killed Bobby", "Bob"));
        assertEquals("A player was slain by A player", TimelineRoutes.replaceName("bob was slain by Bob", "Bob"));
        assertEquals("Bobby joined", TimelineRoutes.replaceName("Bobby joined", "Bob"));
        assertEquals("x", TimelineRoutes.replaceName("x", null));
    }

    @Test
    void kindFilterMatchesKindsBelowAPrefix() {
        assertTrue(TimelineRoutes.kindMatches(List.of(), "join"));
        assertTrue(TimelineRoutes.kindMatches(List.of("worldevent"), "worldevent.start"));
        assertTrue(TimelineRoutes.kindMatches(List.of("kill", "join"), "join"));
        assertFalse(TimelineRoutes.kindMatches(List.of("world"), "worldevent.start"));
    }

    @Test
    void kindsFollowTheirLayers() {
        assertEquals("kills", TimelineRoutes.layerOf("death"));
        assertEquals("worldevents", TimelineRoutes.layerOf("worldevent.end"));
        assertEquals("leaderboard", TimelineRoutes.layerOf("feats.first"));
        assertEquals("markers", TimelineRoutes.layerOf("marker.add"));
        assertEquals("feed", TimelineRoutes.layerOf("join"));
        assertNull(TimelineRoutes.layerOf("snapshot"));
        assertTrue(TimelineRoutes.personal("leave"));
        assertFalse(TimelineRoutes.personal("worldevent.start"));
    }

    @Test
    void placesFollowTheHiddenRule() {
        // A hidden player's event has no place for others, whatever its kind.
        assertArrayEquals(new Integer[]{null, null}, TimelineRoutes.place("feats.unlock", 100, 200, false, false, true));
        assertArrayEquals(new Integer[]{null, null}, TimelineRoutes.place("join", 100, 200, false, false, true));
        assertArrayEquals(new Integer[]{null, null}, TimelineRoutes.place("marker.add", 100, 200, false, false, true));
        // Admins and the player themself see the exact place.
        assertArrayEquals(new Integer[]{100, 200}, TimelineRoutes.place("death", 100, 200, true, false, true));
        assertArrayEquals(new Integer[]{100, 200}, TimelineRoutes.place("death", 100, 200, false, true, false));
        // Other people's places are snapped to the chunk corner.
        assertArrayEquals(new Integer[]{96, -32}, TimelineRoutes.place("feats.first", 100, -1, false, false, false));
        assertArrayEquals(new Integer[]{96, 192}, TimelineRoutes.place("leave", 100, 200, false, false, false));
        assertArrayEquals(new Integer[]{100, 200}, TimelineRoutes.place("worldevent.start", 100, 200, false, false, false));
        assertArrayEquals(new Integer[]{null, null}, TimelineRoutes.place("join", null, 200, false, false, false));
        assertTrue(TimelineRoutes.personal("feats.unlock"));
    }

    @Test
    void onlySevenPartPathsAreTiles() {
        assertTrue(TimelineRoutes.isTile(List.of("timeline", "default", "2026-10-13", "tiles", "0", "1", "2.png")));
        assertFalse(TimelineRoutes.isTile(List.of("timeline", "default", "2026-10-13", "stats")));
    }
}
