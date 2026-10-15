package dev.ryvrook.atlas.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DominionWatchTest {
    @Test
    void dropsTheFileOnlyAfterAMinuteWithoutDominion() {
        DominionWatch w = new DominionWatch();
        assertNull(w.update(true, true, 1_000));
        assertNull(w.update(true, true, 1_000 + DominionWatch.GRACE_MS - 1));
        assertEquals(Boolean.TRUE, w.update(true, true, 1_000 + DominionWatch.GRACE_MS));
        assertTrue(w.gone());
        assertNull(w.update(true, true, 1_000 + 2 * DominionWatch.GRACE_MS));
        assertEquals(Boolean.FALSE, w.update(true, false, 1_000 + 3 * DominionWatch.GRACE_MS));
        assertFalse(w.gone());
    }

    @Test
    void aReloadInsideTheGraceKeepsTheFile() {
        DominionWatch w = new DominionWatch();
        assertNull(w.update(true, true, 0));
        assertNull(w.update(true, false, 5_000));
        assertNull(w.update(true, true, 10_000));
        assertNull(w.update(true, true, 10_000 + DominionWatch.GRACE_MS - 1));
        assertFalse(w.gone());
    }

    @Test
    void aPathOtherThanTheDefaultIsNeverDropped() {
        DominionWatch w = new DominionWatch();
        assertNull(w.update(false, true, 0));
        assertNull(w.update(false, true, 10 * DominionWatch.GRACE_MS));
        assertFalse(w.gone());
    }
}
