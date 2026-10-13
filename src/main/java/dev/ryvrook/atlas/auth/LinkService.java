package dev.ryvrook.atlas.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.atlas.api.SessionApi;
import dev.ryvrook.atlas.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Browser linking. /atlas link issues a six character code that lives five minutes. Posting it
 * to /api/link trades it for a long lived session token kept in a cookie. Sessions and the
 * list of players who ran /atlas hide are saved in the data directory.
 *
 * <p>Only a SHA-256 hash of each session token is kept, in memory and in sessions.json, so a copy
 * of the file cannot be used to sign in. Sessions are looked up by the hash of the cookie value.
 * Files from 0.2.2 and older held the plain tokens as a bare array. They are hashed once on load and
 * written back in the versioned format.
 *
 * <p>Redeemed, adopted and revoked sessions are reported to one listener on the thread that made the
 * change. The session API turns them into SessionApi events on the Atlas scheduler.
 */
public final class LinkService {
    public static final long CODE_TTL_MS = 5 * 60 * 1000L;
    public static final long SESSION_TTL_MS = 30L * 24 * 60 * 60 * 1000L;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    /** Format of sessions.json. Version 2 stores token hashes. Version 1 was a bare array of plain tokens. */
    public static final int SESSIONS_FORMAT = 2;
    /** Attempts allowed per address and minute, shared by link codes and admin tokens. */
    private static final int ATTEMPTS_PER_MINUTE = 10;

    public record Pending(UUID uuid, String name, long expiresAt) {
    }

    /** A linked browser. hash is the hex SHA-256 of the session token, never the token itself. */
    public record Session(String hash, UUID uuid, String name, long createdAt, long lastSeen) {
    }

    /** A freshly redeemed code. token is the cookie value and exists only in this answer. */
    public record Linked(String token, Session session) {
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> codes = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    private final Map<String, long[]> attempts = new ConcurrentHashMap<>();
    private final Path sessionsFile;
    private final Path hiddenFile;
    /** Counts changes. The files are written while it differs from the count at the last successful write. */
    private final java.util.concurrent.atomic.AtomicLong changes = new java.util.concurrent.atomic.AtomicLong();
    private volatile long saved;
    /** Told about each redeemed, adopted and revoked session, on the thread that made the change. */
    private volatile Consumer<SessionApi.SessionEvent> events = e -> { };

    public LinkService(Path dataDir) {
        this.sessionsFile = dataDir.resolve("sessions.json");
        this.hiddenFile = dataDir.resolve("hidden.json");
    }

    /** Sets the one listener for session changes, or none when it is null. */
    public void onEvent(Consumer<SessionApi.SessionEvent> listener) {
        events = listener == null ? e -> { } : listener;
    }

    private void fire(SessionApi.SessionEvent.Kind kind, String hash, UUID uuid) {
        try {
            events.accept(new SessionApi.SessionEvent(kind, hash, uuid));
        } catch (RuntimeException ignored) {
            // A listener must never break linking.
        }
    }

    public String issueCode(UUID uuid, String name) {
        codes.values().removeIf(p -> p.uuid().equals(uuid) || p.expiresAt() < System.currentTimeMillis());
        String code;
        do {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            code = sb.toString();
        } while (codes.containsKey(code));
        codes.put(code, new Pending(uuid, name, System.currentTimeMillis() + CODE_TTL_MS));
        return code;
    }

    /** True when this address has made too many attempts in the last minute. */
    public boolean rateLimited(String address) {
        long now = System.currentTimeMillis();
        long[] slot = attempts.compute(rateKey(address), (k, v) -> {
            if (v == null || now - v[0] > 60_000) {
                return new long[]{now, 1};
            }
            v[1]++;
            return v;
        });
        if (attempts.size() > 10_000) {
            attempts.entrySet().removeIf(e -> now - e.getValue()[0] > 60_000);
        }
        return slot[1] > ATTEMPTS_PER_MINUTE;
    }

    /** True when this address already used up its attempts for the current minute. Counts nothing. */
    public boolean blocked(String address) {
        long[] slot = attempts.get(rateKey(address));
        return slot != null && System.currentTimeMillis() - slot[0] <= 60_000 && slot[1] >= ATTEMPTS_PER_MINUTE;
    }

    /**
     * The key an address is rate limited under. IPv4 addresses, and IPv6 addresses that carry one
     * (mapped or compatible forms), are their dotted IPv4 text. Other IPv6 clients are limited per
     * /64 so rotating addresses inside one network does not reset the limit, and every spelling of
     * the same network (compressed, expanded, upper case, with a zone id or brackets) gives one key.
     * Only IP literals are parsed, so no lookup ever reaches DNS. Anything else is used as it is.
     */
    public static String rateKey(String address) {
        if (address == null) {
            return "?";
        }
        String s = address.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        byte[] b = literal(s);
        if (b == null) {
            return address;
        }
        if (b.length == 4) {
            return (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + "." + (b[3] & 0xFF);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i += 2) {
            sb.append(Integer.toHexString(((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF))).append(':');
        }
        return sb.append(":/64").toString();
    }

    /** The bytes of an IPv4 or IPv6 literal, or null. Characters are checked first so nothing is resolved. */
    private static byte[] literal(String s) {
        if (s.isEmpty() || s.length() > 45) {
            return null;
        }
        boolean colon = s.indexOf(':') >= 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || c == '.'
                    || (colon && (c == ':' || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')));
            if (!ok) {
                return null;
            }
        }
        if (!colon) {
            String[] parts = s.split("\\.", -1);
            if (parts.length != 4) {
                return null;
            }
            byte[] out = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3) {
                    return null;
                }
                int v = Integer.parseInt(parts[i]);
                if (v > 255) {
                    return null;
                }
                out[i] = (byte) v;
            }
            return out;
        }
        try {
            byte[] b = java.net.InetAddress.getByName(s).getAddress();
            if (b.length == 16 && isIpv4Compatible(b)) {
                return new byte[]{b[12], b[13], b[14], b[15]};
            }
            return b;
        } catch (java.net.UnknownHostException | SecurityException e) {
            return null;
        }
    }

    /** True for ::a.b.c.d, the deprecated IPv4 compatible form, which InetAddress keeps as IPv6. */
    private static boolean isIpv4Compatible(byte[] b) {
        for (int i = 0; i < 12; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        // :: and ::1 are IPv6 addresses of their own, not IPv4 ones.
        return (b[12] | b[13]) != 0;
    }

    /** Redeems a code. Returns the new token and session, or null when the code is unknown or expired. */
    public Linked redeem(String rawCode) {
        if (rawCode == null) {
            return null;
        }
        String code = rawCode.trim().toUpperCase(java.util.Locale.ROOT).replace(" ", "");
        Pending p = codes.remove(code);
        if (p == null || p.expiresAt() < System.currentTimeMillis()) {
            return null;
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long now = System.currentTimeMillis();
        Session s = new Session(hash(token), p.uuid(), p.name(), now, now);
        sessions.put(s.hash(), s);
        changes.incrementAndGet();
        fire(SessionApi.SessionEvent.Kind.REDEEMED, s.hash(), s.uuid());
        return new Linked(token, s);
    }

    /** The session of a cookie value, or null. */
    public Session session(String token) {
        if (token == null || token.isEmpty() || token.length() > 200) {
            return null;
        }
        return sessionByHash(hash(token));
    }

    /**
     * The session with this token hash, or null when there is none or it expired. An expired session is
     * dropped, and lastSeen is moved forward at most once an hour.
     */
    public Session sessionByHash(String key) {
        if (!isHash(key)) {
            return null;
        }
        Session s = sessions.get(key);
        if (s == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - s.lastSeen() > SESSION_TTL_MS) {
            sessions.remove(key);
            changes.incrementAndGet();
            return null;
        }
        if (now - s.lastSeen() > 60 * 60 * 1000L) {
            s = new Session(s.hash(), s.uuid(), s.name(), s.createdAt(), now);
            sessions.put(key, s);
            changes.incrementAndGet();
        }
        return s;
    }

    /** Hex SHA-256 of a session token. */
    public static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java runtime", e);
        }
    }

    /** True for 64 lower case hex characters, the form of every token hash. */
    public static boolean isHash(String s) {
        if (s == null || s.length() != 64) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /** Drops expired sessions, expired link codes and old rate limit slots. Returns the sessions removed. */
    public int prune() {
        long now = System.currentTimeMillis();
        int before = sessions.size();
        sessions.values().removeIf(s -> now - s.lastSeen() > SESSION_TTL_MS);
        int removed = before - sessions.size();
        if (removed > 0) {
            changes.incrementAndGet();
        }
        codes.values().removeIf(p -> p.expiresAt() < now);
        attempts.entrySet().removeIf(e -> now - e.getValue()[0] > 60_000);
        return removed;
    }

    public void revoke(String token) {
        if (token != null && !token.isEmpty() && token.length() <= 200) {
            revokeHash(hash(token));
        }
    }

    /** Removes the session with this token hash. True when it existed. */
    public boolean revokeHash(String key) {
        if (!isHash(key)) {
            return false;
        }
        Session s = sessions.remove(key);
        if (s == null) {
            return false;
        }
        changes.incrementAndGet();
        fire(SessionApi.SessionEvent.Kind.REVOKED, key, s.uuid());
        return true;
    }

    /**
     * Takes in a session another app redeemed, under the hash of its token. A session already held keeps
     * the later lastSeen. Returns true only when the hash was new. A malformed hash, a missing player or a
     * session past its lifetime is ignored. A lastSeen in the future counts as now.
     */
    public boolean adopt(String key, UUID uuid, String name, long createdAt, long lastSeen) {
        if (!isHash(key) || uuid == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long seen = Math.min(lastSeen, now);
        if (now - seen > SESSION_TTL_MS) {
            return false;
        }
        Session fresh = new Session(key, uuid, name, Math.min(createdAt, seen), seen);
        boolean[] added = {false};
        boolean[] changed = {false};
        sessions.compute(key, (k, old) -> {
            if (old == null) {
                added[0] = true;
                return fresh;
            }
            if (old.uuid().equals(uuid) && seen > old.lastSeen()) {
                changed[0] = true;
                return new Session(k, old.uuid(), old.name(), old.createdAt(), seen);
            }
            return old;
        });
        if (added[0] || changed[0]) {
            changes.incrementAndGet();
        }
        if (added[0]) {
            fire(SessionApi.SessionEvent.Kind.ADOPTED, key, uuid);
        }
        return added[0];
    }

    /**
     * Removes every session linked to the player, so each browser they linked falls back to a public
     * viewer on its next request and open streams notice within the identity refresh. Returns the
     * number of sessions removed.
     */
    public int revokePlayer(UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            if (uuid.equals(e.getValue().uuid()) && sessions.remove(e.getKey()) != null) {
                removed++;
                fire(SessionApi.SessionEvent.Kind.REVOKED, e.getKey(), uuid);
            }
        }
        codes.values().removeIf(p -> uuid.equals(p.uuid()));
        if (removed > 0) {
            changes.incrementAndGet();
        }
        return removed;
    }

    /**
     * The player a session was linked as, found by the name stored with the session, ignoring case.
     * Null when no session carries the name or when two different players share it.
     */
    public UUID playerByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.trim();
        UUID found = null;
        for (Session s : sessions.values()) {
            if (s.name() != null && s.name().equalsIgnoreCase(wanted)) {
                if (found != null && !found.equals(s.uuid())) {
                    return null;
                }
                found = s.uuid();
            }
        }
        return found;
    }

    public boolean toggleHidden(UUID uuid) {
        boolean nowHidden;
        if (hidden.remove(uuid)) {
            nowHidden = false;
        } else {
            hidden.add(uuid);
            nowHidden = true;
        }
        changes.incrementAndGet();
        return nowHidden;
    }

    public boolean isHidden(UUID uuid) {
        return hidden.contains(uuid);
    }

    /** The players on the hide list, as a copy. */
    public Set<UUID> hiddenPlayers() {
        return Set.copyOf(hidden);
    }

    public int sessionCount() {
        return sessions.size();
    }

    /**
     * Reads sessions.json and hidden.json. A version 1 sessions file holds plain tokens. Each one is
     * hashed here and the file is written back in the hashed format straight away, so the plain
     * tokens do not stay on disk until the next periodic save.
     */
    public void load() {
        boolean migrated = false;
        try {
            if (Files.isRegularFile(sessionsFile)) {
                JsonElement root = JsonParser.parseString(Files.readString(sessionsFile, StandardCharsets.UTF_8));
                JsonArray list;
                if (root.isJsonArray()) {
                    list = root.getAsJsonArray();
                    migrated = true;
                } else {
                    list = root.getAsJsonObject().getAsJsonArray("sessions");
                }
                for (JsonElement e : list) {
                    try {
                        JsonObject o = e.getAsJsonObject();
                        String key = migrated ? Json.str(o, "token") : Json.str(o, "hash");
                        if (key == null || key.isEmpty()) {
                            continue;
                        }
                        if (migrated) {
                            key = hash(key);
                        } else if (!isHash(key)) {
                            continue;
                        }
                        Session s = new Session(key, UUID.fromString(Json.str(o, "uuid")), Json.str(o, "name"),
                                (long) Json.num(o, "createdAt", 0), (long) Json.num(o, "lastSeen", 0));
                        sessions.put(key, s);
                    } catch (RuntimeException ignored) {
                        // A damaged entry only costs that browser a relink.
                    }
                }
            }
            if (Files.isRegularFile(hiddenFile)) {
                JsonElement root = JsonParser.parseString(Files.readString(hiddenFile, StandardCharsets.UTF_8));
                for (JsonElement e : root.getAsJsonArray()) {
                    hidden.add(UUID.fromString(e.getAsString()));
                }
            }
        } catch (Exception ignored) {
            // A damaged file only costs players a relink.
        }
        if (migrated) {
            changes.incrementAndGet();
            try {
                save();
            } catch (java.io.IOException ignored) {
                // The periodic save tries again.
            }
        }
    }

    public synchronized void save() throws java.io.IOException {
        long version = changes.get();
        if (version == saved) {
            return;
        }
        JsonArray list = new JsonArray();
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) {
            if (now - s.lastSeen() > SESSION_TTL_MS) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("hash", s.hash());
            o.addProperty("uuid", s.uuid().toString());
            o.addProperty("name", s.name());
            o.addProperty("createdAt", s.createdAt());
            o.addProperty("lastSeen", s.lastSeen());
            list.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", SESSIONS_FORMAT);
        root.add("sessions", list);
        Json.writeAtomic(sessionsFile, Json.GSON.toJson(root));
        JsonArray h = new JsonArray();
        hidden.forEach(u -> h.add(u.toString()));
        Json.writeAtomic(hiddenFile, Json.GSON.toJson(h));
        saved = version;
    }
}
