package dev.ryvrook.atlas.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasConfigTest {
    private static final String TOKEN = "abcdefghijkmnpqrstuvwxyz23456789";

    @Test
    void defaultsFromNothing() throws Exception {
        AtlasConfig c = AtlasConfig.parse(null);
        assertEquals(8123, c.port);
        assertEquals(1200, c.chunkImagesPerMinute());
        assertEquals(24, c.tileMaxAgeHours());
        assertEquals(600, c.apiRequestsPerMinute());
        assertEquals(3000, c.tileRequestsPerMinute());
        assertTrue(c.preRenderIdle());
        assertEquals(32, c.adminToken.length());
        assertTrue(c.addedKeys.contains("port"));
        assertEquals(Visibility.LINKED, c.visibilityOf("players"));
        assertEquals(Visibility.ADMIN, c.visibilityOf("somethingNew"));
    }

    @Test
    void readsValues() throws Exception {
        AtlasConfig c = AtlasConfig.parse("{\"port\": 9000, \"apiRequestsPerMinute\": 0, \"preRenderIdle\": false, "
                + "\"basePath\": \"map/\", \"adminToken\": \"" + TOKEN + "\", \"visibility\": {\"heat\": \"Public\"}}");
        assertEquals(9000, c.port);
        assertEquals(0, c.apiRequestsPerMinute());
        assertFalse(c.preRenderIdle());
        assertEquals("/map", c.basePath);
        assertEquals(TOKEN, c.adminToken);
        assertEquals(Visibility.PUBLIC, c.visibilityOf("heat"));
        assertEquals(Visibility.LINKED, c.visibilityOf("players"));
        assertFalse(c.adminTokenReplaced);
    }

    @Test
    void shortTokenIsReplaced() throws Exception {
        AtlasConfig c = AtlasConfig.parse("{\"adminToken\": \"short\"}");
        assertTrue(c.adminTokenReplaced);
        assertEquals(32, c.adminToken.length());
        assertTrue(c.addedKeys.contains("adminToken"));
    }

    @Test
    void brokenJsonFailsClosed() {
        assertThrows(AtlasConfig.InvalidConfigException.class, () -> AtlasConfig.parse("{\"port\": 8123,"));
        assertThrows(AtlasConfig.InvalidConfigException.class, () -> AtlasConfig.parse("[1, 2]"));
        assertThrows(AtlasConfig.InvalidConfigException.class, () -> AtlasConfig.parse("\"text\""));
    }

    @Test
    void wrongTypesFailClosed() {
        String[] bad = {
                "{\"port\": \"8123\"}",
                "{\"port\": 81.5}",
                "{\"showPlayers\": \"yes\"}",
                "{\"showPlayers\": 1}",
                "{\"bind\": 5}",
                "{\"trustedProxies\": \"127.0.0.1\"}",
                "{\"trustedProxies\": [1]}",
                "{\"visibility\": []}",
                "{\"visibility\": {\"heat\": true}}",
                "{\"playerUpdateHz\": \"fast\"}",
                "{\"port\": null}",
        };
        for (String text : bad) {
            assertThrows(AtlasConfig.InvalidConfigException.class, () -> AtlasConfig.parse(text), text);
        }
    }

    @Test
    void outOfRangeFailsClosed() {
        String[] bad = {
                "{\"port\": 0}",
                "{\"port\": 70000}",
                "{\"playerUpdateHz\": 0}",
                "{\"tileCacheMb\": 1}",
                "{\"sseClientsPerIp\": 0}",
                "{\"apiRequestsPerMinute\": -1}",
                "{\"tileRequestsPerMinute\": -5}",
                "{\"chunkImagesPerMinute\": -1}",
                "{\"tileMaxAgeHours\": -1}",
                "{\"visibility\": {\"heat\": \"pubic\"}}",
                "{\"basePath\": \"/../etc\"}",
        };
        for (String text : bad) {
            AtlasConfig.InvalidConfigException e = assertThrows(AtlasConfig.InvalidConfigException.class,
                    () -> AtlasConfig.parse(text), text);
            assertFalse(e.getMessage().isBlank());
        }
    }

    @Test
    void unknownKeysAreKeptAside() throws Exception {
        AtlasConfig c = AtlasConfig.parse("{\"prot\": 1}");
        assertEquals(java.util.List.of("prot"), c.unknownKeys);
        assertEquals(8123, c.port);
    }

    @Test
    void rewriteKeepsUnknownKeys(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(AtlasConfig.FILE_NAME);
        Files.writeString(file, "{\"prot\": {\"a\": [1, \"x\"]}, \"note\": \"keep me\", \"adminToken\": \"" + TOKEN + "\"}",
                StandardCharsets.UTF_8);
        AtlasConfig c = AtlasConfig.load(dir);
        assertTrue(c.addedKeys.contains("port"));
        com.google.gson.JsonObject written = com.google.gson.JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("{\"a\":[1,\"x\"]}", written.get("prot").toString());
        assertEquals("keep me", written.get("note").getAsString());
        assertTrue(written.has("port"));
    }

    @Test
    void misspeltLayerIsReported() throws Exception {
        AtlasConfig c = AtlasConfig.parse("{\"visibility\": {\"playres\": \"admin\"}}");
        assertEquals(java.util.List.of("visibility.playres"), c.unknownKeys);
    }

    @Test
    void loadWritesOnlyWhenKeysWereAdded(@TempDir Path dir) throws Exception {
        AtlasConfig first = AtlasConfig.load(dir);
        assertTrue(first.created);
        Path file = dir.resolve(AtlasConfig.FILE_NAME);
        assertTrue(Files.exists(file));

        FileTime stamp = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(file, stamp);
        AtlasConfig second = AtlasConfig.load(dir);
        assertFalse(second.created);
        assertTrue(second.addedKeys.isEmpty(), String.valueOf(second.addedKeys));
        assertEquals(stamp, Files.getLastModifiedTime(file));
        assertEquals(first.adminToken, second.adminToken);

        Files.writeString(file, "{\"port\": 9001, \"adminToken\": \"" + TOKEN + "\"}", StandardCharsets.UTF_8);
        AtlasConfig third = AtlasConfig.load(dir);
        assertEquals(9001, third.port);
        assertTrue(third.addedKeys.contains("bind"));
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.contains("\"apiRequestsPerMinute\""));
        assertTrue(written.contains(TOKEN));
    }

    @Test
    void loadOfBrokenFileLeavesItAlone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(AtlasConfig.FILE_NAME);
        Files.writeString(file, "{\"port\": 8123", StandardCharsets.UTF_8);
        assertThrows(AtlasConfig.InvalidConfigException.class, () -> AtlasConfig.load(dir));
        assertEquals("{\"port\": 8123", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void basePathNormalised() {
        assertEquals("", AtlasConfig.normalizeBasePath("/"));
        assertEquals("/map", AtlasConfig.normalizeBasePath("/map//"));
        assertEquals("/map", AtlasConfig.normalizeBasePath("map"));
        assertEquals("", AtlasConfig.normalizeBasePath(null));
    }
}
