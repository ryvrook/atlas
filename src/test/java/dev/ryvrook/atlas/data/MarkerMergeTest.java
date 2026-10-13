package dev.ryvrook.atlas.data;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerMergeTest {

    private static JsonObject marker(String id, String kind, String name, int x, int z) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("kind", kind);
        o.addProperty("name", name);
        o.addProperty("x", x);
        o.addProperty("z", z);
        return o;
    }

    private static MarkerMerge.Candidate c(JsonObject json, int rank, String source, List<String> keys) {
        return new MarkerMerge.Candidate(json, rank, source, keys);
    }

    private static List<String> ids(List<MarkerMerge.Candidate> list) {
        List<String> out = new ArrayList<>();
        for (MarkerMerge.Candidate m : list) {
            out.add(m.json().get("id").getAsString());
        }
        return out;
    }

    @Test
    void sameIdKeepsLowestRank() {
        List<MarkerMerge.Candidate> in = List.of(
                c(marker("a", "engine", "Gate", 10, 10), MarkerMerge.RANK_PROVIDER, "other", List.of()),
                c(marker("a", "poi", "Gate", 10, 10), MarkerMerge.RANK_STORE, "poi", List.of()));
        List<MarkerMerge.Candidate> out = MarkerMerge.dedupe(in, null);
        assertEquals(1, out.size());
        assertEquals("poi", out.get(0).source());
    }

    @Test
    void providerNearStoreWithSameNameIsDropped() {
        List<MarkerMerge.Candidate> in = List.of(
                c(marker("store", "poi", "Old Ruins", 100, 200), MarkerMerge.RANK_STORE, "poi", List.of()),
                c(marker("prov", "engine", "old ruins", 101, 199), MarkerMerge.RANK_PROVIDER, "other", List.of()),
                c(marker("far", "engine", "Old Ruins", 110, 200), MarkerMerge.RANK_PROVIDER, "other", List.of()));
        assertEquals(List.of("store", "far"), ids(MarkerMerge.dedupe(in, null)));
    }

    @Test
    void sameRankNeighboursStay() {
        List<MarkerMerge.Candidate> in = List.of(
                c(marker("s1", "shared", "Camp", 0, 0), MarkerMerge.RANK_SHARED, "shared", List.of()),
                c(marker("s2", "shared", "Camp", 1, 0), MarkerMerge.RANK_SHARED, "shared", List.of()));
        assertEquals(2, MarkerMerge.dedupe(in, null).size());
    }

    @Test
    void userProviderMarkersAreAlwaysDropped() {
        List<MarkerMerge.Candidate> in = List.of(
                c(marker("user_123", "engine", "Mine", 0, 0), MarkerMerge.RANK_PROVIDER, "other", List.of()));
        assertTrue(MarkerMerge.dedupe(in, null).isEmpty());
    }

    @Test
    void personalCopyOfOwnSharedMarkerMarksSharedOwn() {
        UUID me = UUID.randomUUID();
        JsonObject shared = marker("sh", "shared", "Base", 50, 50);
        List<MarkerMerge.Candidate> in = List.of(
                c(shared, MarkerMerge.RANK_SHARED, "shared", List.of(me.toString())),
                c(marker("p1", "personal", "base", 50, 51), MarkerMerge.RANK_OWN, "personal", List.of()));
        List<MarkerMerge.Candidate> out = MarkerMerge.dedupe(in, me);
        assertEquals(List.of("sh"), ids(out));
        assertTrue(shared.get("own").getAsBoolean());
    }

    @Test
    void personalCopyOfSomeoneElsesSharedMarkerStays() {
        UUID me = UUID.randomUUID();
        List<MarkerMerge.Candidate> in = List.of(
                c(marker("sh", "shared", "Base", 50, 50), MarkerMerge.RANK_SHARED, "shared", List.of(UUID.randomUUID().toString())),
                c(marker("p1", "personal", "Base", 50, 50), MarkerMerge.RANK_OWN, "personal", List.of()));
        assertEquals(List.of("sh", "p1"), ids(MarkerMerge.dedupe(in, me)));
    }

    @Test
    void homeSwallowsBedNextToIt() {
        JsonObject bed = marker("bed", "engine", "Bed", 5, 5);
        bed.addProperty("icon", "Home");
        List<MarkerMerge.Candidate> in = List.of(
                c(marker("home_0", "home", "Home", 5, 5), MarkerMerge.RANK_OWN, "home", List.of()),
                c(bed, MarkerMerge.RANK_STORE, "block", List.of()));
        assertEquals(List.of("home_0"), ids(MarkerMerge.dedupe(in, UUID.randomUUID())));
    }

    @Test
    void outputKeepsInputOrder() {
        List<MarkerMerge.Candidate> in = List.of(
                c(marker("prov", "engine", "A", 0, 0), MarkerMerge.RANK_PROVIDER, "other", List.of()),
                c(marker("spawn", "spawn", "World spawn", 500, 500), MarkerMerge.RANK_SPAWN, "spawn", List.of()),
                c(marker("sh", "shared", "B", 900, 900), MarkerMerge.RANK_SHARED, "shared", List.of()));
        assertEquals(List.of("prov", "spawn", "sh"), ids(MarkerMerge.dedupe(in, null)));
    }
}
