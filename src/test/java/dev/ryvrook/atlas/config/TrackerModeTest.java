package dev.ryvrook.atlas.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerModeTest {
    private static final String TOKEN = "abcdefghijkmnpqrstuvwxyz23456789";

    @Test
    void defaultsToAutoAndIsAdded() throws Exception {
        AtlasConfig c = AtlasConfig.parse("{\"adminToken\": \"" + TOKEN + "\"}");
        assertEquals("auto", c.tracker);
        assertEquals(TrackerMode.AUTO, c.trackerMode());
        assertTrue(c.addedKeys.contains("tracker"));
    }

    @Test
    void readsEveryValueIgnoringCase() throws Exception {
        assertEquals(TrackerMode.ALWAYS, AtlasConfig.parse("{\"tracker\": \"Always\"}").trackerMode());
        AtlasConfig never = AtlasConfig.parse("{\"tracker\": \" never \"}");
        assertEquals(TrackerMode.NEVER, never.trackerMode());
        assertEquals("never", never.tracker);
        assertEquals(TrackerMode.AUTO, AtlasConfig.parse("{\"tracker\": \"\"}").trackerMode());
    }

    @Test
    void anUnknownValueFailsClosed() {
        assertThrows(AtlasConfig.InvalidConfigException.class, () -> AtlasConfig.parse("{\"tracker\": \"sometimes\"}"));
        assertThrows(AtlasConfig.InvalidConfigException.class, () -> AtlasConfig.parse("{\"tracker\": 1}"));
    }
}
