package dev.ryvrook.atlas.iso;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.atlas.config.AtlasConfig;
import dev.ryvrook.atlas.config.AtlasConfig.KeyPlace;
import dev.ryvrook.atlas.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyPlacesTest {
    private static final KeyPlace HALL = new KeyPlace("default", 1280, 416, 4, "hall", "Town Hall");

    // ---- names and views ------------------------------------------------------------

    @Test
    void fileLabelsNameEachDirection() {
        assertEquals("hall-90", KeyPlaces.fileLabel("hall", 90));
        assertTrue(KeyPlaces.validFileLabel("hall-0"));
        assertTrue(KeyPlaces.validFileLabel("a".repeat(40) + "-270"));
        assertFalse(KeyPlaces.validFileLabel("hall-45"));
        assertFalse(KeyPlaces.validFileLabel("hall"));
        assertFalse(KeyPlaces.validFileLabel("Hall-90"));
        assertTrue(IsoSnapshots.validFileLabel("hall"));
        assertTrue(IsoSnapshots.validFileLabel("a".repeat(40) + "-180"));
        assertFalse(IsoSnapshots.validFileLabel("../hall-90"));
    }

    @Test
    void viewsUseScaleTwoAndLowerItForAWideRadius() {
        IsoView v = KeyPlaces.view(HALL, 180, 16);
        assertEquals(2, v.scale());
        assertEquals(180, v.azimuth());
        assertEquals(40, v.cx());
        assertEquals(13, v.cz());
        assertEquals(4, v.radius());
        KeyPlace wide = new KeyPlace("default", 0, 0, 12, "wide", "");
        assertEquals(1, KeyPlaces.view(wide, 0, 16).scale());
        assertEquals(8, KeyPlaces.view(wide, 0, 8).radius());
        assertEquals(2, KeyPlaces.view(wide, 0, 8).scale());
    }

    // ---- fingerprint ------------------------------------------------------------------

    @Test
    void theDigestFollowsEveryChunk() {
        long[] a = {1, 2, 3, 0};
        long[] b = {1, 2, 3, 0};
        long[] c = {1, 2, 4, 0};
        long[] d = {1, 2, 3};
        assertEquals(KeyPlaces.digest(a), KeyPlaces.digest(b));
        assertNotEquals(KeyPlaces.digest(a), KeyPlaces.digest(c));
        assertNotEquals(KeyPlaces.digest(a), KeyPlaces.digest(d));
        assertNotEquals(0, KeyPlaces.digest(new long[0]));
        assertEquals(16, KeyPlaces.printText(KeyPlaces.digest(a)).length());
    }

    // ---- the dedupe decision ------------------------------------------------------------

    private static List<JsonObject> previous(IsoView v, long digest) {
        List<JsonObject> out = new ArrayList<>();
        for (int az : KeyPlaces.AZIMUTHS) {
            JsonObject o = new JsonObject();
            o.addProperty("label", "hall");
            o.addProperty("keyPlace", true);
            o.addProperty("azimuth", az);
            o.addProperty("x", 1280);
            o.addProperty("z", 416);
            o.addProperty("radius", v.radius());
            o.addProperty("scale", v.scale());
            o.addProperty("print", KeyPlaces.printText(digest));
            o.addProperty("partial", false);
            out.add(o);
        }
        return out;
    }

    @Test
    void anUnchangedPlaceReusesThePicturesOfTheDayBefore() {
        IsoView v = KeyPlaces.view(HALL, 0, 16);
        long digest = KeyPlaces.digest(new long[]{5, 6, 7});
        assertTrue(KeyPlaces.unchanged(previous(v, digest), e -> true, v, digest, false));
    }

    @Test
    void anyDifferenceDrawsThePlaceAgain() {
        IsoView v = KeyPlaces.view(HALL, 0, 16);
        long digest = KeyPlaces.digest(new long[]{5, 6, 7});
        List<JsonObject> prev = previous(v, digest);
        // A chunk changed.
        assertFalse(KeyPlaces.unchanged(prev, e -> true, v, KeyPlaces.digest(new long[]{5, 6, 8}), false));
        // The read now missed a chunk.
        assertFalse(KeyPlaces.unchanged(prev, e -> true, v, digest, true));
        // A picture of the day before is gone.
        assertFalse(KeyPlaces.unchanged(prev, e -> (int) Json.num(e, "azimuth", 0) != 270, v, digest, false));
        // No fingerprint is known.
        assertFalse(KeyPlaces.unchanged(prev, e -> true, v, 0, false));
        // A direction is missing.
        assertFalse(KeyPlaces.unchanged(prev.subList(0, 3), e -> true, v, digest, false));
        // The earlier picture was partial.
        List<JsonObject> partial = previous(v, digest);
        partial.get(1).addProperty("partial", true);
        assertFalse(KeyPlaces.unchanged(partial, e -> true, v, digest, false));
        // The place moved to another chunk, or the radius or scale changed.
        IsoView moved = KeyPlaces.view(new KeyPlace("default", 1320, 416, 4, "hall", ""), 0, 16);
        assertFalse(KeyPlaces.unchanged(prev, e -> true, moved, digest, false));
        IsoView wider = KeyPlaces.view(new KeyPlace("default", 1280, 416, 5, "hall", ""), 0, 16);
        assertFalse(KeyPlaces.unchanged(prev, e -> true, wider, digest, false));
        // A move inside the same chunk draws the same square, so it still counts as unchanged.
        IsoView nudged = KeyPlaces.view(new KeyPlace("default", 1290, 420, 4, "hall", ""), 0, 16);
        assertTrue(KeyPlaces.unchanged(prev, e -> true, nudged, digest, false));
        assertFalse(KeyPlaces.unchanged(null, e -> true, v, digest, false));
    }

    // ---- atlas.json -----------------------------------------------------------------------

    @Test
    void theListIsWrittenIntoTheFileWithEverythingElseKept() throws Exception {
        String text = "{\"port\":9000,\"custom\":{\"a\":1},\"iso\":{\"snapshots\":[{\"world\":\"w\",\"x\":1,\"z\":2,"
                + "\"label\":\"s\"}],\"snapshotAdminMarkers\":true}}";
        String out = KeyPlaces.withKeyPlaces(text, List.of(HALL));
        JsonObject root = JsonParser.parseString(out).getAsJsonObject();
        assertEquals(9000, root.get("port").getAsInt());
        assertEquals(1, root.getAsJsonObject("custom").get("a").getAsInt());
        JsonObject iso = root.getAsJsonObject("iso");
        assertTrue(iso.get("snapshotAdminMarkers").getAsBoolean());
        assertEquals(1, iso.getAsJsonArray("snapshots").size());
        JsonArray places = iso.getAsJsonArray("keyPlaces");
        assertEquals(1, places.size());
        assertEquals("Town Hall", places.get(0).getAsJsonObject().get("name").getAsString());
        AtlasConfig back = AtlasConfig.parse(out);
        assertEquals(1, back.isoKeyPlaces().size());
        assertEquals("hall", back.isoKeyPlaces().get(0).label);
        assertEquals(4, back.isoKeyPlaces().get(0).radius);
    }

    @Test
    void aFileThatDoesNotParseIsLeftAlone() {
        assertThrows(KeyPlaces.Problem.class, () -> KeyPlaces.withKeyPlaces("{\"port\": 80,", List.of(HALL)));
        assertThrows(KeyPlaces.Problem.class, () -> KeyPlaces.withKeyPlaces("[]", List.of(HALL)));
        assertThrows(KeyPlaces.Problem.class, () -> KeyPlaces.withKeyPlaces("{\"iso\":[]}", List.of(HALL)));
        assertNotNull(assertDoesNotThrow(() -> KeyPlaces.withKeyPlaces(null, List.of(HALL))));
    }

    private static String assertDoesNotThrow(org.junit.jupiter.api.function.ThrowingSupplier<String> s) {
        return org.junit.jupiter.api.Assertions.assertDoesNotThrow(s);
    }

    @Test
    void theListIsCheckedAgainstTheLimit() {
        List<KeyPlace> four = List.of(HALL, new KeyPlace("w", 0, 0, 6, "b", ""), new KeyPlace("w", 0, 0, 6, "c", ""),
                new KeyPlace("w", 0, 0, 6, "d", ""));
        assertNotNull(AtlasConfig.keyPlaceProblem(four, 3));
        assertNull(AtlasConfig.keyPlaceProblem(four, 4));
        assertNotNull(AtlasConfig.keyPlaceProblem(List.of(HALL, HALL), 3));
    }

    // ---- the admin route body -----------------------------------------------------------------

    @Test
    void aPostedPlaceIsReadWithItsDefaults() throws Exception {
        KeyPlace p = KeyPlaceRoutes.parse("{\"world\":\"default\",\"x\":1280,\"z\":\"416\",\"label\":\"hall\"}");
        assertEquals("default", p.world);
        assertEquals(1280, p.x);
        assertEquals(416, p.z);
        assertEquals(6, p.radius);
        assertEquals("", p.name);
        KeyPlace q = KeyPlaceRoutes.parse("{\"world\":\"default\",\"x\":1,\"z\":2,\"label\":\"mill\",\"radius\":3,\"name\":\"  Old Mill \"}");
        assertEquals(3, q.radius);
        assertEquals("Old Mill", q.name);
    }

    @Test
    void aBadPostIsRefused() {
        String[] bad = {
            "",
            "[]",
            "{\"x\":1,\"z\":2,\"label\":\"a\"}",
            "{\"world\":\"w\",\"z\":2,\"label\":\"a\"}",
            "{\"world\":\"w\",\"x\":1.5,\"z\":2,\"label\":\"a\"}",
            "{\"world\":\"w\",\"x\":1,\"z\":2,\"label\":\"Big Hall\"}",
            "{\"world\":\"w\",\"x\":1,\"z\":2,\"label\":\"a\",\"radius\":17}",
            "{\"world\":\"w\",\"x\":1,\"z\":2,\"label\":\"a\",\"radius\":0}",
            "{\"world\":\"w\",\"x\":1,\"z\":2,\"label\":\"a\",\"name\":\"" + "n".repeat(61) + "\"}",
            "{\"world\":\"w\",\"x\":1,\"z\":2,\"label\":\"a\",\"name\":7}",
            "{\"world\":\"w\",\"x\":99999999,\"z\":2,\"label\":\"a\"}",
        };
        for (String b : bad) {
            KeyPlaces.Problem p = assertThrows(KeyPlaces.Problem.class, () -> KeyPlaceRoutes.parse(b), b);
            assertEquals(400, p.status());
        }
    }

    // ---- day folders ------------------------------------------------------------------------------

    private static void day(Path worldDir, String date, String json) throws Exception {
        Path d = worldDir.resolve(date);
        Files.createDirectories(d.resolve("iso"));
        Files.writeString(d.resolve("tiles.json"), "{}");
        if (json != null) {
            Files.writeString(d.resolve("iso").resolve("index.json"), json);
        }
        for (int az : KeyPlaces.AZIMUTHS) {
            Files.write(d.resolve("iso").resolve("hall-" + az + ".png"), new byte[]{1});
        }
    }

    private static String index(String date, boolean unchanged, String sameAs) {
        StringBuilder sb = new StringBuilder("{\"world\":\"default\",\"date\":\"" + date + "\",\"views\":[");
        for (int i = 0; i < 4; i++) {
            int az = KeyPlaces.AZIMUTHS[i];
            sb.append(i == 0 ? "" : ",").append("{\"label\":\"hall\",\"name\":\"Town Hall\",\"keyPlace\":true,\"azimuth\":").append(az)
                    .append(",\"x\":1280,\"z\":416,\"radius\":4,\"scale\":2,\"print\":\"00000000000000aa\",\"unchanged\":")
                    .append(unchanged).append(sameAs == null ? "" : ",\"sameAs\":\"" + sameAs + "\"").append("}");
        }
        return sb.append("]}").toString();
    }

    @Test
    void thePreviousRenderIsTheNewestHeldDayWithEveryDirection(@TempDir Path root) throws Exception {
        Path w = root.resolve("worlds").resolve("default");
        day(w, "2026-10-10", index("2026-10-10", false, null));
        day(w, "2026-10-11", "{\"views\":[{\"label\":\"hall\",\"keyPlace\":true,\"azimuth\":0}]}");
        day(w, "2026-10-12", null);
        Path today = w.resolve("2026-10-13");
        Files.createDirectories(today);
        IsoSnapshots.Previous p = IsoSnapshots.previous(today, "2026-10-13", "hall");
        assertNotNull(p);
        assertEquals("2026-10-10", p.date());
        assertEquals(4, p.entries().size());
        assertNull(IsoSnapshots.previous(today, "2026-10-13", "mill"));
        // A pruned day keeps only a tombstone and no longer counts.
        Files.delete(w.resolve("2026-10-10").resolve("tiles.json"));
        assertNull(IsoSnapshots.previous(today, "2026-10-13", "hall"));
    }

    @Test
    void theDayListNamesKeyPlaceViewsAndPointsUnchangedOnesAtTheirFirstDay(@TempDir Path root) throws Exception {
        Path w = root.resolve("worlds").resolve("default");
        day(w, "2026-10-12", index("2026-10-12", false, null));
        day(w, "2026-10-13", index("2026-10-13", true, "2026-10-12"));
        JsonArray first = IsoSnapshots.views("default", "2026-10-12", w.resolve("2026-10-12"));
        assertEquals(4, first.size());
        JsonObject a = first.get(1).getAsJsonObject();
        assertEquals("hall", a.get("label").getAsString());
        assertEquals("Town Hall", a.get("name").getAsString());
        assertEquals(90, a.get("azimuth").getAsInt());
        assertTrue(a.get("keyPlace").getAsBoolean());
        assertFalse(a.get("unchanged").getAsBoolean());
        assertEquals("api/timeline/default/2026-10-12/iso/hall-90.png", a.get("file").getAsString());
        JsonArray second = IsoSnapshots.views("default", "2026-10-13", w.resolve("2026-10-13"));
        JsonObject b = second.get(1).getAsJsonObject();
        assertTrue(b.get("unchanged").getAsBoolean());
        assertEquals("2026-10-12", b.get("sameAs").getAsString());
        assertEquals("api/timeline/default/2026-10-12/iso/hall-90.png", b.get("file").getAsString());
        // Once the first day is pruned the picture is served from the day's own linked copy.
        Files.delete(w.resolve("2026-10-12").resolve("tiles.json"));
        JsonObject c = IsoSnapshots.views("default", "2026-10-13", w.resolve("2026-10-13")).get(1).getAsJsonObject();
        assertEquals("api/timeline/default/2026-10-13/iso/hall-90.png", c.get("file").getAsString());
    }

    @Test
    void aLaterSlotOfTheDayShowsTheViewsOfTheFirstSlot(@TempDir Path root) throws Exception {
        Path w = root.resolve("worlds").resolve("default");
        day(w, "2026-10-13T04", index("2026-10-13T04", false, null));
        Path later = w.resolve("2026-10-13T10");
        Files.createDirectories(later);
        Files.writeString(later.resolve("tiles.json"), "{}");
        JsonArray v = IsoSnapshots.views("default", "2026-10-13T10", later);
        assertEquals(4, v.size());
        assertEquals("api/timeline/default/2026-10-13T04/iso/hall-0.png", v.get(0).getAsJsonObject().get("file").getAsString());
        assertEquals(w.resolve("2026-10-13T04"), IsoSnapshots.dayViewsDir(w, "2026-10-13"));
        assertNull(IsoSnapshots.dayViewsDir(w, "2026-10-12"));
        // The next day finds the first slot of the day before as its previous render.
        Path next = w.resolve("2026-10-14T04");
        Files.createDirectories(next);
        assertEquals("2026-10-13T04", IsoSnapshots.previous(next, "2026-10-14T04", "hall").date());
    }

    @Test
    void aReusedPictureIsLinkedAndSurvivesTheOriginal(@TempDir Path root) throws Exception {
        Path from = root.resolve("a").resolve("iso").resolve("hall-0.png");
        Files.createDirectories(from.getParent());
        Files.write(from, new byte[]{1, 2, 3});
        Path to = root.resolve("b").resolve("iso").resolve("hall-0.png");
        IsoSnapshots.linkOrCopy(from, to);
        Files.delete(from);
        assertEquals(3, Files.size(to));
        assertEquals(1, Files.list(to.getParent()).count());
    }
}
