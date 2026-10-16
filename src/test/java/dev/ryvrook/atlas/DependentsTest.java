package dev.ryvrook.atlas;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DependentsTest {
    private static final String ATLAS = "ryvrook:atlas";
    private static final String DOMINION = "ryvrook:dominion";
    private static final String SOCIALBRIDGE = "ryvrook:socialbridge";
    private static final String RESTAPI = "ryvrook:restapi";

    /** The plugins that list Atlas, with what else they read from that list. */
    private static final Map<String, Set<String>> SUITE = Map.of(
            DOMINION, Set.of(ATLAS),
            SOCIALBRIDGE, Set.of(ATLAS, DOMINION),
            RESTAPI, Set.of(ATLAS, DOMINION));

    @Test
    void dominionComesBackBeforeThePluginsThatReadIt() {
        assertEquals(List.of(DOMINION, SOCIALBRIDGE, RESTAPI),
                Dependents.order(List.of(SOCIALBRIDGE, RESTAPI, DOMINION), SUITE));
    }

    @Test
    void aCycleStillReloadsEveryPluginOnce() {
        Map<String, Set<String>> cycle = Map.of("a:a", Set.of("b:b"), "b:b", Set.of("a:a"));
        assertEquals(List.of("b:b", "a:a"), Dependents.order(List.of("a:a", "b:b"), cycle));
    }

    @Test
    void atlasTakesTheSuiteLock() {
        assertSame(Dependents.sharedLock(), System.getProperties().get("ryvrook.dependents.lock"));
    }
}
