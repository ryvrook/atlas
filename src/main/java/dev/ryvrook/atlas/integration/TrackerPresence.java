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
     * Atlas registered. TrackerAPI 1.0.3 and newer reloads Atlas itself two seconds after it starts, so its
     * reload gives one information line, and a warning only when it is still gone after 30 seconds. For an
     * older TrackerAPI each change asks for a reload of Atlas in one warning.
     */
    public static final class Watch {
        /** How long a TrackerAPI that reloads its dependents may stay away before the line becomes a warning. */
        public static final long GRACE_MS = 30_000;

        /** One line to log, as a warning or as information. */
        public record Notice(boolean warn, String text) {
        }

        private final Source source;
        private final TrackerMode mode;
        private Object seen;
        private String seenVersion;
        private long goneSince;
        private boolean goneWarned;

        public Watch(TrackerMode mode, Source source, Found atSetup) {
            this.mode = mode == null ? TrackerMode.AUTO : mode;
            this.source = source;
            this.seen = atSetup == null ? null : atSetup.identity();
            this.seenVersion = atSetup == null ? null : atSetup.version();
        }

        /** {@link #check(Found, long)} at the current time. */
        public Notice check(Found now) {
            return check(now, System.currentTimeMillis());
        }

        /**
         * The line to log for the plugin object now loaded, or null when nothing changed. A TrackerAPI from
         * {@link #RELOADS_DEPENDENTS} on that goes away is being reloaded and reloads Atlas itself, so that is
         * one information line, and a warning follows only when it is still gone after {@link #GRACE_MS}.
         */
        public synchronized Notice check(Found now, long at) {
            Object id = now == null ? null : now.identity();
            if (mode == TrackerMode.NEVER) {
                return null;
            }
            if (id == null && seen == null && goneSince > 0) {
                if (!goneWarned && at - goneSince >= GRACE_MS) {
                    goneWarned = true;
                    return new Notice(true, unloaded(GRACE_MS / 1000));
                }
                return null;
            }
            if (id == seen) {
                return null;
            }
            Object before = seen;
            String beforeVersion = seenVersion;
            seen = id;
            seenVersion = now == null ? null : now.version();
            goneSince = 0;
            goneWarned = false;
            if (id == null) {
                if (before != null && beforeVersion != null && !VersionCompare.below(beforeVersion, RELOADS_DEPENDENTS)) {
                    goneSince = Math.max(1, at);
                    return new Notice(false, "TrackerAPI was unloaded. A reloaded TrackerAPI reloads Atlas by itself "
                            + "about two seconds after it starts, so nothing needs doing");
                }
                return new Notice(true, unloaded(0));
            }
            if (!VersionCompare.below(now.version(), RELOADS_DEPENDENTS)) {
                return null;
            }
            String what = before == null ? "TrackerAPI " + now.version() + " was loaded"
                    : "TrackerAPI " + now.version() + " was reloaded";
            String effect = switch (source) {
                case TRACKER -> "Atlas cannot use the new TrackerAPI classes until it is reloaded";
                case OWN -> "Atlas keeps its own systems until it is reloaded";
                case NONE -> "Atlas records nothing until it is reloaded";
            };
            return new Notice(true, what + ". " + effect + ". Run /plugin reload Ryvrook:Atlas");
        }

        private String unloaded(long seconds) {
            String what = seconds > 0 ? "TrackerAPI has been unloaded for " + seconds + " seconds"
                    : "TrackerAPI was unloaded";
            String effect = switch (source) {
                case TRACKER -> "Atlas records no block activity and no kills until it is reloaded";
                case OWN -> "Atlas keeps its own systems until it is reloaded";
                case NONE -> "Atlas records nothing until it is reloaded";
            };
            String reason = mode == TrackerMode.AUTO && source == Source.TRACKER
                    ? ", since its own systems can only be registered at plugin start" : "";
            return what + ". " + effect + reason + ". Run /plugin reload Ryvrook:Atlas";
        }
    }
}
