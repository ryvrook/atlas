package dev.ryvrook.atlas.http;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServerTest {
    @Test
    void safeRelativeAcceptsPlainFiles() {
        assertEquals("app.js", ApiServer.safeRelative("app.js"));
        assertEquals("vendor/leaflet.css", ApiServer.safeRelative("vendor/leaflet.css"));
        assertEquals("icons/a_b-c@2x+1.png", ApiServer.safeRelative("icons/a_b-c@2x+1.png"));
    }

    @Test
    void safeRelativeRejectsEverythingElse() {
        String[] bad = {null, "", "../manifest.json", "vendor/../../x.js", "/etc/passwd", "a\\\\b.js", "a\u0000.js",
                ".hidden.js", "vendor/.git/config", "a//b.js", "./app.js", "noext", "dir/", "trail.", "a b.js",
                "a%2e.js", "x".repeat(201) + ".js", "vendor"};
        for (String s : bad) {
            assertNull(ApiServer.safeRelative(s), String.valueOf(s));
        }
    }

    @Test
    void stripBase() {
        assertEquals("/api/x", ApiServer.stripBase("/api/x", ""));
        assertEquals("", ApiServer.stripBase("/map", "/map"));
        assertEquals("/api/x", ApiServer.stripBase("/map/api/x", "/map"));
        assertEquals("/mapx/api", ApiServer.stripBase("/mapx/api", "/map"));
    }

    @Test
    void etagMatching() {
        assertTrue(ApiServer.etagMatches("\"abc\"", "\"abc\""));
        assertTrue(ApiServer.etagMatches("W/\"abc\"", "\"abc\""));
        assertTrue(ApiServer.etagMatches("\"x\", \"abc\"", "\"abc\""));
        assertTrue(ApiServer.etagMatches("*", "\"abc\""));
        assertFalse(ApiServer.etagMatches("\"abd\"", "\"abc\""));
    }

    @Test
    void gzipAcceptance() {
        assertTrue(ApiServer.acceptsGzip("gzip, deflate, br"));
        assertTrue(ApiServer.acceptsGzip("br;q=1.0, gzip;q=0.5"));
        assertTrue(ApiServer.acceptsGzip("*"));
        assertFalse(ApiServer.acceptsGzip("gzip;q=0"));
        assertFalse(ApiServer.acceptsGzip("br"));
        assertFalse(ApiServer.acceptsGzip(null));
        assertFalse(ApiServer.acceptsGzip("identity"));
    }

    @Test
    void gzipOnlyWhenSmaller() {
        assertNotNull(ApiServer.gzip("a".repeat(5000).getBytes()));
        assertNull(ApiServer.gzip(new byte[]{1, 2, 3}));
    }

    @Test
    void localAndPrivateAddresses() throws Exception {
        assertTrue(ApiServer.isLocalOrPrivate(InetAddress.getByName("127.0.0.1")));
        assertTrue(ApiServer.isLocalOrPrivate(InetAddress.getByName("10.1.2.3")));
        assertTrue(ApiServer.isLocalOrPrivate(InetAddress.getByName("192.168.1.1")));
        assertTrue(ApiServer.isLocalOrPrivate(InetAddress.getByName("169.254.1.1")));
        assertTrue(ApiServer.isLocalOrPrivate(InetAddress.getByName("::1")));
        assertTrue(ApiServer.isLocalOrPrivate(InetAddress.getByName("fe80::1")));
        assertTrue(ApiServer.isLocalOrPrivate(InetAddress.getByName("febf::1")));
        assertTrue(ApiServer.isLocalOrPrivate(InetAddress.getByName("fd00::1")));
        assertFalse(ApiServer.isLocalOrPrivate(InetAddress.getByName("2001:db8::1")));
        assertFalse(ApiServer.isLocalOrPrivate(InetAddress.getByName("8.8.8.8")));
        assertFalse(ApiServer.isLocalOrPrivate(null));
    }

    @Test
    void versionAssetsTagsLocalFiles() {
        String html = "<script src=\"app.js\"></script><link href=\"vendor/leaflet.css\"><a href=\"https://x.org/a.js\">";
        String out = ApiServer.versionAssets(html, "1.0.0");
        assertTrue(out.contains("src=\"app.js?v=1.0.0\""));
        assertTrue(out.contains("href=\"vendor/leaflet.css?v=1.0.0\""));
        assertTrue(out.contains("href=\"https://x.org/a.js\""));
    }

    @Test
    void indexCspHashesInlineScripts() {
        assertEquals(ApiServer.CSP, ApiServer.indexCsp("<p>no scripts</p>"));
        String csp = ApiServer.indexCsp("<script>var a=1;</script>");
        assertTrue(csp.startsWith(ApiServer.CSP + "; script-src 'self' 'sha256-"));
    }
}
