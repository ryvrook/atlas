package dev.ryvrook.atlas.integration;

import dev.ryvrook.atlas.config.TrackerMode;
import dev.ryvrook.atlas.integration.TrackerPresence.Found;
import dev.ryvrook.atlas.integration.TrackerPresence.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerPresenceTest {
    private static final Found CURRENT = new Found(new Object(), "1.0.0");
    private static final Found NEWER = new Found(new Object(), "1.2.3");
    private static final Found OLD = new Found(new Object(), "0.9.4");

    @Test
    void autoUsesTrackerApiOnlyWhenNewEnough() {
        assertEquals(Source.TRACKER, TrackerPresence.decide(TrackerMode.AUTO, CURRENT));
        assertEquals(Source.TRACKER, TrackerPresence.decide(TrackerMode.AUTO, NEWER));
        assertEquals(Source.OWN, TrackerPresence.decide(TrackerMode.AUTO, OLD));
        assertEquals(Source.OWN, TrackerPresence.decide(TrackerMode.AUTO, null));
        assertEquals(Source.OWN, TrackerPresence.decide(null, null));
    }

    @Test
    void alwaysTracksNothingWithoutTrackerApi() {
        assertEquals(Source.TRACKER, TrackerPresence.decide(TrackerMode.ALWAYS, CURRENT));
        assertEquals(Source.NONE, TrackerPresence.decide(TrackerMode.ALWAYS, OLD));
        assertEquals(Source.NONE, TrackerPresence.decide(TrackerMode.ALWAYS, null));
    }

    @Test
    void neverIgnoresTrackerApi() {
        assertEquals(Source.OWN, TrackerPresence.decide(TrackerMode.NEVER, CURRENT));
        assertEquals(Source.OWN, TrackerPresence.decide(TrackerMode.NEVER, null));
    }

    @Test
    void oneLineNamesTheSource() {
        assertTrue(TrackerPresence.describe(TrackerMode.AUTO, Source.TRACKER, CURRENT).contains("from TrackerAPI 1.0.0"));
        assertTrue(TrackerPresence.describe(TrackerMode.AUTO, Source.OWN, null).contains("its own systems (tracker auto)"));
        assertTrue(TrackerPresence.describe(TrackerMode.AUTO, Source.OWN, OLD).contains("older than 1.0.0"));
        assertTrue(TrackerPresence.describe(TrackerMode.ALWAYS, Source.NONE, null).contains("not installed"));
    }

    @Test
    void watchAsksForAReloadOncePerChange() {
        TrackerPresence.Watch w = new TrackerPresence.Watch(TrackerMode.AUTO, Source.TRACKER, CURRENT);
        assertNull(w.check(CURRENT));
        String reloaded = w.check(NEWER);
        assertNotNull(reloaded);
        assertTrue(reloaded.contains("was reloaded"));
        assertTrue(reloaded.contains("only be registered at server start"));
        assertNull(w.check(NEWER));
        assertTrue(w.check(null).contains("was unloaded"));
        assertNull(w.check(null));
    }

    @Test
    void watchIsQuietWhenTrackerApiIsIgnored() {
        TrackerPresence.Watch w = new TrackerPresence.Watch(TrackerMode.NEVER, Source.OWN, null);
        assertNull(w.check(CURRENT));
    }

    @Test
    void watchReportsATrackerApiLoadedAfterSetup() {
        TrackerPresence.Watch w = new TrackerPresence.Watch(TrackerMode.AUTO, Source.OWN, null);
        String line = w.check(CURRENT);
        assertTrue(line.contains("was loaded"));
        assertTrue(line.contains("keeps its own systems"));
    }
}
