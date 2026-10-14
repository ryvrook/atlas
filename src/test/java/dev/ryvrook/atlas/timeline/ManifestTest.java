package dev.ryvrook.atlas.timeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ManifestTest {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);

    @Test
    void diffListsChangedAndNewKeysOnly() {
        Map<String, String> prev = Map.of("0/0/0", A, "0/1/0", B, "1/0/0", C);
        Map<String, String> next = Map.of("0/0/0", A, "0/1/0", C, "0/2/0", A);
        assertEquals(List.of("0/1/0", "0/2/0"), Manifest.diff(prev, next));
    }

    @Test
    void firstDayListsEveryKey() {
        assertEquals(List.of("-1/3/-2", "0/0/0"), Manifest.diff(null, Map.of("0/0/0", A, "-1/3/-2", B)));
    }

    @Test
    void keysRoundTrip() {
        assertEquals("-2/5/-7", Manifest.key(-2, 5, -7));
        assertArrayEquals(new int[]{-2, 5, -7}, Manifest.parseKey("-2/5/-7"));
        assertNull(Manifest.parseKey("0/1"));
        assertNull(Manifest.parseKey("a/b/c"));
    }

    @Test
    void jsonRoundTripDropsBadEntries() {
        Manifest m = new Manifest("default", "2026-10-12", 5L, null, true, 2, 1,
                Map.of("0/0/0", A, "0/1/0", B), List.of("0/1/0"));
        var o = m.toJson();
        o.getAsJsonObject("tiles").addProperty("0/9/9", "not a hash");
        o.getAsJsonObject("tiles").addProperty("bad", A);
        Manifest back = Manifest.fromJson(o);
        assertEquals(m.tiles(), back.tiles());
        assertEquals(m.changed(), back.changed());
        assertEquals(true, back.partial());
        assertEquals(2, back.skipped());
        assertEquals("2026-10-12", back.date());
        // Written before tiles were carried over, so not complete.
        assertEquals(false, back.complete());
        assertEquals(0, back.carried());
    }

    @Test
    void carriedAndCompleteRoundTrip() {
        Manifest m = new Manifest("default", "2026-10-14T04", 5L, "2026-10-13T22", false, 0, 1,
                Map.of("0/0/0", A, "-1/0/0", B), List.of(), 1, 2, true);
        Manifest back = Manifest.fromJson(m.toJson());
        assertEquals(1, back.carried());
        assertEquals(2, back.composed());
        assertEquals(true, back.complete());
        assertEquals(2, m.summary().get("tiles").getAsInt());
        assertEquals(1, m.summary().get("carried").getAsInt());
    }

    @Test
    void onlyBaseZoomTilesCountAsBlockChanges() {
        Map<String, String> prev = Map.of("0/0/0", A, "0/1/0", B, "1/0/0", C);
        // An upper tile drawn anew, a lower tile a browser asked for and an upper tile with new bytes are no block change.
        Map<String, String> same = Map.of("0/0/0", A, "0/1/0", B, "1/0/0", A, "2/0/0", A, "-1/0/0", C);
        assertEquals(0, Manifest.blockChanges(prev, same));
        assertEquals(3, Manifest.diff(prev, same).size(), "diff still lists the new and redrawn keys");
        // A redrawn base tile and newly explored ground are.
        Map<String, String> next = Map.of("0/0/0", C, "0/1/0", B, "0/5/5", A, "1/0/0", C);
        assertEquals(2, Manifest.blockChanges(prev, next));
        assertEquals(2, Manifest.blockChanges(null, Map.of("0/0/0", A, "0/1/0", B, "1/0/0", C)));
    }
}
