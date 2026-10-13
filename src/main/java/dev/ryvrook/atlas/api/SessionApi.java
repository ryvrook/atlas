package dev.ryvrook.atlas.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The Atlas session API, which lets a sibling web app share one login with the map. It hands out link
 * codes, redeems them, looks sessions up by the hash of their token, revokes them, takes in sessions the
 * other app redeemed itself and reports every change, so two session stores can mirror each other. The
 * hidden player list comes along so both pages hide the same players.
 *
 * <p>This API is versioned on its own, apart from {@link AtlasApi} and the browser API. {@link #VERSION}
 * is the version of this class and {@link #version()} the version of the running Atlas. Within version 1
 * methods, record components and event kinds are only ever added, never removed, renamed or given another
 * meaning. A caller that binds by reflection checks {@code version()} once and looks up only the methods
 * it knows.
 *
 * <p>A session token is the cookie value and never leaves the browser and the app that redeemed it. Atlas
 * keeps only its hex SHA-256 hash, 64 lower case characters, and every method here names a session by
 * that hash. Sessions last 30 days after they were last seen.
 *
 * <p>Threads. Every method may be called from any thread and runs on the caller's thread. None of them
 * blocks on IO or touches a world thread. Changes are kept in memory and written by the existing Atlas
 * save of {@code sessions.json} and {@code hidden.json}, every five minutes and when Atlas stops.
 * Listeners from {@link #onChange(Consumer)} are called one at a time, in the order of the changes, on an
 * {@code Atlas-scheduler} thread, never on the caller's thread. A listener should return quickly. One that
 * throws is logged and the others still run.
 *
 * <pre>{@code
 * SessionApi api = SessionApi.get();
 * if (api != null && api.version() >= 1) {
 *     Runnable stop = api.onChange(e -> mirror(e));
 * }
 * }</pre>
 */
public abstract class SessionApi {
    /** The version of this class. Raised only when a method or record is added. */
    public static final int VERSION = 1;

    private static volatile SessionApi current;

    protected SessionApi() {
    }

    /** The running API, or null before Atlas has started and after it stopped. Any thread. */
    public static SessionApi get() {
        return current;
    }

    /** The version the running Atlas implements, 1 for Atlas 1.1.0. Any thread. */
    public int version() {
        return VERSION;
    }

    /**
     * A new six character link code for the player, valid five minutes and single use. An open code of the
     * same player is dropped. The code redeems on Atlas and through {@link #redeem(String)}. A null uuid
     * throws IllegalArgumentException. Any thread.
     */
    public abstract String issueCode(UUID uuid, String name);

    /**
     * Redeems a code and makes a new session. Returns the token for the cookie and the session, or null when
     * the code is unknown, used or expired. Rate limits are the caller's job. Listeners get a REDEEMED event.
     * Any thread.
     */
    public abstract Linked redeem(String code);

    /**
     * The session with this token hash, or null when there is none or it expired. Refreshes the time it was
     * last seen at most once an hour. Any thread.
     */
    public abstract Session resolve(String tokenHash);

    /**
     * Ends the session with this token hash. True when it existed. Listeners get a REVOKED event only when it
     * did. Any thread.
     */
    public abstract boolean revoke(String tokenHash);

    /**
     * Takes in a session the other app redeemed itself, so the same cookie also works on Atlas. A session
     * Atlas already holds keeps the later of the two last seen times. Listeners get an ADOPTED event only
     * when the hash was new, so two stores that mirror each other never loop. A hash that is not 64 lower
     * case hex characters, a null session or a session that already expired is ignored. Any thread.
     */
    public abstract void adopt(Session session, String tokenHash);

    /**
     * Every player whose name Atlas hides, as an unmodifiable copy. That is the hide list of
     * {@code /atlas hide}, the players hidden through {@link AtlasApi#setHidden} and the hidden flag of the
     * factions snapshot file. The crouching and invisible rules only hide a position and are not included.
     * Any thread.
     */
    public abstract Set<UUID> hidden();

    /**
     * Registers a listener for every session change and returns a handle that unregisters it. Listeners are
     * called on an Atlas-scheduler thread in the order of the changes and are dropped when Atlas stops.
     * Atlas fires REDEEMED when a code is redeemed on the map or through this API, ADOPTED when
     * {@link #adopt} added a session and REVOKED when a browser signs out, {@code /atlas unlink} runs or
     * {@link #revoke} removed a session. Sessions that simply expire fire nothing. Any thread.
     */
    public abstract Runnable onChange(Consumer<SessionEvent> listener);

    /** A linked browser, without its token. createdAt and lastSeen are epoch milliseconds. */
    public record Session(UUID uuid, String name, long createdAt, long lastSeen) {
    }

    /** A freshly redeemed code. token is the cookie value and exists only in this answer. */
    public record Linked(String token, Session session) {
    }

    /** One change to the sessions. tokenHash names the session and uuid its player. */
    public record SessionEvent(Kind kind, String tokenHash, UUID uuid) {
        /** What happened to the session. */
        public enum Kind {
            REDEEMED, ADOPTED, REVOKED
        }
    }

    /** Atlas internal. Publishes the API. */
    protected static void enable(SessionApi api) {
        current = Objects.requireNonNull(api, "api");
    }

    /** Atlas internal. Withdraws the API when it is the running one. */
    protected static void disable(SessionApi api) {
        if (api != null && current == api) {
            current = null;
        }
    }
}
