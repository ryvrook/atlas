package dev.ryvrook.atlas.markers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.auth.Viewer;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dominion's API siege layer and the snapshot's siege pins are never both on the map. */
class DominionSiegeLayerTest {
    @TempDir
    Path dir;
    private Atlas atlas;

    private Atlas atlas(Path snapshot) {
        AtlasConfig cfg = new AtlasConfig();
        cfg.dominionSnapshot = snapshot.toString();
        atlas = new Atlas(cfg, dir.resolve("data"));
        return atlas;
    }

    @AfterEach
    void stop() {
        if (atlas != null) {
            atlas.tiles().shutdown();
        }
    }

    private static List<String> layerIds(JsonObject payload) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : Json.arr(payload, "layers")) {
            out.add(Json.str(e.getAsJsonObject(), "id"));
        }
        return out;
    }

    @Test
    void theApiLayerStepsAsideWhileTheSnapshotDrawsSieges() throws Exception {
        Path snap = dir.resolve("snapshot.json");
        Files.writeString(snap, "{\"version\":1,\"at\":1,\"worlds\":[],\"sieges\":[]}");
        Atlas a = atlas(snap);
        MarkerService markers = a.markers();
        markers.apiLayer("Ryvrook:Dominion", "sieges").circle("default", "siege-1", 16, 16, 24);
        markers.apiLayer("Other", "shops").point("default", "market", 0, 0);
        Viewer pub = new Viewer(null, null, null, false, false);

        List<String> before = layerIds(markers.layersPayload(pub, "default"));
        assertTrue(before.contains(MarkerService.DOMINION_SIEGES), "no snapshot read yet, so the layer shows");

        assertTrue(a.snapshot().poll(null));
        assertTrue(a.snapshotDrawsSieges());
        List<String> after = layerIds(markers.layersPayload(pub, "default"));
        assertFalse(after.contains(MarkerService.DOMINION_SIEGES));
        assertTrue(after.contains("Other:shops"));

        a.snapshot().setOwnerGone(true);
        assertTrue(a.snapshot().poll(null));
        assertFalse(a.snapshot().present());
        assertTrue(layerIds(markers.layersPayload(pub, "default")).contains(MarkerService.DOMINION_SIEGES));

        a.snapshot().setOwnerGone(false);
        assertTrue(a.snapshot().poll(null));
        assertEquals(true, a.snapshot().present());
    }
}
