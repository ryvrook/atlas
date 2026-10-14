package dev.ryvrook.atlas.integration;

import com.google.gson.JsonArray;
import dev.ryvrook.atlas.data.HeatTracker;
import dev.ryvrook.atlas.data.KillLog;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.Counters;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.Journal;
import dev.ryvrook.trackerapi.api.PlayerSnapshot;
import dev.ryvrook.trackerapi.api.Stats;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerApi;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.BlockBroken;
import dev.ryvrook.trackerapi.api.event.BlockPlaced;
import dev.ryvrook.trackerapi.api.event.EntityDied;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds synthetic TrackerAPI records through the subscriptions the bridge makes and checks the heat counts
 * and kill lines are the ones BlockActivitySystem and DeathSystem produce for the same engine event.
 */
class TrackerBridgeTest {
    private static final String WORLD = "default";
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID WOLF = UUID.fromString("00000000-0000-0000-0000-0000000000f0");

    @TempDir
    Path dir;

    private HeatTracker heat;
    private final List<KillLog.Kill> kills = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    /** Players whose place the rule withholds. */
    private final Set<UUID> withheld = new HashSet<>();
    private FakeApi api;
    private TrackerBridge bridge;
    private long seq;

    @BeforeEach
    void setUp() {
        heat = new HeatTracker(dir);
        api = new FakeApi();
        bridge = new TrackerBridge(heat, kills::add, (p, w, x, y, z) -> withheld.contains(p), warnings::add);
        bridge.bind(api);
    }

    private Header header(UUID player) {
        return header(System.currentTimeMillis(), WORLD, player);
    }

    private Header header(long at, String world, UUID player) {
        return new Header(at, ++seq, world, player);
    }

    private static Actor player(UUID uuid, String name) {
        return Actor.player(uuid, name);
    }

    private EntityDied death(Actor victim, Actor killer, double x, double y, double z, boolean boss, boolean ranged) {
        UUID p = victim.isPlayer() ? victim.uuid() : killer != null && killer.isPlayer() ? killer.uuid() : null;
        return new EntityDied(header(p), victim, killer, "Physical", null, ranged, false, x, y, z, boss,
                List.of(), Set.of(), false, null, 0);
    }

    private void standing(UUID uuid, String world, double x, double y, double z) {
        api.snapshots.put(uuid, new PlayerSnapshot(uuid, "p", world, x, y, z, 0f, 0f, "Adventure", null, null, null,
                false, false, false, false, 0L, System.currentTimeMillis()));
    }

    // ---- binding ---------------------------------------------------------------------------------------

    @Test
    void bindSubscribesToBreaksPlacesAndDeathsAsync() {
        assertTrue(bridge.bound());
        assertEquals(Set.of(EventKind.BLOCK_BROKEN, EventKind.BLOCK_PLACED, EventKind.ENTITY_DIED), api.kinds());
        for (FakeSubscription s : api.subs) {
            assertEquals("Atlas", s.owner());
            assertEquals(Delivery.ASYNC, s.delivery());
        }
        assertTrue(warnings.isEmpty());
    }

    @Test
    void unbindClosesEverySubscription() {
        bridge.unbind();
        assertFalse(bridge.bound());
        assertTrue(api.subs.stream().noneMatch(FakeSubscription::isActive));
        api.publish(new BlockBroken(header(ALICE), 1, 64, 1, "Rock_Stone", null, false, false));
        assertEquals(0, heat.totals(0)[0]);
    }

    @Test
    void bindingAgainReplacesTheOldSubscriptions() {
        bridge.bind(api);
        assertEquals(3, api.subs.stream().filter(FakeSubscription::isActive).count());
        api.publish(new BlockBroken(header(ALICE), 1, 64, 1, "Rock_Stone", null, false, false));
        assertEquals(1, heat.totals(0)[0]);
    }

    @Test
    void warnsWhenTrackerApiTracksAKindNot() {
        FakeApi partial = new FakeApi();
        partial.off.add(EventKind.ENTITY_DIED);
        bridge.bind(partial);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("ENTITY_DIED"));
    }

    @Test
    void installBindsThroughTheEnableHookAndCloseDropsIt() {
        bridge.unbind();
        api.up();
        try {
            TrackerLink link = TrackerBridge.install(heat, kills::add, null, null);
            assertTrue(link.bound());
            api.down();
            assertFalse(link.bound());
            api.up();
            assertTrue(link.bound());
            link.close();
            assertFalse(link.bound());
            api.down();
            api.up();
            assertFalse(link.bound());
        } finally {
            api.down();
        }
    }

    // ---- heat, as BlockActivitySystem --------------------------------------------------------------------

    @Test
    void breaksAndPlacesCountPerChunkLikeTheBlockSystem() {
        // BlockActivitySystem records heat.record(world, target.x, target.z, place) per uncancelled event.
        api.publish(new BlockBroken(header(ALICE), 40, 64, -5, "Rock_Stone", "Tool_Pickaxe_Iron", false, false));
        api.publish(new BlockBroken(header(ALICE), 63, 10, -33, "Soil_Dirt", null, true, true));
        api.publish(new BlockPlaced(header(ALICE), 33, 64, -1, "Wood_Oak_Planks", "Wood_Oak_Planks", true, false));
        api.publish(new BlockPlaced(header(BOB), -1, 64, 0, "Rock_Stone", null, false, false));
        JsonArray rows = heat.query(WORLD, 0);
        Map<String, String> byChunk = new HashMap<>();
        for (var r : rows) {
            JsonArray a = r.getAsJsonArray();
            byChunk.put(a.get(0).getAsInt() + "," + a.get(1).getAsInt(), a.get(2).getAsInt() + "/" + a.get(3).getAsInt());
        }
        // Repeat spots, player placed breaks and creative are counted, as the system counted them.
        assertEquals(Map.of("1,-1", "1/1", "1,-2", "1/0", "-1,0", "0/1"), byChunk);
        long[] t = heat.totals(0);
        assertEquals(2, t[0]);
        assertEquals(2, t[1]);
    }

    @Test
    void heatUsesTheTimeTheBlockChanged() {
        long twoHoursAgo = System.currentTimeMillis() - 2 * 60 * 60 * 1000L;
        api.publish(new BlockBroken(header(twoHoursAgo, WORLD, ALICE), 0, 0, 0, "Rock_Stone", null, false, false));
        assertEquals(1, heat.totals(0)[0]);
        assertEquals(0, heat.totals(System.currentTimeMillis() - 60 * 60 * 1000L)[0]);
    }

    @Test
    void eventsWithoutAWorldAreSkipped() {
        api.publish(new BlockBroken(header(System.currentTimeMillis(), null, ALICE), 0, 0, 0, "Rock_Stone", null, false, false));
        assertEquals(0, heat.totals(0)[0]);
    }

    // ---- kills, as DeathSystem -------------------------------------------------------------------------

    @Test
    void playerKilledByPlayerKeepsThePlaceWhenBothAreShown() {
        standing(BOB, WORLD, 12.0, 70.0, -4.0);
        EntityDied e = death(player(ALICE, "Alice"), player(BOB, "Bob"), 10.7, 70.0, -3.2, false, true);
        api.publish(e);
        assertEquals(1, kills.size());
        KillLog.Kill k = kills.get(0);
        assertEquals(e.at(), k.at());
        assertEquals(WORLD, k.world());
        assertEquals(new KillLog.Party("Alice", "Player", true, false, ALICE), k.victim());
        assertEquals(new KillLog.Party("Bob", "Player", true, false, BOB), k.killer());
        assertEquals("Physical", k.cause());
        assertTrue(k.ranged());
        assertEquals(10, k.x());
        assertEquals(-4, k.z());
    }

    @Test
    void aWithheldVictimOrKillerHidesThePlace() {
        standing(BOB, WORLD, 12.0, 70.0, -4.0);
        withheld.add(ALICE);
        api.publish(death(player(ALICE, "Alice"), player(BOB, "Bob"), 10, 70, -3, false, false));
        withheld.clear();
        withheld.add(BOB);
        api.publish(death(player(ALICE, "Alice"), player(BOB, "Bob"), 10, 70, -3, false, false));
        assertEquals(2, kills.size());
        for (KillLog.Kill k : kills) {
            assertNull(k.x());
            assertNull(k.z());
            assertEquals("Bob", k.killer().name());
        }
    }

    @Test
    void aKillerWithoutASampleInThisWorldHidesThePlace() {
        api.publish(death(player(ALICE, "Alice"), player(BOB, "Bob"), 10, 70, -3, false, false));
        standing(BOB, "instance-1", 12.0, 70.0, -4.0);
        api.publish(death(player(ALICE, "Alice"), player(BOB, "Bob"), 10, 70, -3, false, false));
        assertEquals(2, kills.size());
        assertNull(kills.get(0).x());
        assertNull(kills.get(1).x());
    }

    @Test
    void playerKilledByTheWorldOrACreature() {
        api.publish(death(player(ALICE, "Alice"), null, -0.5, 64, 31.9, false, false));
        api.publish(death(player(ALICE, "Alice"), Actor.WORLD, 5, 64, 5, false, false));
        api.publish(death(player(ALICE, "Alice"), Actor.creature(WOLF, "Wolf_Black"), 8, 64, 8, false, false));
        assertEquals(3, kills.size());
        assertNull(kills.get(0).killer());
        assertEquals(-1, kills.get(0).x());
        assertEquals(31, kills.get(0).z());
        assertNull(kills.get(1).killer());
        assertEquals(new KillLog.Party("Wolf Black", "Wolf_Black", false, false, null), kills.get(2).killer());
        assertEquals(8, kills.get(2).x());
    }

    @Test
    void creatureDeathsCountOnlyWhenAPlayerKilled() {
        api.publish(death(Actor.creature(WOLF, "Wolf_Black"), null, 0, 64, 0, false, false));
        api.publish(death(Actor.creature(WOLF, "Wolf_Black"), Actor.creature(UUID.randomUUID(), "Bear_Grizzly"), 0, 64, 0,
                false, false));
        api.publish(death(Actor.creature(WOLF, "Wolf_Black"), Actor.WORLD, 0, 64, 0, false, false));
        assertTrue(kills.isEmpty());
        standing(ALICE, WORLD, 2, 64, 2);
        api.publish(death(Actor.creature(WOLF, "Wolf_Black"), player(ALICE, "Alice"), 0.2, 64, 0.9, false, false));
        assertEquals(1, kills.size());
        KillLog.Kill k = kills.get(0);
        assertEquals(new KillLog.Party("Wolf Black", "Wolf_Black", false, false, null), k.victim());
        assertEquals(ALICE, k.killer().uuid());
        assertEquals(0, k.x());
        assertEquals(0, k.z());
    }

    @Test
    void bossFlagComesFromTheRecordAndFromAtlasOwnList() {
        standing(ALICE, WORLD, 0, 64, 0);
        // Flagged by TrackerAPI only.
        api.publish(death(Actor.creature(WOLF, "Hedera"), player(ALICE, "Alice"), 0, 64, 0, true, false));
        // Named by Atlas's list only, as DeathSystem flagged it.
        api.publish(death(Actor.creature(WOLF, "Dragon_Void"), player(ALICE, "Alice"), 0, 64, 0, false, false));
        api.publish(death(Actor.creature(WOLF, "Trork_Grunt"), player(ALICE, "Alice"), 0, 64, 0, false, false));
        assertEquals(3, kills.size());
        assertTrue(kills.get(0).victim().boss());
        assertTrue(kills.get(1).victim().boss());
        assertFalse(kills.get(2).victim().boss());
    }

    @Test
    void aBossKillerIsFlaggedLikeDeathSystemDid() {
        api.publish(death(player(ALICE, "Alice"), Actor.creature(WOLF, "Goblin_Duke_Phase_3_Fast"), 0, 64, 0, false, false));
        assertTrue(kills.get(0).killer().boss());
        assertEquals("Goblin Duke Phase 3 Fast", kills.get(0).killer().name());
    }

    @Test
    void aSelfKillHasNoKiller() {
        api.publish(death(player(ALICE, "Alice"), player(ALICE, "Alice"), 0, 64, 0, false, false));
        assertEquals(1, kills.size());
        assertNull(kills.get(0).killer());
        assertEquals(0, kills.get(0).x());
    }

    @Test
    void anUnknownPlaceIsStoredAsUnknown() {
        api.publish(death(player(ALICE, "Alice"), null, Double.NaN, 64, 0, false, false));
        assertNull(kills.get(0).x());
        assertNull(kills.get(0).z());
    }

    @Test
    void aWorldVictimIsSkipped() {
        api.publish(death(Actor.WORLD, player(ALICE, "Alice"), 0, 64, 0, false, false));
        assertTrue(kills.isEmpty());
    }

    // ---- fakes -----------------------------------------------------------------------------------------

    private static final class FakeSubscription implements Subscription {
        final EventKind kind;
        final String owner;
        final Delivery delivery;
        final Consumer<TrackerEvent> listener;
        boolean active = true;

        FakeSubscription(EventKind kind, String owner, Delivery delivery, Consumer<TrackerEvent> listener) {
            this.kind = kind;
            this.owner = owner;
            this.delivery = delivery;
            this.listener = listener;
        }

        @Override
        public EventKind kind() {
            return kind;
        }

        @Override
        public Delivery delivery() {
            return delivery;
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public long delivered() {
            return 0;
        }

        @Override
        public long failures() {
            return 0;
        }

        @Override
        public void close() {
            active = false;
        }
    }

    private static final class FakeApi extends TrackerApi {
        final List<FakeSubscription> subs = new ArrayList<>();
        final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
        final Set<EventKind> off = new HashSet<>();

        void up() {
            enable(this);
        }

        void down() {
            subs.forEach(FakeSubscription::close);
            disable(this);
        }

        Set<EventKind> kinds() {
            Set<EventKind> out = new HashSet<>();
            for (FakeSubscription s : subs) {
                if (s.isActive()) {
                    out.add(s.kind());
                }
            }
            return out;
        }

        void publish(TrackerEvent e) {
            for (FakeSubscription s : List.copyOf(subs)) {
                if (s.isActive() && s.kind() == e.kind()) {
                    s.listener.accept(e);
                }
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <E extends TrackerEvent> Subscription subscribe(String owner, Class<E> type, Delivery delivery,
                                                                Consumer<? super E> listener) {
            EventKind kind = type == BlockBroken.class ? EventKind.BLOCK_BROKEN
                    : type == BlockPlaced.class ? EventKind.BLOCK_PLACED
                    : type == EntityDied.class ? EventKind.ENTITY_DIED : null;
            FakeSubscription s = new FakeSubscription(kind, owner, delivery, e -> ((Consumer<E>) listener).accept((E) e));
            subs.add(s);
            return s;
        }

        @Override
        public Subscription subscribeAll(String owner, Delivery delivery, Consumer<TrackerEvent> listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Subscription subscribeKinds(String owner, Set<EventKind> kinds, Delivery delivery,
                                           Consumer<TrackerEvent> listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Subscription> subscriptions() {
            return List.copyOf(subs);
        }

        @Override
        public boolean isTracked(EventKind kind) {
            return !off.contains(kind);
        }

        @Override
        public PlayerSnapshot snapshot(UUID player) {
            return snapshots.get(player);
        }

        @Override
        public List<PlayerSnapshot> snapshots() {
            return List.copyOf(snapshots.values());
        }

        @Override
        public Counters counters() {
            return null;
        }

        @Override
        public Journal journal() {
            return null;
        }

        @Override
        public Stats stats() {
            return null;
        }

        @Override
        public String version() {
            return "1.0.0";
        }
    }
}
