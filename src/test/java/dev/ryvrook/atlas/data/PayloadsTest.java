package dev.ryvrook.atlas.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadsTest {
    private static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ALEX = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ADMIN = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static Set<String> keys(JsonObject o) {
        return new TreeSet<>(o.keySet());
    }

    private static Set<String> set(String... k) {
        return new TreeSet<>(Set.of(k));
    }

    // ---- hidden rule ----------------------------------------------------------

    @Test
    void killFieldsArePinned() {
        JsonObject victim = parse("{\"name\":\"Bob\"}");
        JsonObject k = Payloads.kill(5L, "default", victim, null, "fall", false, 96, -32);
        assertEquals(set("at", "world", "victim", "killer", "cause", "ranged", "x", "z"), keys(k));
        assertEquals(96, k.get("x").getAsInt());
        assertTrue(k.get("killer").isJsonNull());
        JsonObject unknown = Payloads.kill(5L, null, victim, null, null, true, null, null);
        assertEquals(keys(k), keys(unknown));
        assertTrue(unknown.get("x").isJsonNull());
        assertTrue(unknown.get("z").isJsonNull());
    }

    @Test
    void placesTheLiveMapWouldNotShowAreNotRecorded() {
        assertFalse(Payloads.placeWithheld(64, 0, false, false));
        // Below the hideBelowY line.
        assertTrue(Payloads.placeWithheld(-5, 0, false, false));
        assertFalse(Payloads.placeWithheld(0, 0, false, false));
        // Crouching or invisible under a hide rule.
        assertTrue(Payloads.placeWithheld(64, 0, true, false));
        // In the territory of their own faction while hidePlayersInOwnTerritory is on.
        assertTrue(Payloads.placeWithheld(64, 0, false, true));
        assertTrue(Payloads.placeWithheld(Double.NaN, 0, false, false));
    }

    @Test
    void hiddenRuleSparesAdminsAndSelf() {
        Predicate<UUID> hidden = u -> u.equals(STEVE);
        assertTrue(Payloads.hiddenFrom(false, ALEX, STEVE, hidden));
        assertTrue(Payloads.hiddenFrom(false, null, STEVE, hidden));
        assertFalse(Payloads.hiddenFrom(true, ADMIN, STEVE, hidden));
        assertFalse(Payloads.hiddenFrom(false, STEVE, STEVE, hidden));
        assertFalse(Payloads.hiddenFrom(false, STEVE, ALEX, hidden));
        assertFalse(Payloads.hiddenFrom(false, ALEX, null, hidden));
    }

    @Test
    void personWritesAPlayerWithNullUuidWhenHidden() {
        JsonObject o = new JsonObject();
        Payloads.person(o, "uuid", "name", STEVE, "Steve", true);
        assertTrue(o.get("uuid").isJsonNull());
        assertEquals("A player", o.get("name").getAsString());
        JsonObject p = new JsonObject();
        Payloads.person(p, "uuid", "name", STEVE, "Steve", false);
        assertEquals(STEVE.toString(), p.get("uuid").getAsString());
        assertEquals("Steve", p.get("name").getAsString());
    }

    // ---- Feats ----------------------------------------------------------------

    private static final String FEATS = """
            {"updatedAt": 1760000000000, "totalFeats": 218, "totalPoints": 7870, "online": 3, "rarityPlayers": 42,
             "secretField": "must not leak",
             "rarity": {"first_steps": 0.9524, "rock_bottom": 0.0310, "bad": "x"},
             "players": [
              {"uuid": "11111111-1111-1111-1111-111111111111", "name": "Steve", "points": 1240, "unlocked": 58,
               "lastUnlockAt": 1759990000000, "extra": 1},
              {"uuid": "22222222-2222-2222-2222-222222222222", "name": "Alex", "points": 900, "unlocked": 40,
               "lastUnlockAt": 0}
             ],
             "recent": [
              {"at": 1759990000000, "uuid": "11111111-1111-1111-1111-111111111111", "name": "Steve",
               "feat": "rock_bottom", "featName": "Rock Bottom", "tier": "Bronze", "points": 10,
               "category": "Mining", "hidden": false, "internal": {"x": 1}},
              {"at": 1759990000001, "uuid": "22222222-2222-2222-2222-222222222222", "name": "Alex",
               "feat": "unknown_feat", "featName": "Unknown", "tier": "Gold", "points": 50,
               "category": "Combat", "hidden": true}
             ],
             "milestones": {
              "world_power": {"at": 1759980000000, "by": null, "byName": null, "featName": "Lights On", "tier": "Silver"},
              "world_gate": {"at": 1759970000000, "by": "22222222-2222-2222-2222-222222222222", "byName": "Alex",
               "featName": "Gatekeeper", "tier": "Gold"}
             },
             "firsts": {
              "first_gobliterator": {"at": 1759990000000, "by": "11111111-1111-1111-1111-111111111111",
               "byName": "Steve", "featName": "Pioneer", "tier": "Platinum", "secret": true}
             }}
            """;

    @Test
    void leaderboardHasExactFieldsAndNoRawKeys() {
        JsonObject out = Payloads.leaderboard(parse(FEATS), 1760000000000L, u -> false);
        assertEquals(set("available", "updatedAt", "totalFeats", "totalPoints", "online", "rarityPlayers", "rarity",
                "players", "recent", "milestones", "firsts"), keys(out));
        assertFalse(out.toString().contains("secret"));
        assertFalse(out.toString().contains("internal"));
        assertFalse(out.toString().contains("extra"));
        assertEquals(42, out.get("rarityPlayers").getAsInt());
        assertEquals(3, out.get("online").getAsInt());
        assertEquals(Set.of("rock_bottom"), out.getAsJsonObject("rarity").keySet(),
                "rarity keeps only feats the payload names");
        assertEquals(Set.of("first_steps", "rock_bottom"), Payloads.leaderboard(parse(FEATS), 1L, u -> false, null, true)
                .getAsJsonObject("rarity").keySet());

        JsonObject row = out.getAsJsonArray("players").get(0).getAsJsonObject();
        assertEquals(set("rank", "uuid", "name", "points", "unlocked", "lastUnlockAt"), keys(row));
        assertEquals(1, row.get("rank").getAsInt());
        assertEquals(1759990000000L, row.get("lastUnlockAt").getAsLong());
        assertTrue(out.getAsJsonArray("players").get(1).getAsJsonObject().get("lastUnlockAt").isJsonNull());

        JsonObject unlock = out.getAsJsonArray("recent").get(0).getAsJsonObject();
        assertEquals(set("at", "uuid", "name", "feat", "featName", "tier", "points", "category", "hiddenFeat", "rarity"),
                keys(unlock));
        assertEquals(0.031, unlock.get("rarity").getAsDouble(), 1e-9);
        assertTrue(out.getAsJsonArray("recent").get(1).getAsJsonObject().get("rarity").isJsonNull());
        assertTrue(out.getAsJsonArray("recent").get(1).getAsJsonObject().get("hiddenFeat").getAsBoolean());

        JsonArray milestones = out.getAsJsonArray("milestones");
        assertEquals(2, milestones.size());
        // Oldest first.
        assertEquals("world_gate", milestones.get(0).getAsJsonObject().get("feat").getAsString());
        JsonObject nobody = milestones.get(1).getAsJsonObject();
        assertEquals(set("feat", "featName", "tier", "at", "uuid", "name"), keys(nobody));
        assertTrue(nobody.get("uuid").isJsonNull());
        assertTrue(nobody.get("name").isJsonNull());
        JsonObject first = out.getAsJsonArray("firsts").get(0).getAsJsonObject();
        assertEquals("Steve", first.get("name").getAsString());
        assertEquals(STEVE.toString(), first.get("uuid").getAsString());
    }

    @Test
    void leaderboardAppliesHiddenRuleEverywhere() {
        JsonObject out = Payloads.leaderboard(parse(FEATS), 1L, u -> STEVE.equals(u));
        JsonObject row = out.getAsJsonArray("players").get(0).getAsJsonObject();
        assertEquals("A player", row.get("name").getAsString());
        assertTrue(row.get("uuid").isJsonNull());
        JsonObject unlock = out.getAsJsonArray("recent").get(0).getAsJsonObject();
        assertEquals("A player", unlock.get("name").getAsString());
        assertTrue(unlock.get("uuid").isJsonNull());
        JsonObject first = out.getAsJsonArray("firsts").get(0).getAsJsonObject();
        assertEquals("A player", first.get("name").getAsString());
        assertTrue(first.get("uuid").isJsonNull());
        // Alex is not hidden.
        JsonObject gate = out.getAsJsonArray("milestones").get(0).getAsJsonObject();
        assertEquals("Alex", gate.get("name").getAsString());
        assertFalse(out.toString().contains("Steve"));
        assertFalse(out.toString().contains(STEVE.toString()));
    }

    @Test
    void emptyLeaderboardHasEveryKey() {
        JsonObject out = Payloads.leaderboard(null, 0, u -> false);
        assertFalse(out.get("available").getAsBoolean());
        assertEquals(keys(Payloads.leaderboard(parse(FEATS), 1, u -> false)), keys(out));
    }

    // ---- Dominion -------------------------------------------------------------

    private static final String SNAPSHOT = """
            {"version": 1, "at": 1760000000000,
             "worlds": [{"name": "default",
               "players": [{"uuid": "11111111-1111-1111-1111-111111111111", "name": "Steve", "x": 1, "y": 70, "z": 2,
                 "faction": "RED", "color": "#ff0000", "hidden": true}],
               "territories": [{"id": "f1", "tag": "RED", "name": "Red Hand", "color": "#ff0000", "type": "NORMAL",
                 "raidable": true, "chunks": [[0, 0], [1, 0], "junk", [2]], "breached": [[1, 0]],
                 "zones": [{"id": "z1", "name": "Market", "chunks": [[0, 0]]}, {"id": "z2", "name": "", "chunks": [[0, 0]]}],
                 "privateNote": "x"}],
               "points": [{"id": "hill:north", "kind": "hill", "label": "North", "x": 10.6, "y": 64, "z": -5.2,
                 "color": "#00ff00", "faction": null, "expiresAt": 0, "leak": 1}]}],
             "factions": [{"id": "f1", "tag": "RED", "name": "Red Hand", "color": "#ff0000", "nation": "NORTH",
               "leader": "Steve", "members": 3, "memberIds": ["a", "b", "c"], "online": 1, "claims": 2,
               "power": 10.5, "maxPower": 20, "value": 1234.5, "rank": 1, "kills": 4, "deaths": 2,
               "siegesWon": 1, "siegesLost": 0, "level": 3, "points": 250, "bank": 99.5,
               "relations": {"BLUE": "ENEMY", "GRN": "NEUTRAL"}, "createdAt": 1750000000000,
               "description": "We are red", "secretStash": 1000}],
             "nations": [{"id": "n1", "tag": "NORTH", "name": "The North", "color": "#0000ff", "members": ["RED"]}],
             "sieges": [{"id": "s1", "attacker": "BLUE", "defender": "RED", "phase": "CAPTURING", "world": "default",
               "chunk": [1, 0], "endsAt": 1760000060000, "raw": true}],
             "events": {"enabled": true, "koths": [{"name": "North", "status": "Capturing", "holder": "RED"}],
               "outposts": [], "envoys": ["Envoy A"], "bounties": 2, "season": {"status": "Week 2", "endsAt": 1761000000000}},
             "grace": {"active": false, "endsAt": 0}, "eotw": false}
            """;

    private static DominionSnapshot.View view() {
        return new DominionSnapshot.View(parse(SNAPSHOT), 1760000000000L);
    }

    @Test
    void factionRowIsExplicit() {
        DominionSnapshot.View v = view();
        JsonObject f = v.faction("RED");
        JsonObject row = Payloads.faction(f, false, v.nationOf(f));
        assertEquals(set("id", "tag", "name", "color", "leader", "members", "online", "claims", "power", "maxPower",
                "value", "rank", "kills", "deaths", "siegesWon", "siegesLost", "level", "points", "relations",
                "createdAt", "description", "bank", "nation"), keys(row));
        assertFalse(row.toString().contains("secretStash"));
        assertFalse(row.toString().contains("memberIds"));
        assertTrue(row.get("bank").isJsonNull());
        assertEquals(3, row.get("members").getAsInt());
        assertEquals(Set.of("BLUE"), row.getAsJsonObject("relations").keySet());
        JsonObject nation = row.getAsJsonObject("nation");
        assertEquals(set("tag", "name", "color"), keys(nation));
        assertEquals("The North", nation.get("name").getAsString());
        assertEquals(99.5, Payloads.faction(f, true, null).get("bank").getAsDouble(), 1e-9);
        assertTrue(Payloads.faction(f, true, null).get("nation").isJsonNull());
    }

    @Test
    void territoryCleansChunksAndZones() {
        DominionSnapshot.View v = view();
        JsonObject t = v.worldArray("default", "territories").get(0).getAsJsonObject();
        JsonObject row = Payloads.territory(t, v.faction("RED"));
        assertEquals(set("id", "tag", "name", "color", "type", "raidable", "chunks", "breached", "zones", "power",
                "maxPower", "claims"), keys(row));
        assertEquals(2, row.getAsJsonArray("chunks").size());
        assertEquals(1, row.getAsJsonArray("zones").size());
        assertEquals(set("id", "name", "chunks"), keys(row.getAsJsonArray("zones").get(0).getAsJsonObject()));
        assertFalse(row.toString().contains("privateNote"));
        JsonObject bare = Payloads.territory(t, null);
        assertTrue(bare.get("power").isJsonNull());
    }

    @Test
    void siegeAndPointShapes() {
        DominionSnapshot.View v = view();
        JsonObject s = Payloads.siege(v.sieges().get(0).getAsJsonObject());
        assertEquals(set("id", "attacker", "defender", "phase", "world", "chunk", "endsAt"), keys(s));
        assertEquals("[1,0]", s.get("chunk").toString());
        JsonObject p = v.worldArray("default", "points").get(0).getAsJsonObject();
        JsonObject pub = Payloads.point(p, false, v.events());
        assertEquals(set("id", "kind", "label", "x", "y", "z", "color", "faction", "expiresAt", "event", "holder",
                "control", "status"), keys(pub));
        assertTrue(pub.get("y").isJsonNull());
        assertEquals(11, pub.get("x").getAsLong());
        assertEquals(-5, pub.get("z").getAsLong());
        assertTrue(pub.get("expiresAt").isJsonNull());
        assertTrue(pub.get("event").getAsBoolean());
        assertEquals("RED", pub.get("holder").getAsString());
        assertEquals("Capturing", pub.get("status").getAsString());
        assertEquals(64, Payloads.point(p, true, v.events()).get("y").getAsLong());
    }

    @Test
    void eventsSummaryAndGrace() {
        DominionSnapshot.View v = view();
        JsonObject e = Payloads.events(v.events());
        assertEquals(set("enabled", "koths", "outposts", "envoys", "bounties", "season", "seasonEndsAt"), keys(e));
        assertEquals(set("name", "status", "holder", "control", "endsAt"), keys(e.getAsJsonArray("koths").get(0).getAsJsonObject()));
        assertEquals("Week 2", e.get("season").getAsString());
        assertEquals(1761000000000L, e.get("seasonEndsAt").getAsLong());
        JsonElement g = Payloads.grace(parse(SNAPSHOT).getAsJsonObject("grace"));
        assertTrue(g.getAsJsonObject().get("endsAt").isJsonNull());
        assertTrue(Payloads.grace(null).isJsonNull());
    }

    @Test
    void feedLinesAreReduced() {
        JsonObject raw = parse("{\"at\": 5, \"type\": \"claim\", \"text\": \"RED claimed land.\", \"actor\": \"x\"}");
        JsonObject line = Payloads.feedLine(raw);
        assertEquals(set("at", "type", "text", "source", "world"), keys(line));
        assertEquals("dominion", line.get("source").getAsString());
        assertTrue(line.get("world").isJsonNull());
        JsonObject portal = Payloads.feedLine(parse("{\"at\": 5, \"type\": \"death\", \"text\": \"A player died.\", "
                + "\"source\": \"atlas\", \"world\": \"portal-1\"}"));
        assertEquals("portal-1", portal.get("world").getAsString());
        assertEquals("atlas", portal.get("source").getAsString());
        assertNull(Payloads.feedLine(parse("{\"at\": 5}")));
    }

    @Test
    void snapshotVersionCheck() {
        assertNull(DominionSnapshot.versionOf(parse("{\"version\": 1}")));
        assertEquals("2", DominionSnapshot.versionOf(parse("{\"version\": 2}")));
        assertEquals("none", DominionSnapshot.versionOf(parse("{}")));
    }

    @Test
    void nullsAreAlwaysPresent() {
        JsonObject f = Payloads.faction(parse("{\"tag\": \"X\"}"), true, null);
        for (Map.Entry<String, JsonElement> e : f.entrySet()) {
            assertTrue(e.getValue() != null, e.getKey());
        }
        assertTrue(f.get("color").isJsonNull());
        assertTrue(f.get("createdAt").isJsonNull());
        assertTrue(f.get("bank").isJsonNull());
        assertEquals("X", f.get("name").getAsString());
    }

    @Test
    void aHiddenFeatIsNamedOnlyForWhoEarnedItAndAdmins() {
        UUID alex = UUID.fromString("22222222-2222-2222-2222-222222222222");
        JsonObject pub = Payloads.leaderboard(parse(FEATS), 1L, u -> false, null, false);
        JsonObject masked = pub.getAsJsonArray("recent").get(1).getAsJsonObject();
        assertEquals(Payloads.HIDDEN_FEAT_NAME, masked.get("featName").getAsString());
        assertEquals("hidden", masked.get("feat").getAsString());
        assertEquals("", masked.get("category").getAsString());
        assertTrue(masked.get("rarity").isJsonNull());
        assertEquals("Rock Bottom", pub.getAsJsonArray("recent").get(0).getAsJsonObject().get("featName").getAsString());
        assertFalse(pub.toString().contains("unknown_feat"));
        assertFalse(pub.toString().contains("\"Unknown\""));

        JsonObject mine = Payloads.leaderboard(parse(FEATS), 1L, u -> false, alex, false);
        assertEquals("Unknown", mine.getAsJsonArray("recent").get(1).getAsJsonObject().get("featName").getAsString());
        JsonObject steve = Payloads.leaderboard(parse(FEATS), 1L, u -> false, STEVE, false);
        assertEquals(Payloads.HIDDEN_FEAT_NAME, steve.getAsJsonArray("recent").get(1).getAsJsonObject().get("featName").getAsString());
        JsonObject admin = Payloads.leaderboard(parse(FEATS), 1L, u -> false, null, true);
        assertEquals("Unknown", admin.getAsJsonArray("recent").get(1).getAsJsonObject().get("featName").getAsString());
    }

    @Test
    void hiddenMilestonesAndUnearnedHiddenFeatsStayHidden() {
        JsonObject root = parse("""
                {"rarity": {"secret_cake": 0.0, "world_gate": 0.5},
                 "recent": [],
                 "milestones": {"world_gate": {"at": 1759970000000, "by": null, "byName": null,
                   "featName": "Gatekeeper", "tier": "Gold", "hidden": true}},
                 "firsts": {}}
                """);
        JsonObject pub = Payloads.leaderboard(root, 1L, u -> false, null, false);
        assertFalse(pub.toString().contains("Gatekeeper"), "the hidden flag on a milestone masks it");
        assertFalse(pub.toString().contains("world_gate"));
        assertFalse(pub.toString().contains("secret_cake"), "a hidden feat nobody earned is not listed in rarity");
        JsonObject admin = Payloads.leaderboard(root, 1L, u -> false, null, true);
        assertTrue(admin.toString().contains("Gatekeeper"));
        assertTrue(admin.toString().contains("secret_cake"));
    }
}
