package dev.ryvrook.atlas.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChronicleExportTest {
    private static final String STEVE = "6f1c2a4e-0b7d-4c55-9a51-1c9f0d3e8b21";
    private static final String ALEX = "0b9f2d1e-3c4a-4b5d-8e6f-7a8b9c0d1e2f";

    /** An export in the shape Chronicle writes, section 10.16 of its contract. */
    static final String FIXTURE = """
            {"Version":1,"updatedAt":1760301234567,"online":3,"chronicleVersion":"1.0.0",
             "server":{"blocks_broken":5120334,"kills":88123,"playtime":91233411,"players_known":1840,"use:chest":42,"new_stat":7},
             "boards":{"kills":[{"uuid":"6f1c2a4e-0b7d-4c55-9a51-1c9f0d3e8b21","name":"Steve","value":4410},
                                {"uuid":"0b9f2d1e-3c4a-4b5d-8e6f-7a8b9c0d1e2f","name":"Alex","value":3900}],
                       "playtime":[{"uuid":null,"name":"A player","value":901233},
                                   {"uuid":"0b9f2d1e-3c4a-4b5d-8e6f-7a8b9c0d1e2f","name":"Alex","value":800000.5}],
                       "jumps":[]},
             "recentRecords":[{"at":1760300412000,"kind":"FASTEST","key":"fastest:Goblin_Gobliterator_HM@2","names":["Steve","Alex"],"value":412000}]}
            """;

    private static JsonObject find(JsonArray list, String key) {
        for (var e : list) {
            if (e.getAsJsonObject().get("key").getAsString().equals(key)) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    @Test
    void readsTheFileOnlyWhenItChanges(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("export").resolve("stats.json");
        ChronicleExport export = new ChronicleExport(file);
        assertFalse(export.poll());
        assertFalse(export.available());
        Files.createDirectories(file.getParent());
        Files.writeString(file, FIXTURE, StandardCharsets.UTF_8);
        assertTrue(export.poll());
        assertTrue(export.available());
        assertEquals(1760301234567L, export.updatedAt());
        assertFalse(export.poll(), "an unchanged file is not read again");

        // A broken write keeps the last good copy.
        Files.writeString(file, "{\"Version\":1,", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        assertFalse(export.poll());
        assertTrue(export.available());
        assertNotNull(export.lastError());

        Files.delete(file);
        assertTrue(export.poll(), "a file that went away is a change");
        assertFalse(export.available());
        assertFalse(export.poll());
    }

    @Test
    void payloadListsTotalsAndBoardsInTheirOrder() throws Exception {
        JsonObject root = com.google.gson.JsonParser.parseString(FIXTURE).getAsJsonObject();
        JsonObject out = ChronicleExport.payload(root, 1760301234567L, "https://stats.example.com/", u -> false);
        assertTrue(out.get("available").getAsBoolean());
        assertEquals(1760301234567L, out.get("updatedAt").getAsLong());
        assertEquals(3, out.get("online").getAsInt());
        assertEquals("1.0.0", out.get("chronicleVersion").getAsString());
        assertEquals("https://stats.example.com/", out.get("url").getAsString());
        assertFalse(out.has("recentRecords"));

        JsonArray totals = out.getAsJsonArray("totals");
        assertEquals("players_known", totals.get(0).getAsJsonObject().get("key").getAsString());
        assertEquals("playtime", totals.get(1).getAsJsonObject().get("key").getAsString());
        JsonObject playtime = find(totals, "playtime");
        assertEquals("Time played", playtime.get("label").getAsString());
        assertEquals("seconds", playtime.get("unit").getAsString());
        assertEquals(91233411L, playtime.get("value").getAsLong());
        assertTrue(playtime.get("headline").getAsBoolean());
        assertEquals("Chests opened", find(totals, "use:chest").get("label").getAsString());
        JsonObject unknown = find(totals, "new_stat");
        assertEquals("New stat", unknown.get("label").getAsString());
        assertEquals("count", unknown.get("unit").getAsString());
        assertFalse(unknown.get("headline").getAsBoolean());

        JsonArray boards = out.getAsJsonArray("boards");
        assertEquals(2, boards.size(), "an empty board is left out");
        assertEquals("playtime", boards.get(0).getAsJsonObject().get("key").getAsString());
        JsonObject kills = boards.get(1).getAsJsonObject();
        assertEquals("Kills", kills.get("label").getAsString());
        JsonObject top = kills.getAsJsonArray("rows").get(0).getAsJsonObject();
        assertEquals(1, top.get("rank").getAsInt());
        assertEquals(STEVE, top.get("uuid").getAsString());
        assertEquals("Steve", top.get("name").getAsString());
        assertEquals(4410L, top.get("value").getAsLong());
        JsonArray playRows = boards.get(0).getAsJsonObject().getAsJsonArray("rows");
        assertTrue(playRows.get(0).getAsJsonObject().get("uuid").isJsonNull());
        assertEquals("A player", playRows.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(800000.5, playRows.get(1).getAsJsonObject().get("value").getAsDouble());

        for (String key : List.of("available", "updatedAt", "online", "chronicleVersion", "url", "totals", "boards")) {
            assertTrue(ChronicleExport.empty(null).has(key), key);
        }
    }

    @Test
    void hiddenPlayersAreAPlayer() {
        JsonObject root = com.google.gson.JsonParser.parseString(FIXTURE).getAsJsonObject();
        UUID hidden = UUID.fromString(STEVE);
        JsonObject out = ChronicleExport.payload(root, 0, null, hidden::equals);
        assertTrue(out.get("url").isJsonNull());
        JsonArray boards = out.getAsJsonArray("boards");
        JsonArray kills = boards.get(1).getAsJsonObject().getAsJsonArray("rows");
        assertTrue(kills.get(0).getAsJsonObject().get("uuid").isJsonNull());
        assertEquals("A player", kills.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(ALEX, kills.get(1).getAsJsonObject().get("uuid").getAsString());
        assertEquals(1760301234567L, out.get("updatedAt").getAsLong(), "the file's own time when none was read");
    }

    @Test
    void missingExportIsEmpty() {
        JsonObject out = ChronicleExport.payload(null, 0, "/stats/", u -> false);
        assertFalse(out.get("available").getAsBoolean());
        assertNull(out.get("updatedAt").isJsonNull() ? null : out.get("updatedAt"));
        assertEquals(0, out.getAsJsonArray("boards").size());
        assertEquals("/stats/", out.get("url").getAsString());
    }
}
