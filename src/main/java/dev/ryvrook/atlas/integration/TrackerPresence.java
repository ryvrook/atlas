package dev.ryvrook.atlas.integration;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import dev.ryvrook.atlas.config.TrackerMode;

/**
 * Finds TrackerAPI through the engine's plugin manager and decides where the heat layer and the kill
 * feed come from. ECS systems can only be registered in setup, before TrackerAPI's API object exists, so
 * the decision is made from the plugin manager. Never touches a TrackerAPI class.
 */
public final class TrackerPresence {
    public static final String GROUP = "Ryvrook";
    public static final String NAME = "TrackerAPI";
    public static final String MINIMUM = "1.0.0";

    /** Where block activity and deaths come from. */
    public enum Source {
        /** Atlas registers its own block and death systems. */
        OWN,
        /** Atlas registers nothing and the bridge feeds it from TrackerAPI. */
        TRACKER,
        /** tracker is always but TrackerAPI is missing or too old, so nothing is recorded. */
        NONE
    }

    /** The loaded TrackerAPI plugin object and its manifest version. */
    public record Found(Object identity, String version) {
    }

    private TrackerPresence() {
    }

    /** The loaded TrackerAPI, or null when it is not loaded. Safe from any thread. */
    public static Found find() {
        try {
            PluginManager manager = PluginManager.get();
            if (manager == null) {
                return null;
            }
            PluginBase p = manager.getPlugin(new PluginIdentifier(GROUP, NAME));
            if (p == null) {
                return null;
            }
            String version = p.getManifest() == null || p.getManifest().getVersion() == null
                    ? "0.0.0" : p.getManifest().getVersion().toString();
            return new Found(p, version);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** True when the found TrackerAPI is one Atlas can use. */
    public static boolean usable(Found found) {
        return found != null && !VersionCompare.below(found.version(), MINIMUM);
    }

    /** The source for a mode and what the plugin manager holds. Pure. */
    public static Source decide(TrackerMode mode, Found found) {
        return switch (mode == null ? TrackerMode.AUTO : mode) {
            case NEVER -> Source.OWN;
            case AUTO -> usable(found) ? Source.TRACKER : Source.OWN;
            case ALWAYS -> usable(found) ? Source.TRACKER : Source.NONE;
        };
    }

    /** The one log line that says which source is active. */
    public static String describe(TrackerMode mode, Source source, Found found) {
        String key = (mode == null ? TrackerMode.AUTO : mode).key();
        return switch (source) {
            case TRACKER -> "Atlas takes block activity and kills from TrackerAPI " + found.version()
                    + " (tracker " + key + ")";
            case OWN -> "Atlas records block activity and kills with its own systems (tracker " + key
                    + (found == null || mode == TrackerMode.NEVER ? ")"
                    : ", TrackerAPI " + found.version() + " is older than " + MINIMUM + ")");
            case NONE -> "Atlas records no block activity and no kills, since tracker is always and "
                    + (found == null ? "TrackerAPI is not installed" : "TrackerAPI " + found.version()
                    + " is older than " + MINIMUM) + ". Install TrackerAPI " + MINIMUM
                    + " or newer, or set tracker to auto";
        };
    }

    /**
     * Watches the TrackerAPI plugin object every few seconds. A reloaded TrackerAPI brings new classes that
     * the running Atlas cannot use, and one that appears or goes away after setup cannot change the systems
     * Atlas registered, so each change asks for a reload of Atlas in one log line.
     */
    public static final class Watch {
        private final Source source;
        private final TrackerMode mode;
        private Object seen;

        public Watch(TrackerMode mode, Source source, Found atSetup) {
            this.mode = mode == null ? TrackerMode.AUTO : mode;
            this.source = source;
            this.seen = atSetup == null ? null : atSetup.identity();
        }

        /** The line to log for the plugin object now loaded, or null when nothing changed. */
        public synchronized String check(Found now) {
            Object id = now == null ? null : now.identity();
            if (id == seen || mode == TrackerMode.NEVER) {
                return null;
            }
            Object before = seen;
            seen = id;
            String what = id == null ? "TrackerAPI was unloaded"
                    : before == null ? "TrackerAPI " + now.version() + " was loaded"
                    : "TrackerAPI " + now.version() + " was reloaded";
            String effect = switch (source) {
                case TRACKER -> id == null
                        ? "Atlas records no block activity and no kills until it is reloaded"
                        : "Atlas cannot use the new TrackerAPI classes until it is reloaded";
                case OWN -> "Atlas keeps its own systems until it is reloaded";
                case NONE -> "Atlas records nothing until it is reloaded";
            };
            String reason = mode == TrackerMode.AUTO && source == Source.TRACKER
                    ? ", since its own systems can only be registered at server start" : "";
            return what + ". " + effect + reason + ". Run /plugin reload Ryvrook:Atlas";
        }
    }
}
