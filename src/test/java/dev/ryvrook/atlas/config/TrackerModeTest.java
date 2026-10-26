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

    @Test
    void theSiblingKeysDefaultToAutoAndReadNever() throws Exception {
        AtlasConfig c = AtlasConfig.parse("{\"adminToken\": \"" + TOKEN + "\"}");
        for (String key : new String[]{"coinage", "dominion", "feats", "chronicle", "vanish"}) {
            assertTrue(c.addedKeys.contains(key), key);
        }
        assertEquals(TrackerMode.AUTO, c.coinageMode());
        AtlasConfig off = AtlasConfig.parse("{\"coinage\": \"never\", \"dominion\": \"NEVER\", \"feats\": \"never\", \"chronicle\": \"always\", \"vanish\": \"never\"}");
        assertEquals(TrackerMode.NEVER, off.coinageMode());
        assertEquals(TrackerMode.NEVER, off.dominionMode());
        assertEquals("never", off.dominion);
        assertEquals(TrackerMode.NEVER, off.featsMode());
        assertEquals(TrackerMode.ALWAYS, off.chronicleMode());
        assertEquals(TrackerMode.NEVER, off.vanishMode());
        assertEquals(TrackerMode.AUTO, c.vanishMode());
        assertThrows(AtlasConfig.InvalidConfigException.class, () -> AtlasConfig.parse("{\"feats\": \"off\"}"));
    }
}
