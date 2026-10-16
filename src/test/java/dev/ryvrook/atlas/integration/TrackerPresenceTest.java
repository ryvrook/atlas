package dev.ryvrook.atlas.integration;

import dev.ryvrook.atlas.config.TrackerMode;
import dev.ryvrook.atlas.integration.PluginPresence.Found;
import dev.ryvrook.atlas.integration.TrackerPresence.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerPresenceTest {
    private static final Found CURRENT = new Found(new Object(), "1.0.0");
    private static final Found NEWER = new Found(new Object(), "1.2.3");
    private static final Found OLD = new Found(new Object(), "0.9.4");
    private static final Found PATCHED = new Found(new Object(), "1.0.2");

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
        TrackerPresence.Watch.Notice reloaded = w.check(PATCHED);
        assertTrue(reloaded.warn());
        assertTrue(reloaded.text().contains("1.0.2 was reloaded"));
        assertTrue(reloaded.text().contains("cannot use the new TrackerAPI classes"));
        assertNull(w.check(PATCHED));
        TrackerPresence.Watch.Notice gone = w.check(null);
        assertTrue(gone.warn(), "an old TrackerAPI does not reload Atlas, so its going away is a warning at once");
        assertTrue(gone.text().contains("was unloaded"));
        assertTrue(gone.text().contains("only be registered at plugin start"));
        assertNull(w.check(null));
    }

    @Test
    void aReloadingTrackerApiIsInformationUntilItStaysAway() {
        TrackerPresence.Watch w = new TrackerPresence.Watch(TrackerMode.AUTO, Source.TRACKER, NEWER);
        TrackerPresence.Watch.Notice gone = w.check(null, 1_000);
        assertFalse(gone.warn());
        assertTrue(gone.text().contains("reloads Atlas by itself"));
        assertNull(w.check(null, 20_000));
        TrackerPresence.Watch.Notice late = w.check(null, 31_000);
        assertTrue(late.warn());
        assertTrue(late.text().contains("unloaded for 30 seconds"));
        assertNull(w.check(null, 60_000), "warned once");
        assertNull(w.check(new Found(new Object(), "1.1.0"), 61_000), "a TrackerAPI that reloads Atlas needs no line");
    }

    @Test
    void watchLeavesTheReloadToATrackerApiThatReloadsItsDependents() {
        TrackerPresence.Watch w = new TrackerPresence.Watch(TrackerMode.AUTO, Source.TRACKER, CURRENT);
        assertNull(w.check(new Found(new Object(), TrackerPresence.RELOADS_DEPENDENTS)));
        assertNull(w.check(NEWER));
        assertFalse(w.check(null, 5_000).warn());
        assertNull(w.check(new Found(new Object(), "1.1.0"), 7_000), "back within the grace, as in a reload");
        TrackerPresence.Watch own = new TrackerPresence.Watch(TrackerMode.AUTO, Source.OWN, null);
        assertNull(own.check(NEWER));
    }

    @Test
    void watchIsQuietWhenTrackerApiIsIgnored() {
        TrackerPresence.Watch w = new TrackerPresence.Watch(TrackerMode.NEVER, Source.OWN, null);
        assertNull(w.check(CURRENT));
    }

    @Test
    void watchReportsATrackerApiLoadedAfterSetup() {
        TrackerPresence.Watch w = new TrackerPresence.Watch(TrackerMode.AUTO, Source.OWN, null);
        String line = w.check(CURRENT).text();
        assertTrue(line.contains("was loaded"));
        assertTrue(line.contains("keeps its own systems"));
    }
}
