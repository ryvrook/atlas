package dev.ryvrook.atlas.api;

import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.data.LiveState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The map address, the render queue and the world list other plugins read through AtlasApi. */
class AtlasApiStatusTest {
    @TempDir
    Path dir;
    private Atlas atlas;

    private AtlasApi start(AtlasConfig cfg) {
        atlas = new Atlas(cfg, dir.resolve("data"));
        atlas.useExecutor(Runnable::run);
        AtlasApi api = AtlasApi.get();
        assertNotNull(api);
        return api;
    }

    @AfterEach
    void stop() {
        if (atlas != null) {
            atlas.shutdown();
            atlas.tiles().shutdown();
        }
    }

    @Test
    void anEmptyPublicUrlIsBuiltFromBindPortAndBasePath() {
        AtlasConfig cfg = new AtlasConfig();
        cfg.bind = "127.0.0.1";
        cfg.port = 8124;
        cfg.basePath = "/map";
        AtlasApi api = start(cfg);
        assertEquals("http://127.0.0.1:8124/map/", api.publicUrl());
        assertFalse(api.publicUrlSet());
    }

    @Test
    void aWildcardBindGetsThePlaceholderHost() {
        assertEquals("http://your-server-address:8123/", Atlas.addressOf("0.0.0.0", 8123, ""));
        assertEquals("http://your-server-address:8123/", Atlas.addressOf("::", 8123, null));
        assertEquals("http://[::1]:8123/live/", Atlas.addressOf("::1", 8123, "live/"));
    }

    @Test
    void aSetPublicUrlWinsAndEndsWithASlash() {
        AtlasConfig cfg = new AtlasConfig();
        cfg.publicUrl = " https://map.example.com ";
        AtlasApi api = start(cfg);
        assertEquals("https://map.example.com/", api.publicUrl());
        assertTrue(api.publicUrlSet());
    }

    @Test
    void theAddressNeverCarriesTheAdminToken() {
        AtlasConfig cfg = new AtlasConfig();
        cfg.adminToken = "a-long-admin-token-for-the-test-1234567890";
        AtlasApi api = start(cfg);
        assertFalse(api.publicUrl().contains(cfg.adminToken));
    }

    @Test
    void aFreshQueueIsEmptyAndItsWorldMapIsReadOnly() {
        AtlasApi api = start(new AtlasConfig());
        RenderStatus r = api.renderStatus();
        assertEquals(0, r.queued());
        assertEquals(0, r.running());
        assertFalse(r.paused());
        assertTrue(r.queuedPerWorld().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> r.queuedPerWorld().put("default", 1));
    }

    @Test
    void worldsAreSortedAndADisabledWorldIsLeftOut() {
        AtlasConfig cfg = new AtlasConfig();
        AtlasConfig.WorldSettings off = new AtlasConfig.WorldSettings();
        off.enabled = false;
        AtlasConfig.WorldSettings hidden = new AtlasConfig.WorldSettings();
        hidden.hidden = true;
        cfg.worlds = new java.util.LinkedHashMap<>(Map.of("closed", off, "secret", hidden));
        AtlasApi api = start(cfg);
        Map<String, LiveState.WorldInfo> live = atlas.live().worlds();
        for (String name : List.of("secret", "default", "closed")) {
            live.put(name, new LiveState.WorldInfo(name, name.equals("default") ? 3 : 0, null, null, null, true,
                    1L, LiveState.WorldKind.plain(name)));
        }
        List<WorldStatus> worlds = api.worlds();
        assertEquals(List.of("default", "secret"), worlds.stream().map(WorldStatus::name).toList());
        WorldStatus def = worlds.get(0);
        assertTrue(def.loaded());
        assertEquals(3, def.players());
        assertTrue(def.publicVisible());
        assertFalse(worlds.get(1).publicVisible());
    }

    @Test
    void theRunningInstanceReportsTheClassVersion() {
        assertEquals(2, AtlasApi.VERSION);
        assertEquals(AtlasApi.VERSION, start(new AtlasConfig()).version());
    }
}
