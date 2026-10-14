package dev.ryvrook.atlas.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSettingsTest {
    @Test
    void defaults() throws Exception {
        AtlasConfig c = AtlasConfig.parse(null);
        assertTrue(c.worldEnabled("anything"));
        assertNull(c.world("anything").hidden);
        assertNull(c.world("anything").renderLimit);
        assertEquals("", c.defaultWorld);
        assertTrue(c.timelineEnabled());
        assertEquals(4, c.timelineHour());
        assertEquals(90, c.timelineRetainDays());
        assertEquals(2048, c.timelineMaxDiskMb());
        assertTrue(c.timelineEvents());
        assertTrue(c.timelineSkipUnchanged());
        assertEquals(Visibility.PUBLIC, c.visibilityOf("timeline"));
        assertEquals(Visibility.PUBLIC, c.visibilityOf("layers"));
        assertFalse(c.hidePlayersCrouching);
        assertTrue(c.hidePlayersInvisible);
        assertTrue(c.addedKeys.contains("worlds"));
        assertTrue(c.addedKeys.contains("timeline"));
    }

    @Test
    void readsWorlds() throws Exception {
        AtlasConfig c = AtlasConfig.parse("{\"defaultWorld\": \" lobby \", \"worlds\": {"
                + "\"lobby\": {\"displayName\": \"The Lobby\", \"hidden\": false},"
                + "\"old\": {\"enabled\": false},"
                + "\"arena\": {\"renderLimit\": {\"x\": 100, \"z\": -50, \"radius\": 300}, \"hidden\": true}}}");
        assertEquals("lobby", c.defaultWorld);
        assertEquals("The Lobby", c.world("lobby").displayName);
        assertEquals(Boolean.FALSE, c.world("lobby").hidden);
        assertFalse(c.worldEnabled("old"));
        assertTrue(c.worldEnabled("lobby"));
        AtlasConfig.RenderLimit r = c.world("arena").renderLimit;
        assertEquals(300, r.radius);
        assertTrue(r.contains(100, 250));
        assertFalse(r.contains(100, 251));
        assertTrue(r.touches(390, -60, 500, 0));
        assertFalse(r.touches(1000, 1000, 1100, 1100));
        assertTrue(c.world("arena").inside(100, -50));
    }

    @Test
    void readsTimeline() throws Exception {
        AtlasConfig c = AtlasConfig.parse("{\"timeline\": {\"enabled\": false, \"hour\": 23, \"retainDays\": 0}}");
        assertFalse(c.timelineEnabled());
        assertEquals(23, c.timelineHour());
        assertEquals(0, c.timelineRetainDays());
        assertEquals(2048, c.timelineMaxDiskMb());
        assertTrue(c.addedKeys.contains("timeline.maxDiskMb"));
        assertTrue(c.addedKeys.contains("timeline.events"));
        assertTrue(c.addedKeys.contains("timeline.skipUnchanged"));
        assertTrue(c.timelineSkipUnchanged());
    }

    @Test
    void skipUnchangedIsFilledInAndRead() throws Exception {
        // A file from 1.4.0 gets the new key with its default, and an owner can turn it off.
        AtlasConfig old = AtlasConfig.parse("{\"timeline\": {\"enabled\": true, \"hour\": 4, \"perDay\": 8, "
                + "\"retainDays\": 90, \"maxDiskMb\": 2048, \"events\": true}}");
        assertTrue(old.timelineSkipUnchanged());
        assertEquals(java.util.List.of("timeline.skipUnchanged"), old.addedKeys.stream()
                .filter(k -> k.startsWith("timeline")).toList());
        AtlasConfig off = AtlasConfig.parse("{\"timeline\": {\"skipUnchanged\": false}}");
        assertFalse(off.timelineSkipUnchanged());
        assertFalse(off.addedKeys.contains("timeline.skipUnchanged"));
        assertFalse(off.unknownKeys.contains("timeline.skipUnchanged"));
    }

    @Test
    void badWorldsFailClosed() {
        String[] bad = {
                "{\"worlds\": []}",
                "{\"worlds\": {\"a\": true}}",
                "{\"worlds\": {\"a\": {\"enabled\": \"no\"}}}",
                "{\"worlds\": {\"a\": {\"enabled\": null}}}",
                "{\"worlds\": {\"a\": {\"displayName\": 5}}}",
                "{\"worlds\": {\"a\": {\"hidden\": \"yes\"}}}",
                "{\"worlds\": {\"a\": {\"renderLimit\": 5}}}",
                "{\"worlds\": {\"a\": {\"renderLimit\": {\"x\": 1, \"z\": 2}}}}",
                "{\"worlds\": {\"a\": {\"renderLimit\": {\"x\": 1, \"z\": 2, \"radius\": 0}}}}",
                "{\"worlds\": {\"a\": {\"renderLimit\": {\"x\": \"1\", \"z\": 2, \"radius\": 5}}}}",
                "{\"worlds\": {\" \": {}}}",
                "{\"defaultWorld\": 3}",
                "{\"timeline\": 1}",
                "{\"timeline\": null}",
                "{\"timeline\": {\"hour\": 24}}",
                "{\"timeline\": {\"hour\": -1}}",
                "{\"timeline\": {\"hour\": 4.5}}",
                "{\"timeline\": {\"retainDays\": -1}}",
                "{\"timeline\": {\"maxDiskMb\": -5}}",
                "{\"timeline\": {\"events\": \"true\"}}",
                "{\"timeline\": {\"skipUnchanged\": \"false\"}}",
                "{\"timeline\": {\"skipUnchanged\": 0}}",
                "{\"timeline\": {\"skipUnchanged\": null}}",
                "{\"hidePlayersCrouching\": \"yes\"}",
        };
        for (String text : bad) {
            assertThrows(AtlasConfig.InvalidConfigException.class, () -> AtlasConfig.parse(text), text);
        }
    }

    @Test
    void unknownNestedKeysAreReported() throws Exception {
        AtlasConfig c = AtlasConfig.parse("{\"worlds\": {\"a\": {\"enabeld\": false, \"renderLimit\": "
                + "{\"x\": 0, \"z\": 0, \"radius\": 5, \"radious\": 9}}}, \"timeline\": {\"hours\": 3}}");
        assertTrue(c.unknownKeys.contains("worlds.a.renderLimit.radious"));
        assertTrue(c.unknownKeys.contains("worlds.a.enabeld"));
        assertTrue(c.unknownKeys.contains("timeline.hours"));
        assertTrue(c.worldEnabled("a"));
    }
}
