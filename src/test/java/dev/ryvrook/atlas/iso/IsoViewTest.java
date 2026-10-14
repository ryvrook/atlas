package dev.ryvrook.atlas.iso;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsoViewTest {
    private static IsoView.Parsed parse(String... kv) {
        Map<String, String> q = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            q.put(kv[i], kv[i + 1]);
        }
        return IsoView.parse("default", q, 16);
    }

    @Test
    void defaultsAreRadiusFourScaleTwoFromTheSouthEast() {
        IsoView v = parse("x", "1280", "z", "416").view();
        assertNotNull(v);
        assertEquals(40, v.cx());
        assertEquals(13, v.cz());
        assertEquals(4, v.radius());
        assertEquals(0, v.azimuth());
        assertEquals(2, v.scale());
        assertEquals(81, v.chunks());
    }

    @Test
    void negativeBlocksRoundDownToTheirChunk() {
        IsoView v = parse("x", "-1", "z", "-33").view();
        assertEquals(-1, v.cx());
        assertEquals(-2, v.cz());
    }

    @Test
    void badValuesAnswerWithASentence() {
        assertNotNull(parse("z", "1").error());
        assertNotNull(parse("x", "a", "z", "1").error());
        assertNotNull(parse("x", "1", "z", "1", "r", "0").error());
        assertNotNull(parse("x", "1", "z", "1", "r", "17").error());
        assertNotNull(parse("x", "1", "z", "1", "az", "45").error());
        assertNotNull(parse("x", "1", "z", "1", "s", "4").error());
        assertNotNull(parse("x", "1", "z", "1", "s", "0").error());
        assertNotNull(parse("x", "99999999", "z", "1").error());
        for (String e : new String[]{parse("z", "1").error(), parse("x", "1", "z", "1", "r", "17").error()}) {
            assertTrue(!e.contains(";") && !e.contains("\u2014"), e);
        }
    }

    @Test
    void maxRadiusFromTheSettingsApplies() {
        assertNotNull(IsoView.parse("w", Map.of("x", "0", "z", "0", "r", "9"), 8).error());
        assertEquals(8, IsoView.parse("w", Map.of("x", "0", "z", "0", "r", "8"), 8).view().radius());
        assertEquals(2, IsoView.parse("w", Map.of("x", "0", "z", "0"), 2).view().radius());
    }

    @Test
    void scaleIsLoweredForWideViews() {
        assertEquals(3, IsoView.maxScale(4));
        assertEquals(3, IsoView.maxScale(5));
        assertEquals(2, IsoView.maxScale(8));
        assertEquals(1, IsoView.maxScale(16));
        assertEquals(1, parse("x", "0", "z", "0", "r", "16", "s", "3").view().scale());
        assertEquals(3, parse("x", "0", "z", "0", "r", "2", "s", "3").view().scale());
        assertTrue(parse("x", "0", "z", "0", "r", "16").view().large());
        assertTrue(!parse("x", "0", "z", "0", "r", "8").view().large());
    }

    @Test
    void fileNamesRoundTrip() {
        IsoView v = new IsoView("default", -3, 12, 5, 270, 1);
        String name = v.fileName(4242);
        assertEquals("-3_12_r5_a270_s1_t4242_v" + IsoRaster.STYLE + ".png", name);
        IsoView.Named n = IsoView.parseName("default", name);
        assertEquals(v, n.view());
        assertEquals(4242, n.bucket());
        assertEquals(IsoRaster.STYLE, n.style());
        assertEquals(1, IsoView.parseName("default", "-3_12_r5_a270_s1_t4242.png").style());
        assertNull(IsoView.parseName("default", "../x.png"));
        assertTrue(v.covers(-8, 17));
        assertTrue(!v.covers(-9, 12));
    }
}
