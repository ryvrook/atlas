package dev.ryvrook.atlas.markers;

import dev.ryvrook.atlas.api.CircleMarker;
import dev.ryvrook.atlas.api.MarkerLayer;
import dev.ryvrook.atlas.api.PointMarker;
import dev.ryvrook.atlas.api.PolygonMarker;
import dev.ryvrook.atlas.api.PolylineMarker;
import dev.ryvrook.atlas.api.Tier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerFileTest {
    private static final String GOOD = """
            {"label": "Shops", "tier": "linked", "defaultHidden": true, "priority": 5, "minZoom": -1, "maxZoom": null,
             "world": "default",
             "markers": [
               {"id": "market", "x": 120, "z": -40, "y": 64, "icon": "Coins", "label": "Market", "popup": "<b>Open</b>",
                "tint": "#FFCC00"},
               {"id": "road", "kind": "polyline", "points": [[0, 0], [100, 40]], "weight": 3},
               {"id": "farm", "kind": "polygon", "points": [[0, 0], [50, 0], [50, 50]], "fill": "#33aa3355",
                "popup": "<i>Wheat</i>", "faction": "RED"},
               {"id": "ring", "kind": "circle", "x": 5, "z": 6, "radius": 64, "world": "nether"}]}
            """;

    @Test
    void parsesEveryKind() throws Exception {
        MarkerLayer l = LayerFile.parse("file", "shops", GOOD);
        assertEquals("file:shops", l.key());
        assertEquals("Shops", l.label());
        assertEquals(Tier.LINKED, l.tier());
        assertTrue(l.defaultHidden());
        assertEquals(5, l.priority());
        assertEquals(-1, l.minZoom());
        assertNull(l.maxZoom());
        assertEquals(3, l.markers("default").size());
        assertEquals(1, l.markers("nether").size());
        PointMarker p = assertInstanceOf(PointMarker.class, l.get("default", "market"));
        assertEquals(120, p.x());
        assertEquals(64.0, p.y());
        assertEquals("Coins", p.icon());
        assertEquals("#ffcc00", p.tint());
        assertEquals("<b>Open</b>", p.popup());
        PolylineMarker road = assertInstanceOf(PolylineMarker.class, l.get("default", "road"));
        assertEquals(2, road.points().size());
        assertEquals(3.0, road.weight());
        PolygonMarker farm = assertInstanceOf(PolygonMarker.class, l.get("default", "farm"));
        assertFalse(farm.popupTrusted());
        assertEquals("RED", farm.faction());
        CircleMarker ring = assertInstanceOf(CircleMarker.class, l.get("nether", "ring"));
        assertEquals(64, ring.radius());
    }

    @Test
    void writeReadsBackTheSame() throws Exception {
        MarkerLayer l = LayerFile.parse("file", "shops", GOOD);
        String text = LayerFile.write(l);
        MarkerLayer again = LayerFile.parse("file", "shops", text);
        assertEquals(text, LayerFile.write(again));
    }

    @Test
    void refusesBadFiles() {
        String[] bad = {
                "not json",
                "[]",
                "{\"lable\": \"typo\"}",
                "{\"tier\": \"friends\"}",
                "{\"markers\": {}}",
                "{\"markers\": [{\"id\": \"a\", \"x\": 1, \"z\": 2}]}",
                "{\"world\": \"w\", \"markers\": [{\"x\": 1, \"z\": 2}]}",
                "{\"world\": \"w\", \"markers\": [{\"id\": \"a\", \"x\": \"1\", \"z\": 2}]}",
                "{\"world\": \"w\", \"markers\": [{\"id\": \"a\", \"kind\": \"star\", \"x\": 1, \"z\": 2}]}",
                "{\"world\": \"w\", \"markers\": [{\"id\": \"a\", \"kind\": \"polyline\", \"points\": [[1, 2]]}]}",
                "{\"world\": \"w\", \"markers\": [{\"id\": \"a\", \"kind\": \"polygon\", \"points\": [[1, 2], [3, 4]]}]}",
                "{\"world\": \"w\", \"markers\": [{\"id\": \"a\", \"kind\": \"circle\", \"x\": 1, \"z\": 2, \"radius\": 0}]}",
                "{\"world\": \"w\", \"markers\": [{\"id\": \"a\", \"x\": 1, \"z\": 2, \"tint\": \"red\"}]}",
                "{\"world\": \"w\", \"markers\": [{\"id\": \"a\", \"x\": 1, \"z\": 2, \"icon\": \"../etc\"}]}",
                "{\"world\": \"w\", \"markers\": [{\"id\": \"a\", \"x\": 1, \"z\": 2, \"icon\": \"data:text/html;base64,AAAA\"}]}",
                "{\"world\": \"w\", \"markers\": [{\"id\": \"a b\", \"x\": 1, \"z\": 2}]}",
                "{\"world\": \"w\", \"markers\": [{\"id\": \"a\", \"x\": 1, \"z\": 2, \"popup\": \"x\", \"popupHtml\": \"y\"}]}",
                "{\"world\": \"w\", \"markers\": [{\"id\": \"a\", \"x\": 1, \"z\": 2, \"popupHtml\": \"<b>y</b>\"}]}",
                "{\"minZoom\": 1.5}",
        };
        for (String text : bad) {
            assertThrows(LayerFile.Invalid.class, () -> LayerFile.parse("file", "x", text), text);
        }
    }

    @Test
    void iconDataUrlIsCappedAt8Kb() throws Exception {
        String small = "data:image/png;base64," + "A".repeat(100);
        MarkerLayer l = LayerFile.parse("file", "x", "{\"world\": \"w\", \"markers\": [{\"id\": \"a\", \"x\": 1, \"z\": 2, \"icon\": \""
                + small + "\"}]}");
        assertEquals(small, ((PointMarker) l.get("w", "a")).icon());
        String big = "data:image/png;base64," + "A".repeat(9000);
        assertThrows(LayerFile.Invalid.class, () -> LayerFile.parse("file", "x",
                "{\"world\": \"w\", \"markers\": [{\"id\": \"a\", \"x\": 1, \"z\": 2, \"icon\": \"" + big + "\"}]}"));
    }

    @Test
    void sameIdReplaces() throws Exception {
        MarkerLayer l = LayerFile.parse("file", "x", "{\"world\": \"w\", \"markers\": [{\"id\": \"a\", \"x\": 1, \"z\": 2},"
                + " {\"id\": \"a\", \"kind\": \"circle\", \"x\": 1, \"z\": 2, \"radius\": 3}]}");
        assertEquals(1, l.size());
        assertInstanceOf(CircleMarker.class, l.get("w", "a"));
    }
}
