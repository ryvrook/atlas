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
        assertEquals(20, c.isoRequestsPerMinute());
        assertEquals(4, c.isoViewsPerAddress());
        assertEquals(2, c.isoLargeViewsPerAddress());
        assertEquals(3, c.isoKeyPlaceLimit());
        assertTrue(c.isoKeyPlaces().isEmpty());
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
            "{\"isoViewsPerAddress\":0}",
            "{\"isoViewsPerAddress\":17}",
            "{\"isoViewsPerAddress\":2.5}",
            "{\"isoLargeViewsPerAddress\":0}",
            "{\"isoLargeViewsPerAddress\":5}",
            "{\"isoKeyPlaceLimit\":0}",
            "{\"isoKeyPlaceLimit\":11}",
            "{\"iso\":{\"keyPlaces\":{}}}",
            "{\"iso\":{\"keyPlaces\":[1]}}",
            "{\"iso\":{\"keyPlaces\":[{\"world\":\"w\",\"x\":1,\"z\":2}]}}",
            "{\"iso\":{\"keyPlaces\":[{\"world\":\"w\",\"x\":1,\"z\":2,\"label\":\"Town Hall\"}]}}",
            "{\"iso\":{\"keyPlaces\":[{\"world\":\"w\",\"x\":1,\"z\":2,\"label\":\"a\",\"radius\":0}]}}",
            "{\"iso\":{\"keyPlaces\":[{\"world\":\"w\",\"x\":1,\"z\":2,\"label\":\"a\",\"radius\":17}]}}",
            "{\"iso\":{\"keyPlaces\":[{\"world\":\"w\",\"x\":1,\"z\":2,\"label\":\"a\",\"name\":5}]}}",
            "{\"iso\":{\"keyPlaces\":[{\"world\":\"w\",\"x\":1,\"z\":2,\"label\":\"a\",\"name\":\""
                    + "x".repeat(61) + "\"}]}}",
            "{\"iso\":{\"keyPlaces\":[{\"world\":\"w\",\"x\":1,\"z\":2,\"label\":\"a\",\"name\":\"tab\\there\"}]}}",
            "{\"iso\":{\"keyPlaces\":[{\"world\":\"\",\"x\":1,\"z\":2,\"label\":\"a\"}]}}",
            "{\"iso\":{\"keyPlaces\":[{\"world\":\"w\",\"x\":40000000,\"z\":2,\"label\":\"a\"}]}}",
            "{\"iso\":{\"keyPlaces\":[{\"world\":\"w\",\"x\":1,\"z\":2,\"label\":\"a\"},"
                    + "{\"world\":\"v\",\"x\":3,\"z\":4,\"label\":\"a\"}]}}",
            "{\"isoKeyPlaceLimit\":1,\"iso\":{\"keyPlaces\":[{\"world\":\"w\",\"x\":1,\"z\":2,\"label\":\"a\"},"
                    + "{\"world\":\"w\",\"x\":3,\"z\":4,\"label\":\"b\"}]}}",
        };
        for (String text : bad) {
            assertThrows(AtlasConfig.InvalidConfigException.class, () -> AtlasConfig.parse(text), text);
        }
    }

    @Test
    void keyPlacesAreReadWithTheirDefaults() throws Exception {
        AtlasConfig c = AtlasConfig.parse("{\"isoViewsPerAddress\":16,\"isoLargeViewsPerAddress\":4,\"isoKeyPlaceLimit\":10,"
                + "\"iso\":{\"keyPlaces\":[{\"world\":\"default\",\"x\":1280,\"z\":416,\"label\":\"spawn\"},"
                + "{\"world\":\"default\",\"x\":-40,\"z\":90,\"radius\":12,\"label\":\"harbour\",\"name\":\"The Harbour\"},"
                + "{\"world\":\"default\",\"x\":5,\"z\":5,\"label\":\"mill\",\"name\":null}]}}");
        assertEquals(16, c.isoViewsPerAddress());
        assertEquals(4, c.isoLargeViewsPerAddress());
        assertEquals(10, c.isoKeyPlaceLimit());
        assertEquals(3, c.isoKeyPlaces().size());
        AtlasConfig.KeyPlace spawn = c.isoKeyPlaces().get(0);
        assertEquals(6, spawn.radius);
        assertEquals("", spawn.name);
        assertEquals("spawn", spawn.displayName());
        assertEquals("The Harbour", c.isoKeyPlaces().get(1).displayName());
        assertEquals(12, c.isoKeyPlaces().get(1).radius);
        assertEquals("", c.isoKeyPlaces().get(2).name);
    }

    @Test
    void aMissingKeyPlacesListIsAddedToTheFile() throws Exception {
        AtlasConfig c = AtlasConfig.parse("{\"iso\":{\"snapshots\":[]}}");
        assertTrue(c.addedKeys.contains("iso.keyPlaces"));
        assertTrue(c.addedKeys.contains("isoViewsPerAddress"));
        assertTrue(c.addedKeys.contains("isoLargeViewsPerAddress"));
        assertTrue(c.addedKeys.contains("isoKeyPlaceLimit"));
    }

    @Test
    void unknownKeysInsideIsoAreReported() throws Exception {
        AtlasConfig c = AtlasConfig.parse("{\"iso\":{\"snapshot\":[],\"snapshots\":[{\"world\":\"w\",\"x\":1,\"z\":2,"
                + "\"label\":\"a\",\"zoom\":3}]}}");
        assertTrue(c.unknownKeys.contains("iso.snapshot"));
        assertTrue(c.unknownKeys.contains("iso.snapshots[0].zoom"));
        AtlasConfig k = AtlasConfig.parse("{\"iso\":{\"keyPlaces\":[{\"world\":\"w\",\"x\":1,\"z\":2,\"label\":\"a\",\"zoom\":3}]}}");
        assertTrue(k.unknownKeys.contains("iso.keyPlaces[0].zoom"));
    }
}
