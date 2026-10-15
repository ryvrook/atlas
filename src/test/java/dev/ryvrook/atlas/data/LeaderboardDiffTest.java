package dev.ryvrook.atlas.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeaderboardDiffTest {
    private static JsonObject j(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void onlyNewUnlocksMilestonesAndFirsts() {
        JsonObject before = j("{\"recent\": [{\"at\": 100, \"uuid\": \"00000000-0000-0000-0000-000000000001\", \"name\": \"A\", \"feat\": \"f1\"}],"
                + "\"milestones\": {\"m1\": {\"at\": 50, \"byName\": \"A\"}}, \"firsts\": {}}");
        JsonObject next = j("{\"recent\": [{\"at\": 100, \"uuid\": \"00000000-0000-0000-0000-000000000001\", \"name\": \"A\", \"feat\": \"f1\"},"
                + "{\"at\": 100, \"uuid\": \"00000000-0000-0000-0000-000000000002\", \"name\": \"B\", \"feat\": \"f1\"},"
                + "{\"at\": 200, \"name\": \"C\", \"feat\": \"f2\", \"featName\": \"Second\", \"tier\": \"Gold\"},"
                + "{\"at\": 90, \"name\": \"Old\", \"feat\": \"f0\"}],"
                + "\"milestones\": {\"m1\": {\"at\": 50, \"byName\": \"A\"}, \"m2\": {\"at\": 150}},"
                + "\"firsts\": {\"f2\": {\"at\": 200, \"by\": \"00000000-0000-0000-0000-000000000003\", \"byName\": \"C\"}}}");
        List<Leaderboard.FeatEvent> out = Leaderboard.diff(before, next);
        assertEquals(4, out.size());
        assertEquals("feats.unlock", out.get(0).kind());
        assertEquals("B", out.get(0).name());
        assertEquals("feats.milestone", out.get(1).kind());
        assertEquals(null, out.get(1).name());
        assertEquals("Second", out.stream().filter(e -> e.kind().equals("feats.unlock") && e.at() == 200).findFirst().get().featName());
        assertEquals(1, out.stream().filter(e -> e.kind().equals("feats.first")).count());
        assertEquals(0, Leaderboard.diff(next, next).size());
    }

    @Test
    void aHiddenMilestoneIsMarkedForTheTimeline() {
        JsonObject before = j("{\"recent\": [], \"milestones\": {}, \"firsts\": {}}");
        JsonObject next = j("{\"recent\": [], \"milestones\": {\"m9\": {\"at\": 50, \"featName\": \"Gatekeeper\", \"hidden\": true}},"
                + " \"firsts\": {\"f9\": {\"at\": 60, \"featName\": \"Open\", \"hidden\": false}}}");
        List<Leaderboard.FeatEvent> out = Leaderboard.diff(before, next);
        assertEquals(2, out.size());
        assertEquals(true, out.get(0).hiddenFeat());
        assertEquals(false, out.get(1).hiddenFeat());
    }
}
