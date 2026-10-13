package dev.ryvrook.atlas.util;

import com.hypixel.hytale.protocol.BoolParamValue;
import com.hypixel.hytale.protocol.DoubleParamValue;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.IntParamValue;
import com.hypixel.hytale.protocol.LongParamValue;
import com.hypixel.hytale.protocol.ParamValue;
import com.hypixel.hytale.protocol.StringParamValue;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;

import java.util.Locale;
import java.util.Map;

/**
 * Plain text from engine messages. Translation keys are looked up in the server's en-US strings
 * through I18nModule.getMessage(language, key), which returns null for unknown keys. Anything
 * that cannot be translated falls back to the key or id made readable.
 */
public final class Text {
    private static final String LANGUAGE = "en-US";

    private Text() {
    }

    /** The en-US text of a translation key, or null when the key is unknown or the module is missing. */
    public static String translate(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            I18nModule i18n = I18nModule.get();
            if (i18n == null) {
                return null;
            }
            String s = i18n.getMessage(LANGUAGE, key);
            if (s == null && key.startsWith("server.")) {
                s = i18n.getMessage(LANGUAGE, key.substring(7));
            }
            return s == null || s.isBlank() ? null : s;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** A translation key or asset id as readable words, such as Goblin_Breach to Goblin Breach. */
    public static String pretty(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1 && !s.contains(" ")) {
            s = s.substring(dot + 1);
        }
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0 && slash < s.length() - 1) {
            s = s.substring(slash + 1);
        }
        return s.replace('_', ' ').trim();
    }

    /** The translated text of a key, or the key made readable. */
    public static String translateOrPretty(String key) {
        String t = translate(key);
        return t != null ? t : pretty(key);
    }

    /** Plain text of a formatted message. Raw text wins, then the translated id with simple parameters filled in. */
    public static String plain(FormattedMessage m) {
        if (m == null) {
            return null;
        }
        if (m.rawText != null && !m.rawText.isBlank()) {
            return m.rawText;
        }
        if (m.messageId == null) {
            return null;
        }
        String t = translate(m.messageId);
        if (t == null) {
            return pretty(m.messageId);
        }
        if (m.params != null) {
            for (Map.Entry<String, ParamValue> e : m.params.entrySet()) {
                String v = paramText(e.getValue());
                if (v != null) {
                    t = t.replace("{" + e.getKey() + "}", v);
                }
            }
        }
        return stripMarkup(t);
    }

    private static String paramText(ParamValue p) {
        if (p instanceof StringParamValue s) {
            return s.value;
        }
        if (p instanceof IntParamValue i) {
            return Integer.toString(i.value);
        }
        if (p instanceof LongParamValue l) {
            return Long.toString(l.value);
        }
        if (p instanceof DoubleParamValue d) {
            return String.format(Locale.ROOT, "%.1f", d.value);
        }
        if (p instanceof BoolParamValue b) {
            return Boolean.toString(b.value);
        }
        return null;
    }

    /** Removes simple markup tags such as <b> or <color is="#fff"> that some strings carry. */
    static String stripMarkup(String s) {
        return s.replaceAll("<[^<>]{1,40}>", "").trim();
    }

    /** An engine image path such as UI/WorldMap/MapMarkers/GoblinBreach.png as GoblinBreach. */
    public static String imageName(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String s = path;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        return s.isBlank() ? null : s;
    }
}
