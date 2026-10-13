package dev.ryvrook.atlas.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Plugin version reported by /api/health and the client. Gradle writes it into version.txt next to
 * this class from the same value it puts in manifest.json, so the two never differ.
 */
public final class AtlasVersion {
    public static volatile String VERSION = read();

    private AtlasVersion() {
    }

    private static String read() {
        try (InputStream in = AtlasVersion.class.getResourceAsStream("version.txt")) {
            if (in != null) {
                String v = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        } catch (IOException ignored) {
            // Fall through to the placeholder below.
        }
        return "unknown";
    }
}
