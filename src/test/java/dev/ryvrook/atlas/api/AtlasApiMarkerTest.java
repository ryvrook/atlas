package dev.ryvrook.atlas.api;

import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.config.AtlasConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The marker events other plugins get through AtlasApi.onMarker. */
class AtlasApiMarkerTest {
    private static final UUID ANN = UUID.fromString("6f1c2a4e-0b7d-4c55-9a51-1c9f0d3e8b21");
    @TempDir
    Path dir;
    private final ExecutorService scheduler = Executors.newSingleThreadExecutor(r -> new Thread(r, "Atlas-scheduler"));
    private Atlas atlas;

    private AtlasApi start() {
        atlas = new Atlas(new AtlasConfig(), dir.resolve("data"));
        atlas.useExecutor(scheduler);
        AtlasApi api = AtlasApi.get();
        assertNotNull(api);
        return api;
    }

    @AfterEach
    void stop() {
        if (atlas != null) {
            atlas.shutdown();
            atlas.tiles().shutdown();
        }
        scheduler.shutdownNow();
    }

    /** One event as the listener saw it, with the thread it ran on. */
    private record Seen(MarkerEvent event, String thread) {
    }

    private static Seen next(LinkedBlockingQueue<Seen> q) throws InterruptedException {
        Seen s = q.poll(5, TimeUnit.SECONDS);
        assertNotNull(s, "no event arrived");
        return s;
    }

    @Test
    void listenersHearEveryEventInOrderOnTheSchedulerUntilRemoved() throws Exception {
        AtlasApi api = start();
        assertTrue(api.version() >= 3);
        LinkedBlockingQueue<Seen> seen = new LinkedBlockingQueue<>();
        List<MarkerEvent> other = new CopyOnWriteArrayList<>();
        api.onMarker(e -> {
            throw new IllegalStateException("a broken listener must not stop the others");
        });
        Runnable stop = api.onMarker(e -> seen.add(new Seen(e, Thread.currentThread().getName())));
        api.onMarker(other::add);

        long at = System.currentTimeMillis();
        MarkerEvent added = new MarkerEvent(MarkerEvent.Kind.ADDED, ANN, "default", "shared", "m1", 12, -40, at);
        MarkerEvent staff = new MarkerEvent(MarkerEvent.Kind.ADDED, null, "default", "atlas:admin", "market", 1, 2, at);
        MarkerEvent removed = new MarkerEvent(MarkerEvent.Kind.REMOVED, ANN, "default", "shared", "m1", 12, -40, at + 1);
        atlas.markers().fire(added);
        atlas.markers().fire(staff);
        atlas.markers().fire(removed);

        Seen first = next(seen);
        assertEquals(added, first.event());
        assertEquals(ANN, first.event().player());
        assertEquals("Atlas-scheduler", first.thread());
        Seen second = next(seen);
        assertEquals(staff, second.event());
        assertNull(second.event().player(), "a staff marker has no author");
        Seen third = next(seen);
        assertEquals(MarkerEvent.Kind.REMOVED, third.event().kind());
        assertEquals("m1", third.event().markerId());
        assertEquals("Atlas-scheduler", third.thread());

        stop.run();
        atlas.markers().fire(added);
        assertNull(seen.poll(300, TimeUnit.MILLISECONDS), "a removed listener hears nothing");
        // The listener that stayed still gets the fourth event, so the queue kept running past the failures.
        for (int i = 0; i < 50 && other.size() < 4; i++) {
            Thread.sleep(20);
        }
        assertEquals(4, other.size());
        assertThrows(NullPointerException.class, () -> api.onMarker(null));
    }

    @Test
    void shutdownDropsListeners() throws Exception {
        AtlasApi api = start();
        LinkedBlockingQueue<Seen> seen = new LinkedBlockingQueue<>();
        api.onMarker(e -> seen.add(new Seen(e, Thread.currentThread().getName())));
        atlas.shutdown();
        atlas.markers().fire(new MarkerEvent(MarkerEvent.Kind.ADDED, ANN, "default", "shared", "m1", 0, 0, 1L));
        assertNull(seen.poll(300, TimeUnit.MILLISECONDS));
    }
}
