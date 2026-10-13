package dev.ryvrook.atlas.http;

import dev.ryvrook.atlas.auth.Viewer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseHubTest {
    private static SseHub.Frame data(long n) {
        return new SseHub.Frame(n, SseHub.frame("players", "{}"), false);
    }

    private static SseHub.Frame control(long n) {
        return new SseHub.Frame(n, SseHub.frame("tiles", "{}"), true);
    }

    @Test
    void eventIdsRoundTrip() {
        SseHub hub = new SseHub();
        SseHub.Client c = new SseHub.Client(Viewer.PUBLIC, null, "P", "a", 0xBEEFL, 0);
        String id = hub.eventId(c, 42);
        assertArrayEquals(new long[]{0xBEEFL, 42}, hub.parseEventId(id));
        assertNull(hub.parseEventId("other-beef-42"));
        assertNull(hub.parseEventId(new SseHub().eventId(c, 42)));
        assertNull(hub.parseEventId("garbage"));
        assertNull(hub.parseEventId(null));
    }

    @Test
    void fullQueueDropsDataFramesBeforeControlFrames() throws Exception {
        SseHub.Client c = new SseHub.Client(Viewer.PUBLIC, null, "P", "a", 1, 0);
        c.offer(control(1));
        for (int i = 2; i <= SseHub.QUEUE; i++) {
            c.offer(data(i));
        }
        c.offer(control(1000));
        List<SseHub.Frame> out = c.take(0);
        assertEquals(SseHub.QUEUE, out.size());
        assertEquals(1, out.get(0).n());
        assertTrue(out.get(0).control());
        assertEquals(3, out.get(1).n());
        assertEquals(1000, out.get(out.size() - 1).n());
    }

    @Test
    void queueOfOnlyControlFramesKeepsThemAndDropsNewData() throws Exception {
        SseHub.Client c = new SseHub.Client(Viewer.PUBLIC, null, "P", "a", 1, 0);
        for (int i = 1; i <= SseHub.QUEUE + 10; i++) {
            c.offer(control(i));
        }
        c.offer(data(500));
        List<SseHub.Frame> out = c.take(0);
        assertEquals(SseHub.QUEUE + 10, out.size());
        assertTrue(out.stream().allMatch(SseHub.Frame::control));
    }

    @Test
    void historyCoversTheLastFrames() {
        SseHub.Client c = new SseHub.Client(Viewer.PUBLIC, null, "P", "a", 1, 0);
        for (int i = 1; i <= SseHub.REPLAY + 50; i++) {
            c.offer(data(i));
        }
        synchronized (c) {
            assertEquals(SseHub.REPLAY, c.history.size());
            assertEquals(50, c.coveredFrom);
            assertEquals(51, c.history.peekFirst().n());
        }
    }

    @SuppressWarnings("unchecked")
    private static void register(SseHub hub, SseHub.Client c) throws Exception {
        java.lang.reflect.Field f = SseHub.class.getDeclaredField("clients");
        f.setAccessible(true);
        ((java.util.Set<SseHub.Client>) f.get(hub)).add(c);
    }

    @Test
    void feedLinesAboutAHiddenWorldStayAway() throws Exception {
        SseHub hub = new SseHub();
        hub.setWorldGate((v, w) -> !"portal-1".equals(w));
        SseHub.Client c = new SseHub.Client(Viewer.PUBLIC, "default", "P", "a", 1, 0);
        register(hub, c);
        hub.broadcastAll("feed", "{\"at\":1,\"type\":\"death\",\"text\":\"t\",\"source\":\"atlas\",\"world\":\"portal-1\"}", x -> true);
        hub.broadcastAll("feed", "{\"at\":2,\"type\":\"death\",\"text\":\"t\",\"source\":\"atlas\",\"world\":\"default\"}", x -> true);
        hub.broadcastAll("feed", "{\"at\":3,\"type\":\"claim\",\"text\":\"t\",\"source\":\"dominion\",\"world\":null}", x -> true);
        List<SseHub.Frame> out = c.take(0);
        assertEquals(2, out.size());
        assertTrue(new String(out.get(0).bytes(), java.nio.charset.StandardCharsets.UTF_8).contains("\"at\":2"));
        hub.closeAll();
        assertTrue(c.closed);
    }

    @Test
    void endedClientsAreCappedOldestFirst() throws Exception {
        SseHub hub = new SseHub();
        java.util.List<SseHub.Client> all = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < SseHub.MAX_GHOSTS + 20; i++) {
            SseHub.Client c = new SseHub.Client(Viewer.PUBLIC, null, "P", "a", i + 1, 0);
            synchronized (c) {
                c.ghost = true;
                c.ghostSince = now - 1_000 + i;
            }
            register(hub, c);
            all.add(c);
        }
        assertTrue(all.get(0).ended());
        hub.sweep();
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i < 20, all.get(i).closed, "client " + i);
        }
    }

    @Test
    void replayBudgetDropsTheOldestEndedClient() throws Exception {
        SseHub hub = new SseHub();
        byte[] big = new byte[SseHub.REPLAY_BYTES - 1024];
        int n = (int) (SseHub.REPLAY_BUDGET_BYTES / big.length) + 3;
        long now = System.currentTimeMillis();
        java.util.List<SseHub.Client> all = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            SseHub.Client c = new SseHub.Client(Viewer.PUBLIC, null, "P", "a", i + 1, 0);
            c.offer(new SseHub.Frame(i + 1, big, false));
            synchronized (c) {
                c.ghost = true;
                c.ghostSince = now - 1_000 + i;
            }
            register(hub, c);
            all.add(c);
        }
        hub.sweep();
        long kept = all.stream().filter(c -> !c.closed).count();
        assertTrue(kept * big.length <= SseHub.REPLAY_BUDGET_BYTES, "kept " + kept);
        assertTrue(all.get(0).closed);
        assertTrue(!all.get(n - 1).closed);
    }

    @Test
    void framesAlreadySentAreSkipped() throws Exception {
        SseHub.Client c = new SseHub.Client(Viewer.PUBLIC, null, "P", "a", 1, 10);
        c.offer(data(5));
        c.offer(data(11));
        List<SseHub.Frame> out = c.take(0);
        assertEquals(1, out.size());
        assertEquals(11, out.get(0).n());
    }
}
