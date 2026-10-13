package dev.ryvrook.atlas.auth;

import dev.ryvrook.atlas.api.SessionApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionApiTest {
    private static final UUID ANN = UUID.fromString("6f1c2a4e-0b7d-4c55-9a51-1c9f0d3e8b21");
    private final ExecutorService scheduler = Executors.newSingleThreadExecutor(r -> new Thread(r, "Atlas-scheduler"));
    private SessionService service;

    private SessionService start(Path dir, Set<UUID> hidden) {
        LinkService links = new LinkService(dir);
        service = new SessionService(links, () -> hidden);
        service.start(scheduler);
        return service;
    }

    @AfterEach
    void stop() {
        if (service != null) {
            service.stop();
        }
        scheduler.shutdownNow();
    }

    /** One event as the listener saw it, with the thread it ran on. */
    private record Seen(SessionApi.SessionEvent event, String thread) {
    }

    private static Seen next(LinkedBlockingQueue<Seen> q) throws InterruptedException {
        Seen s = q.poll(5, TimeUnit.SECONDS);
        assertNotNull(s, "no event arrived");
        return s;
    }

    @Test
    void nullBeforeStartAndAfterStop(@TempDir Path dir) {
        SessionService s = new SessionService(new LinkService(dir), Set::of);
        assertNull(SessionApi.get());
        s.start(scheduler);
        assertSame(s, SessionApi.get());
        assertEquals(1, SessionApi.get().version());
        assertEquals(1, SessionApi.VERSION);
        s.stop();
        assertNull(SessionApi.get());
        assertFalse(s.running());
    }

    @Test
    void issueRedeemAndResolve(@TempDir Path dir) {
        SessionApi api = start(dir, Set.of());
        String code = api.issueCode(ANN, "Ann");
        assertEquals(6, code.length());
        SessionApi.Linked linked = api.redeem(code.toLowerCase(java.util.Locale.ROOT));
        assertNotNull(linked);
        assertEquals(ANN, linked.session().uuid());
        assertEquals("Ann", linked.session().name());
        assertNull(api.redeem(code), "codes are single use");
        String hash = LinkService.hash(linked.token());
        SessionApi.Session s = api.resolve(hash);
        assertNotNull(s);
        assertEquals(ANN, s.uuid());
        assertEquals(linked.session().createdAt(), s.createdAt());
        assertNull(api.resolve(hash.toUpperCase(java.util.Locale.ROOT)));
        assertNull(api.resolve("not a hash"));
        assertNull(api.resolve(null));
        assertNull(api.redeem("ZZZZZZ"));
        assertThrows(IllegalArgumentException.class, () -> api.issueCode(null, "Nobody"));
    }

    @Test
    void codesIssuedByAtlasRedeemThroughTheApiAndBack(@TempDir Path dir) {
        LinkService links = new LinkService(dir);
        service = new SessionService(links, Set::of);
        service.start(scheduler);
        SessionApi.Linked viaApi = service.redeem(links.issueCode(ANN, "Ann"));
        assertNotNull(viaApi);
        assertNotNull(links.session(viaApi.token()), "a session redeemed through the API signs the browser in on Atlas");
        LinkService.Linked viaAtlas = links.redeem(service.issueCode(ANN, "Ann"));
        assertNotNull(viaAtlas);
        assertNotNull(service.resolve(viaAtlas.session().hash()));
    }

    @Test
    void revokeEndsTheSessionOnce(@TempDir Path dir) {
        LinkService links = new LinkService(dir);
        service = new SessionService(links, Set::of);
        service.start(scheduler);
        SessionApi.Linked linked = service.redeem(service.issueCode(ANN, "Ann"));
        String hash = LinkService.hash(linked.token());
        assertTrue(service.revoke(hash));
        assertFalse(service.revoke(hash));
        assertNull(service.resolve(hash));
        assertNull(links.session(linked.token()));
        assertFalse(service.revoke(null));
        assertFalse(service.revoke("abc"));
    }

    @Test
    void adoptedSessionsSignInOnAtlas(@TempDir Path dir) {
        LinkService links = new LinkService(dir);
        service = new SessionService(links, Set::of);
        service.start(scheduler);
        String token = "chronicle-token-that-only-the-browser-holds";
        String hash = LinkService.hash(token);
        long now = System.currentTimeMillis();
        service.adopt(new SessionApi.Session(ANN, "Ann", now - 60_000, now - 1_000), hash);
        LinkService.Session s = links.session(token);
        assertNotNull(s);
        assertEquals(ANN, s.uuid());
        assertEquals(now - 60_000, s.createdAt());
        assertEquals(1, links.sessionCount());
        // A second adopt of the same hash keeps one session with the later last seen time.
        service.adopt(new SessionApi.Session(ANN, "Ann", now - 60_000, now), hash);
        assertEquals(1, links.sessionCount());
        assertEquals(now, service.resolve(hash).lastSeen());
        // Expired sessions, malformed hashes and missing sessions are ignored.
        service.adopt(new SessionApi.Session(ANN, "Ann", 1, 2), LinkService.hash("old"));
        service.adopt(new SessionApi.Session(ANN, "Ann", now, now), "ABC");
        service.adopt(new SessionApi.Session(null, "Ann", now, now), LinkService.hash("nobody"));
        service.adopt(null, LinkService.hash("null"));
        assertEquals(1, links.sessionCount());
    }

    @Test
    void adoptedSessionsAreSaved(@TempDir Path dir) throws Exception {
        LinkService links = new LinkService(dir);
        service = new SessionService(links, Set::of);
        service.start(scheduler);
        String token = "shared-cookie-value";
        long now = System.currentTimeMillis();
        service.adopt(new SessionApi.Session(ANN, "Ann", now, now), LinkService.hash(token));
        links.save();
        LinkService reread = new LinkService(dir);
        reread.load();
        assertNotNull(reread.session(token));
    }

    @Test
    void onChangeReportsEveryChangeInOrderOnTheScheduler(@TempDir Path dir) throws Exception {
        LinkService links = new LinkService(dir);
        service = new SessionService(links, Set::of);
        service.start(scheduler);
        LinkedBlockingQueue<Seen> seen = new LinkedBlockingQueue<>();
        service.onChange(e -> {
            throw new IllegalStateException("a broken listener must not stop the others");
        });
        Runnable stop = service.onChange(e -> seen.add(new Seen(e, Thread.currentThread().getName())));

        SessionApi.Linked linked = service.redeem(service.issueCode(ANN, "Ann"));
        String redeemed = LinkService.hash(linked.token());
        String adopted = LinkService.hash("adopted");
        long now = System.currentTimeMillis();
        service.adopt(new SessionApi.Session(ANN, "Ann", now, now), adopted);
        service.adopt(new SessionApi.Session(ANN, "Ann", now, now), adopted);
        service.revoke(redeemed);
        links.redeem(links.issueCode(ANN, "Ann"));
        assertEquals(2, links.revokePlayer(ANN));

        List<SessionApi.SessionEvent.Kind> kinds = List.of(SessionApi.SessionEvent.Kind.REDEEMED,
                SessionApi.SessionEvent.Kind.ADOPTED, SessionApi.SessionEvent.Kind.REVOKED,
                SessionApi.SessionEvent.Kind.REDEEMED, SessionApi.SessionEvent.Kind.REVOKED,
                SessionApi.SessionEvent.Kind.REVOKED);
        Seen first = next(seen);
        assertEquals(SessionApi.SessionEvent.Kind.REDEEMED, first.event().kind());
        assertEquals(redeemed, first.event().tokenHash());
        assertEquals(ANN, first.event().uuid());
        assertEquals("Atlas-scheduler", first.thread());
        Seen second = next(seen);
        assertEquals(adopted, second.event().tokenHash());
        for (int i = 2; i < kinds.size(); i++) {
            Seen s = next(seen);
            assertEquals(kinds.get(i), s.event().kind(), "event " + i);
            assertEquals("Atlas-scheduler", s.thread());
        }
        assertNull(seen.poll(200, TimeUnit.MILLISECONDS), "the second adopt of one hash fires nothing");

        stop.run();
        service.redeem(service.issueCode(ANN, "Ann"));
        assertNull(seen.poll(300, TimeUnit.MILLISECONDS), "an unsubscribed listener hears nothing");
    }

    @Test
    void stopDropsListeners(@TempDir Path dir) throws Exception {
        LinkService links = new LinkService(dir);
        service = new SessionService(links, Set::of);
        service.start(scheduler);
        LinkedBlockingQueue<Seen> seen = new LinkedBlockingQueue<>();
        service.onChange(e -> seen.add(new Seen(e, Thread.currentThread().getName())));
        service.stop();
        links.redeem(links.issueCode(ANN, "Ann"));
        assertNull(seen.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void hiddenIsACopyOfTheSharedList(@TempDir Path dir) {
        UUID bob = UUID.randomUUID();
        java.util.Set<UUID> live = new java.util.HashSet<>(Set.of(bob));
        SessionApi api = start(dir, live);
        Set<UUID> copy = api.hidden();
        assertEquals(Set.of(bob), copy);
        live.add(ANN);
        assertEquals(Set.of(bob), copy);
        assertEquals(Set.of(bob, ANN), api.hidden());
        assertThrows(UnsupportedOperationException.class, () -> copy.add(ANN));
    }
}
