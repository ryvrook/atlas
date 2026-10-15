package dev.ryvrook.atlas.integration;

/**
 * Decides when the Dominion snapshot file is left over from a Dominion that no longer runs. Atlas never
 * links against Dominion, it reads the file, which stays on disk after Dominion is removed or fails to
 * start. Only the default path is watched, so a snapshot written by something else is never dropped.
 *
 * <p>Dominion loads after Atlas, since it lists Atlas as an optional dependency, and a reload of Dominion
 * takes it away for a moment, so it has to be missing for {@link #GRACE_MS} before the file is dropped.
 * Pure, the caller passes what the plugin manager says and the time.
 */
public final class DominionWatch {
    public static final String GROUP = "Ryvrook";
    public static final String NAME = "Dominion";
    /** How long Dominion has to be missing before its file is dropped. */
    public static final long GRACE_MS = 60_000;

    private long missingSince;
    private boolean gone;

    /**
     * Feeds one look at the plugin manager. Returns TRUE when the file should now be dropped, FALSE when it
     * should be read again, and null when nothing changed.
     */
    public synchronized Boolean update(boolean watched, boolean missing, long now) {
        if (!watched || !missing) {
            missingSince = 0;
            if (gone) {
                gone = false;
                return Boolean.FALSE;
            }
            return null;
        }
        if (missingSince == 0) {
            missingSince = now;
        }
        if (!gone && now - missingSince >= GRACE_MS) {
            gone = true;
            return Boolean.TRUE;
        }
        return null;
    }

    public synchronized boolean gone() {
        return gone;
    }
}
