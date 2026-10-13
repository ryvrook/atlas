package dev.ryvrook.atlas.markers;

import dev.ryvrook.atlas.api.MarkerLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HtmlTest {
    @Test
    void escapesSpecialCharacters() {
        assertEquals("&lt;img src=x onerror=&quot;a()&quot;&gt; &amp; &#39;q&#39;", Html.escape("<img src=x onerror=\"a()\"> & 'q'"));
        assertEquals("one<br>two", Html.escape("one\r\ntwo"));
        assertEquals("plain", Html.escape("plain"));
        assertNull(Html.escape(null));
    }

    @Test
    void popupIsEscapedUnlessTrustedThroughTheApi() throws Exception {
        MarkerLayer l = LayerFile.parse("file", "p", "{\"world\": \"w\", \"markers\": ["
                + "{\"id\": \"a\", \"x\": 0, \"z\": 0, \"popup\": \"<b>hi</b>\"}]}");
        assertEquals("&lt;b&gt;hi&lt;/b&gt;", LayerJson.marker(l.get("w", "a")).get("popup").getAsString());
        MarkerLayer api = ApiImpl.detached("plugin", "p");
        api.point("w", "b", 0, 0).popupHtml("<b>hi</b>");
        assertEquals("<b>hi</b>", LayerJson.marker(api.get("w", "b")).get("popup").getAsString());
    }
}
