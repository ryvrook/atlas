package dev.ryvrook.atlas.http;

import com.sun.net.httpserver.Headers;
import dev.ryvrook.atlas.auth.LinkService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

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

    private static Headers cookies(String line) {
        Headers h = new Headers();
        h.add("Cookie", line);
        return h;
    }

    @Test
    void oldSessionCookieIsAcceptedAndRewritten(@TempDir Path dir) {
        LinkService links = new LinkService(dir);
        LinkService.Linked linked = links.redeem(links.issueCode(UUID.randomUUID(), "Ann"));
        ApiServer.SessionCookie read = ApiServer.readSession(cookies("theme=dark; atlas_session=" + linked.token()), "ryvrook_session");
        assertEquals(linked.token(), read.token());
        assertTrue(read.legacy());
        assertNotNull(links.session(read.token()), "a browser linked on 1.0.0 stays linked");
        List<String> lines = ApiServer.migrateCookie(read, true, "ryvrook_session", false, "");
        assertEquals(List.of(
                "ryvrook_session=" + linked.token() + "; Max-Age=2592000; Path=/; HttpOnly; SameSite=Lax",
                "atlas_session=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax"), lines);
    }

    @Test
    void rewriteCarriesDomainAndSecure() {
        ApiServer.SessionCookie read = ApiServer.readSession(cookies("atlas_session=tok"), "ryvrook_session");
        List<String> lines = ApiServer.migrateCookie(read, true, "ryvrook_session", true, "example.com");
        assertEquals("ryvrook_session=tok; Max-Age=2592000; Path=/; HttpOnly; SameSite=Lax; Domain=example.com; Secure", lines.get(0));
        // The old cookie never had a Domain, so it is cleared without one.
        assertEquals("atlas_session=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax; Secure", lines.get(1));
    }

    @Test
    void oldCookieWithoutSessionIsOnlyCleared() {
        ApiServer.SessionCookie read = ApiServer.readSession(cookies("atlas_session=gone"), "ryvrook_session");
        assertEquals(List.of("atlas_session=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax"),
                ApiServer.migrateCookie(read, false, "ryvrook_session", false, ""));
    }

    @Test
    void currentCookieWinsAndNothingMovesWithoutTheOldOne() {
        ApiServer.SessionCookie both = ApiServer.readSession(cookies("atlas_session=old; ryvrook_session=new"), "ryvrook_session");
        assertEquals("new", both.token());
        assertFalse(both.legacy());
        assertTrue(both.legacyPresent());
        assertEquals(List.of("atlas_session=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax"),
                ApiServer.migrateCookie(both, true, "ryvrook_session", false, ""));

        ApiServer.SessionCookie current = ApiServer.readSession(cookies("ryvrook_session=new"), "ryvrook_session");
        assertEquals("new", current.token());
        assertTrue(ApiServer.migrateCookie(current, true, "ryvrook_session", false, "").isEmpty());

        ApiServer.SessionCookie none = ApiServer.readSession(new Headers(), "ryvrook_session");
        assertNull(none.token());
        assertTrue(ApiServer.migrateCookie(none, false, "ryvrook_session", false, "").isEmpty());

        // An owner who keeps the old name has nothing to move.
        ApiServer.SessionCookie kept = ApiServer.readSession(cookies("atlas_session=tok"), "atlas_session");
        assertEquals("tok", kept.token());
        assertFalse(kept.legacy());
        assertTrue(ApiServer.migrateCookie(kept, true, "atlas_session", false, "").isEmpty());
    }

    @Test
    void cookieAttributes() {
        assertEquals("; Path=/; HttpOnly; SameSite=Lax", ApiServer.cookieAttributes(false, ""));
        assertEquals("; Path=/; HttpOnly; SameSite=Lax; Domain=.example.com", ApiServer.cookieAttributes(false, ".example.com"));
        assertEquals("; Path=/; HttpOnly; SameSite=Lax; Secure", ApiServer.cookieAttributes(true, null));
    }
}
