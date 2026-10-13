package dev.ryvrook.atlas.http;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedProxiesTest {
    @Test
    void emptyTrustsNobody() {
        assertSame(TrustedProxies.NONE, TrustedProxies.parse(List.of()));
        assertSame(TrustedProxies.NONE, TrustedProxies.parse(null));
        assertEquals("10.0.0.1", TrustedProxies.NONE.client("10.0.0.1", List.of("1.2.3.4")));
    }

    @Test
    void parsesAddressesAndRanges() {
        TrustedProxies p = TrustedProxies.parse(List.of("127.0.0.1", "10.0.0.0/8", "fd00::/8", " ::1 ", "proxy.local",
                "10.0.0.0/33", "1.2.3.4/x", ""));
        assertTrue(p.trusts("127.0.0.1"));
        assertTrue(p.trusts("10.200.3.4"));
        assertFalse(p.trusts("11.0.0.1"));
        assertTrue(p.trusts("fd12::5"));
        assertTrue(p.trusts("0:0:0:0:0:0:0:1"));
        assertFalse(p.trusts("fe80::1"));
        assertEquals(List.of("proxy.local", "10.0.0.0/33", "1.2.3.4/x"), p.rejected());
    }

    @Test
    void oddPrefixLengths() {
        TrustedProxies p = TrustedProxies.parse(List.of("192.168.4.0/22"));
        assertTrue(p.trusts("192.168.7.255"));
        assertFalse(p.trusts("192.168.8.0"));
        TrustedProxies all = TrustedProxies.parse(List.of("0.0.0.0/0"));
        assertTrue(all.trusts("8.8.8.8"));
        assertFalse(all.trusts("::2"));
    }

    @Test
    void clientWalksForwardedForFromTheRight() {
        TrustedProxies p = TrustedProxies.parse(List.of("10.0.0.0/8"));
        assertEquals("203.0.113.9", p.client("10.0.0.1", List.of("198.51.100.1, 203.0.113.9, 10.0.0.2")));
        assertEquals("203.0.113.9", p.client("10.0.0.1", List.of("198.51.100.1", "203.0.113.9")));
        assertEquals("10.0.0.3", p.client("10.0.0.1", List.of("10.0.0.3, 10.0.0.2")));
        assertEquals("10.0.0.1", p.client("10.0.0.1", List.of("garbage")));
        assertEquals("10.0.0.2", p.client("10.0.0.1", List.of("1.2.3.4, garbage, 10.0.0.2")));
        assertEquals("8.8.8.8", p.client("8.8.8.8", List.of("1.2.3.4")));
        assertEquals("10.0.0.1", p.client("10.0.0.1", null));
    }

    @Test
    void stripsPortsBracketsAndQuotes() {
        assertEquals("1.2.3.4", TrustedProxies.strip(" 1.2.3.4:5678 "));
        assertEquals("2001:db8::1", TrustedProxies.strip("[2001:db8::1]:443"));
        assertEquals("2001:db8::1", TrustedProxies.strip("\"[2001:db8::1]\""));
        assertEquals("2001:db8::1", TrustedProxies.strip("2001:db8::1"));
    }

    @Test
    void literalNeverResolvesNames() {
        assertNull(TrustedProxies.literal("localhost"));
        assertNull(TrustedProxies.literal("example.com"));
        assertNull(TrustedProxies.literal("1.2.3"));
        assertNull(TrustedProxies.literal("1.2.3.256"));
        assertNull(TrustedProxies.literal(""));
        assertNull(TrustedProxies.literal(null));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, TrustedProxies.literal("1.2.3.4"));
        assertEquals(16, TrustedProxies.literal("fe80::1%en0").length);
        assertArrayEquals(new byte[]{(byte) 192, 0, 2, 1}, TrustedProxies.literal("::ffff:192.0.2.1"));
    }
}
