package dev.ryvrook.atlas.http;

/** Plugin version reported by /api/health, filled from the manifest at setup. */
public final class AtlasVersion {
    public static volatile String VERSION = "0.2.2";

    private AtlasVersion() {
    }
}
