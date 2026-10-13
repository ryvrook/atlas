package dev.ryvrook.atlas.markers;

import com.google.gson.JsonObject;
import dev.ryvrook.atlas.api.MarkerLayer;
import dev.ryvrook.atlas.api.Tier;
import dev.ryvrook.atlas.auth.Viewer;
import dev.ryvrook.atlas.config.AtlasConfig;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerJsonTest {
    private static final Viewer PUBLIC = Viewer.PUBLIC;
    private static final Viewer LINKED = new Viewer(UUID.randomUUID(), "Lin", null, false, false);
    private static final Viewer RED = new Viewer(UUID.randomUUID(), "Red", "RED", false, false);
    private static final Viewer BLUE = new Viewer(UUID.randomUUID(), "Blue", "BLUE", false, false);
    private static final Viewer ADMIN = new Viewer(UUID.randomUUID(), "Op", null, true, false);

    private static MarkerLayer layer(String tier) throws Exception {
        return LayerFile.parse("file", "t", "{\"tier\": \"" + tier + "\", \"world\": \"w\", \"markers\": ["
                + "{\"id\": \"a\", \"x\": 1, \"z\": 2, \"popup\": \"<script>x</script>\"},"
                + "{\"id\": \"b\", \"x\": 3, \"z\": 4, \"faction\": \"RED\"}]}");
    }

    @Test
    void tiers() {
        assertTrue(LayerJson.canSee(PUBLIC, Tier.PUBLIC));
        assertFalse(LayerJson.canSee(PUBLIC, Tier.LINKED));
        assertTrue(LayerJson.canSee(LINKED, Tier.LINKED));
        assertFalse(LayerJson.canSee(LINKED, Tier.FACTION));
        assertTrue(LayerJson.canSee(RED, Tier.FACTION));
        assertFalse(LayerJson.canSee(RED, Tier.ADMIN));
        assertTrue(LayerJson.canSee(ADMIN, Tier.ADMIN));
        assertTrue(LayerJson.canSee(new Viewer(null, "as RED", "RED", false, true), Tier.FACTION));
    }

    @Test
    void layerFollowsTierAtServeTime() throws Exception {
        AtlasConfig cfg = AtlasConfig.parse(null);
        MarkerLayer pub = layer("public");
        assertNotNull(LayerJson.layer(PUBLIC, cfg, pub, "w", "file"));
        MarkerLayer adm = layer("admin");
        assertNull(LayerJson.layer(PUBLIC, cfg, adm, "w", "file"));
        assertNull(LayerJson.layer(RED, cfg, adm, "w", "file"));
        assertNotNull(LayerJson.layer(ADMIN, cfg, adm, "w", "file"));
        // The same layer object changes tier and the next answer follows it.
        adm.tier(Tier.PUBLIC);
        assertNotNull(LayerJson.layer(PUBLIC, cfg, adm, "w", "file"));
        assertNull(LayerJson.layer(PUBLIC, cfg, adm, "other", "file"));
    }

    @Test
    void factionTierFiltersTaggedMarkers() throws Exception {
        AtlasConfig cfg = AtlasConfig.parse(null);
        MarkerLayer fac = layer("faction");
        assertNull(LayerJson.layer(LINKED, cfg, fac, "w", "file"));
        assertEquals(2, LayerJson.layer(RED, cfg, fac, "w", "file").getAsJsonArray("markers").size());
        JsonObject blue = LayerJson.layer(BLUE, cfg, fac, "w", "file");
        assertEquals(1, blue.getAsJsonArray("markers").size());
        assertEquals("a", blue.getAsJsonArray("markers").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(2, LayerJson.layer(ADMIN, cfg, fac, "w", "file").getAsJsonArray("markers").size());
    }

    @Test
    void layersSettingGatesEverything() throws Exception {
        AtlasConfig cfg = AtlasConfig.parse("{\"visibility\": {\"layers\": \"admin\"}}");
        assertNull(LayerJson.layer(RED, cfg, layer("public"), "w", "file"));
        assertNotNull(LayerJson.layer(ADMIN, cfg, layer("public"), "w", "file"));
    }

    @Test
    void shapeOfOneLayer() throws Exception {
        JsonObject o = LayerJson.layer(PUBLIC, AtlasConfig.parse(null), layer("public"), "w", "file");
        for (String k : new String[]{"id", "plugin", "label", "source", "tier", "defaultHidden", "priority", "minZoom",
                "maxZoom", "updatedAt", "markers"}) {
            assertTrue(o.has(k), k);
        }
        JsonObject m = o.getAsJsonArray("markers").get(0).getAsJsonObject();
        for (String k : new String[]{"id", "kind", "label", "popup", "x", "y", "z", "radius", "points", "icon", "iconData",
                "tint", "fill", "weight", "faction"}) {
            assertTrue(m.has(k), k);
        }
        assertEquals("&lt;script&gt;x&lt;/script&gt;", m.get("popup").getAsString());
        assertEquals("file:t", o.get("id").getAsString());
    }
}
