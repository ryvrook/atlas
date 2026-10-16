package dev.ryvrook.atlas.integration;

import dev.ryvrook.atlas.config.TrackerMode;
import dev.ryvrook.atlas.integration.PluginPresence.Found;

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

    /** The loaded TrackerAPI, or null when it is not loaded. Safe from any thread. */
    public static Found find() {
        return PluginPresence.find(GROUP, NAME);
    }

    private TrackerPresence() {
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

    /** TrackerAPI from this version on reloads every plugin that lists it, Atlas included, after its own start. */
    public static final String RELOADS_DEPENDENTS = "1.0.3";

    /**
     * Watches the TrackerAPI plugin object every few seconds. A reloaded TrackerAPI brings new classes that
     * the running Atlas cannot use, and one that appears or goes away after setup cannot change the systems
     * Atlas registered. TrackerAPI 1.0.3 and newer reloads Atlas itself two seconds after it starts, so the
     * watch stays quiet then. For an older TrackerAPI, or one that was unloaded, each change asks for a
     * reload of Atlas in one log line.
     */
    public static final class Watch {
        private final Source source;
        private final TrackerMode mode;
        private Object seen;
        private boolean gonePending;

        public Watch(TrackerMode mode, Source source, Found atSetup) {
            this.mode = mode == null ? TrackerMode.AUTO : mode;
            this.source = source;
            this.seen = atSetup == null ? null : atSetup.identity();
        }

        /** The line to log for the plugin object now loaded, or null when nothing changed or TrackerAPI handles it. */
        public synchronized String check(Found now) {
            Object id = now == null ? null : now.identity();
            if (id == seen || mode == TrackerMode.NEVER) {
                gonePending = false;
                return null;
            }
            if (id == null && !gonePending) {
                // A reload takes TrackerAPI away for a moment and then reloads Atlas, so the gap is only
                // reported when the next look still finds no TrackerAPI.
                gonePending = true;
                return null;
            }
            gonePending = false;
            Object before = seen;
            seen = id;
            if (id != null && !VersionCompare.below(now.version(), RELOADS_DEPENDENTS)) {
                return null;
            }
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
            String reason = id == null && mode == TrackerMode.AUTO && source == Source.TRACKER
                    ? ", since its own systems can only be registered at plugin start" : "";
            return what + ". " + effect + reason + ". Run /plugin reload Ryvrook:Atlas";
        }
    }
}
