package dev.ryvrook.atlas.integration;

/** What the plugin keeps of the TrackerAPI bridge, so it never names a TrackerAPI type itself. */
public interface TrackerLink extends AutoCloseable {

    /** True while the bridge holds live subscriptions. */
    boolean bound();

    /** Drops the enable and disable hooks and every subscription. Calling it twice is harmless. */
    @Override
    void close();
}
