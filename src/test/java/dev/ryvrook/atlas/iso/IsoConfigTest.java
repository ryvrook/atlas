package dev.ryvrook.atlas.iso;

import dev.ryvrook.atlas.config.AtlasConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsoConfigTest {
    @Test
    void defaults() throws Exception {
        AtlasConfig c = AtlasConfig.parse(null);
        assertTrue(c.isoEnabled());
        assertEquals(64, c.isoCacheMb());
        assertEquals(512, c.isoDiskMb());
        assertEquals(16, c.isoMaxRadius());
        assertEquals(10, c.isoRequestsPerMinute());
        assertTrue(c.isoSnapshots().isEmpty());
        assertFalse(c.isoSnapshotAdminMarkers());
        assertEquals("public", c.visibility.get("iso"));
    }

    @Test
    void snapshotAreasAreRead() throws Exception {
        AtlasConfig c = AtlasConfig.parse("{\"iso\":{\"snapshots\":[{\"world\":\"default\",\"x\":1280,\"z\":416,"
                + "\"radius\":4,\"label\":\"spawn\"}],\"snapshotAdminMarkers\":true}}");
        assertEquals(1, c.isoSnapshots().size());
        assertEquals("spawn", c.isoSnapshots().get(0).label);
        assertEquals(1280, c.isoSnapshots().get(0).x);
        assertTrue(c.isoSnapshotAdminMarkers());
    }

    @Test
    void badValuesFailClosed() {
        String[] bad = {
            "{\"isoCacheMb\":2}",
            "{\"isoDiskMb\":-1}",
            "{\"isoMaxRadius\":0}",
            "{\"isoMaxRadius\":17}",
            "{\"isoRequestsPerMinute\":-5}",
            "{\"isoEnabled\":\"yes\"}",
            "{\"iso\":[]}",
            "{\"iso\":{\"snapshots\":{}}}",
            "{\"iso\":{\"snapshots\":[{\"world\":\"default\",\"x\":1,\"z\":2}]}}",
            "{\"iso\":{\"snapshots\":[{\"world\":\"default\",\"x\":1.5,\"z\":2,\"label\":\"a\"}]}}",
            "{\"iso\":{\"snapshots\":[{\"world\":\"default\",\"x\":1,\"z\":2,\"label\":\"Spawn Area\"}]}}",
            "{\"iso\":{\"snapshots\":[{\"world\":\"\",\"x\":1,\"z\":2,\"label\":\"a\"}]}}",
            "{\"iso\":{\"snapshots\":[{\"world\":\"default\",\"x\":1,\"z\":2,\"label\":\"a\",\"radius\":20}]}}",
            "{\"isoMaxRadius\":4,\"iso\":{\"snapshots\":[{\"world\":\"default\",\"x\":1,\"z\":2,\"label\":\"a\",\"radius\":8}]}}",
            "{\"iso\":{\"snapshots\":[{\"world\":\"w\",\"x\":1,\"z\":2,\"label\":\"a\"},{\"world\":\"w\",\"x\":3,\"z\":4,\"label\":\"a\"}]}}",
            "{\"iso\":{\"snapshotAdminMarkers\":1}}",
            "{\"visibility\":{\"iso\":\"friends\"}}",
        };
        for (String text : bad) {
            assertThrows(AtlasConfig.InvalidConfigException.class, () -> AtlasConfig.parse(text), text);
        }
    }

    @Test
    void unknownKeysInsideIsoAreReported() throws Exception {
        AtlasConfig c = AtlasConfig.parse("{\"iso\":{\"snapshot\":[],\"snapshots\":[{\"world\":\"w\",\"x\":1,\"z\":2,"
                + "\"label\":\"a\",\"zoom\":3}]}}");
        assertTrue(c.unknownKeys.contains("iso.snapshot"));
        assertTrue(c.unknownKeys.contains("iso.snapshots[0].zoom"));
    }
}
