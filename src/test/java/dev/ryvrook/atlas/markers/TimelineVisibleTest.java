package dev.ryvrook.atlas.markers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineVisibleTest {
    @Test
    void onlyPublicLayersReachTheTimeline() throws Exception {
        assertTrue(MarkerService.timelineVisible(null), "a fresh admin layer is public");
        for (String tier : new String[]{"public", "linked", "faction", "admin"}) {
            boolean visible = MarkerService.timelineVisible(LayerFile.parse("atlas", "admin",
                    "{\"tier\": \"" + tier + "\", \"world\": \"w\", \"markers\": [{\"id\": \"a\", \"x\": 1, \"z\": 2}]}"));
            if ("public".equals(tier)) {
                assertTrue(visible, tier);
            } else {
                assertFalse(visible, tier);
            }
        }
    }
}
