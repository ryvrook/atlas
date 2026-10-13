package dev.ryvrook.atlas.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkServiceTest {
    @Test
    void ipv4KeysStayAsTheyAre() {
        assertEquals("203.0.113.7", LinkService.rateKey("203.0.113.7"));
        assertEquals("203.0.113.7", LinkService.rateKey(" 203.0.113.7 "));
    }

    @Test
    void ipv6SpellingsShareOneKey() {
        String key = LinkService.rateKey("2001:db8:0:0:1:2:3:4");
        assertEquals("2001:db8:0:0::/64", key);
        assertEquals(key, LinkService.rateKey("2001:db8::1:2:3:4"));
        assertEquals(key, LinkService.rateKey("2001:0DB8:0000:0000:ffff:1:2:3"));
        assertEquals(key, LinkService.rateKey("[2001:db8::9]"));
        assertEquals(key, LinkService.rateKey("2001:db8::9%eth0"));
        assertTrue(!key.equals(LinkService.rateKey("2001:db8:0:1::9")));
    }

    @Test
    void mappedIpv4BecomesIpv4() {
        assertEquals("192.0.2.1", LinkService.rateKey("::ffff:192.0.2.1"));
        assertEquals("192.0.2.1", LinkService.rateKey("::ffff:c000:201"));
        assertEquals("192.0.2.1", LinkService.rateKey("::192.0.2.1"));
    }

    @Test
    void loopbackIpv6IsNotIpv4() {
        assertEquals("0:0:0:0::/64", LinkService.rateKey("::1"));
    }

    @Test
    void nonLiteralsAreUsedAsIs() {
        assertEquals("?", LinkService.rateKey(null));
        assertEquals("example.com", LinkService.rateKey("example.com"));
        assertEquals("not an address", LinkService.rateKey("not an address"));
    }

    @Test
    void revokePlayerRemovesEverySessionOfThatPlayer(@TempDir Path dir) {
        LinkService links = new LinkService(dir);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        LinkService.Linked a1 = links.redeem(links.issueCode(a, "Ann"));
        LinkService.Linked a2 = links.redeem(links.issueCode(a, "Ann"));
        LinkService.Linked b1 = links.redeem(links.issueCode(b, "Bob"));
        assertNotNull(a1);
        assertNotNull(a2);
        assertNotNull(b1);
        String pending = links.issueCode(a, "Ann");
        assertEquals(2, links.revokePlayer(a));
        assertNull(links.session(a1.token()));
        assertNull(links.session(a2.token()));
        assertNotNull(links.session(b1.token()));
        assertNull(links.redeem(pending));
        assertEquals(0, links.revokePlayer(a));
        assertEquals(0, links.revokePlayer(null));
    }

    @Test
    void playerByNameFindsLinkedPlayersIgnoringCase(@TempDir Path dir) {
        LinkService links = new LinkService(dir);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        links.redeem(links.issueCode(a, "Ann"));
        links.redeem(links.issueCode(a, "Ann"));
        links.redeem(links.issueCode(b, "Twin"));
        links.redeem(links.issueCode(c, "twin"));
        assertEquals(a, links.playerByName("ann"));
        assertEquals(a, links.playerByName(" ANN "));
        // Two players under one name is no answer at all.
        assertNull(links.playerByName("Twin"));
        assertNull(links.playerByName("Nobody"));
        assertNull(links.playerByName(null));
    }

    @Test
    void attemptsAreLimitedPerAddressKey(@TempDir Path dir) {
        LinkService links = new LinkService(dir);
        for (int i = 0; i < 10; i++) {
            assertTrue(!links.rateLimited("2001:db8::" + (i + 1)));
        }
        assertTrue(links.rateLimited("2001:db8:0:0:ffff::1"));
        assertTrue(!links.rateLimited("198.51.100.1"));
    }
}
