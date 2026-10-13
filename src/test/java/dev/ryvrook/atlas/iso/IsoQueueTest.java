package dev.ryvrook.atlas.iso;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsoQueueTest {
    private static int n;

    private static IsoQueue.Entry view(String address, int radius) {
        n++;
        return new IsoQueue.Entry("default/" + n + "_r" + radius, new IsoView("default", n, 0, radius, 0, 1), address);
    }

    private static IsoQueue.Offer offer(IsoQueue q, IsoQueue.Entry e) {
        return q.offer(e, () -> 0);
    }

    /** The caps of 1.1, two views per address and one large, which the older tests below were written for. */
    private static IsoQueue old() {
        return new IsoQueue(8, 2, () -> 2, () -> 1);
    }

    @Test
    void theDefaultAllowsFourViewsAndTwoWaitingLargeOnesPerAddress() {
        IsoQueue q = new IsoQueue(8, 2);
        assertEquals(4, q.perAddressCap());
        assertEquals(2, q.largePerAddressCap());
        IsoQueue.Entry[] e = new IsoQueue.Entry[4];
        for (int i = 0; i < 4; i++) {
            e[i] = view("me", 4);
            IsoQueue.Offer o = offer(q, e[i]);
            assertEquals(IsoQueue.Outcome.QUEUED, o.outcome());
            assertTrue(o.cancelled().isEmpty());
        }
        assertEquals(4, q.perAddress("me"));
        IsoQueue.Offer fifth = offer(q, view("me", 4));
        assertEquals(List.of(e[0]), fifth.cancelled());
        assertEquals(4, q.perAddress("me"));
    }

    @Test
    void twoLargeViewsWaitPerAddressAndAThirdReplacesTheOldest() {
        IsoQueue q = new IsoQueue(8, 2);
        IsoQueue.Entry a = view("me", 12);
        IsoQueue.Entry b = view("me", 12);
        assertTrue(offer(q, a).cancelled().isEmpty());
        assertTrue(offer(q, b).cancelled().isEmpty());
        assertEquals(2, q.largeCount());
        IsoQueue.Offer o = offer(q, view("me", 16));
        assertEquals(List.of(a), o.cancelled());
        assertEquals(2, q.largeCount());
    }

    @Test
    void theCapsAreReadOnEveryOfferAndHeldToTheQueue() {
        int[] cap = {16};
        IsoQueue q = new IsoQueue(8, 2, () -> cap[0], () -> 4);
        assertEquals(8, q.perAddressCap());
        for (int i = 0; i < 8; i++) {
            assertEquals(IsoQueue.Outcome.QUEUED, offer(q, view("one", 4)).outcome());
        }
        // A whole queue from one address still turns over its own oldest view rather than answering busy.
        IsoQueue.Offer o = offer(q, view("one", 4));
        assertEquals(IsoQueue.Outcome.QUEUED, o.outcome());
        assertEquals(1, o.cancelled().size());
        cap[0] = 1;
        assertEquals(1, q.perAddressCap());
        assertEquals(1, q.largePerAddressCap());
        IsoQueue.Offer later = offer(q, view("two", 4));
        assertEquals(IsoQueue.Outcome.BUSY, later.outcome());
    }

    @Test
    void aThirdViewReplacesTheOldestWaitingViewOfTheSameAddress() {
        IsoQueue q = old();
        IsoQueue.Entry a = view("1.2.3.4", 4);
        IsoQueue.Entry b = view("1.2.3.4", 4);
        IsoQueue.Entry c = view("1.2.3.4", 4);
        assertEquals(IsoQueue.Outcome.QUEUED, offer(q, a).outcome());
        assertEquals(IsoQueue.Outcome.QUEUED, offer(q, b).outcome());
        IsoQueue.Offer o = offer(q, c);
        assertEquals(IsoQueue.Outcome.QUEUED, o.outcome());
        assertEquals(List.of(a), o.cancelled());
        assertTrue(a.cancelled());
        assertNull(q.get(a.key));
        assertSame(c, q.get(c.key));
        assertEquals(2, q.perAddress("1.2.3.4"));
        assertEquals(1, q.cancelledTotal());
        assertFalse(q.start(a, 10));
    }

    @Test
    void theRunningViewIsNeverReplaced() {
        IsoQueue q = old();
        IsoQueue.Entry a = view("x", 4);
        IsoQueue.Entry b = view("x", 4);
        offer(q, a);
        offer(q, b);
        assertTrue(q.start(a, 10));
        IsoQueue.Offer o = offer(q, view("x", 4));
        assertEquals(List.of(b), o.cancelled());
        assertSame(a, q.get(a.key));
        assertEquals(2, q.perAddress("x"));
    }

    @Test
    void twelveViewsInARowFromOneAddressNeverAnswerBusy() {
        IsoQueue q = old();
        int[] radii = {4, 4, 4, 4, 8, 8, 8, 8, 12, 12, 16, 16};
        IsoQueue.Entry first = null;
        for (int r : radii) {
            IsoQueue.Entry e = view("me", r);
            assertEquals(IsoQueue.Outcome.QUEUED, offer(q, e).outcome());
            if (first == null) {
                first = e;
                q.start(e, 10);
            }
            assertTrue(q.perAddress("me") <= 2);
        }
        assertSame(first, q.get(first.key));
        assertEquals(10, q.cancelledTotal());
    }

    @Test
    void busyOnlyOnceEveryAddressIsAtItsCap() {
        IsoQueue q = old();
        for (int i = 0; i < 4; i++) {
            offer(q, view("a" + i, 4));
            offer(q, view("a" + i, 4));
        }
        assertEquals(8, q.browserCount());
        IsoQueue.Offer stranger = offer(q, view("new", 4));
        assertEquals(IsoQueue.Outcome.BUSY, stranger.outcome());
        assertTrue(stranger.cancelled().isEmpty());
        assertEquals(IsoQueue.Outcome.QUEUED, offer(q, view("a0", 4)).outcome());
        assertEquals(8, q.browserCount());
    }

    @Test
    void aLimitedOfferChangesNothing() {
        IsoQueue q = old();
        IsoQueue.Entry a = view("x", 4);
        offer(q, a);
        offer(q, view("x", 4));
        IsoQueue.Offer o = q.offer(view("x", 4), () -> 7);
        assertEquals(IsoQueue.Outcome.LIMITED, o.outcome());
        assertEquals(7, o.waitSeconds());
        assertFalse(a.cancelled());
        assertSame(a, q.get(a.key));
        assertEquals(0, q.cancelledTotal());
    }

    @Test
    void oneWaitingLargeViewPerAddress() {
        IsoQueue q = old();
        IsoQueue.Entry small = view("x", 4);
        IsoQueue.Entry big = view("x", 12);
        offer(q, small);
        offer(q, big);
        IsoQueue.Offer o = offer(q, view("x", 16));
        assertEquals(List.of(big), o.cancelled());
        assertSame(small, q.get(small.key));
        assertEquals(1, q.largeCount());
    }

    @Test
    void aLargeViewPastTheLargeLimitWaitsAndIsPromoted() {
        IsoQueue q = old();
        IsoQueue.Entry a = view("a", 12);
        IsoQueue.Entry b = view("b", 12);
        IsoQueue.Entry c = view("c", 16);
        offer(q, a);
        offer(q, b);
        IsoQueue.Offer o = offer(q, c);
        assertEquals(IsoQueue.Outcome.QUEUED, o.outcome());
        assertTrue(c.deferred());
        assertEquals(1, q.deferredCount());
        assertEquals(3, q.ahead(c).size());
        q.start(a, 10);
        assertEquals(List.of(c), q.finish(a));
        assertFalse(c.deferred());
        assertEquals(0, q.deferredCount());
    }

    @Test
    void replacingAnAdmittedLargeViewPromotesTheNextOne() {
        IsoQueue q = old();
        IsoQueue.Entry a = view("a", 12);
        IsoQueue.Entry b = view("b", 12);
        IsoQueue.Entry c = view("c", 12);
        offer(q, a);
        offer(q, b);
        offer(q, c);
        assertTrue(c.deferred());
        IsoQueue.Offer o = offer(q, view("a", 4));
        assertTrue(o.cancelled().isEmpty());
        o = offer(q, view("a", 4));
        assertEquals(List.of(a), o.cancelled());
        assertEquals(List.of(c), o.promoted());
        assertFalse(c.deferred());
    }

    @Test
    void browserViewsComeBeforeCommandsAndACancelledViewCanBeAskedAgain() {
        IsoQueue q = old();
        IsoQueue.Entry snapshot = view(null, 4);
        q.addDirect(snapshot);
        IsoQueue.Entry a = view("x", 4);
        offer(q, a);
        assertEquals(List.of(a, snapshot), q.all());
        assertEquals(1, q.ahead(a).size());
        assertEquals(1, q.aheadOfNew().size());
        offer(q, view("x", 4));
        offer(q, view("x", 4));
        assertNull(q.get(a.key));
        IsoQueue.Entry again = new IsoQueue.Entry(a.key, a.view, "x");
        assertEquals(IsoQueue.Outcome.QUEUED, offer(q, again).outcome());
        assertSame(again, q.get(a.key));
        assertEquals(2, q.perAddress("x"));
    }
}
