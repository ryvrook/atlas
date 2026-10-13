package dev.ryvrook.atlas.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

/**
 * Browser linking. /atlas link issues a six character code that lives five minutes. Posting it
 * to /api/link trades it for a long lived session token kept in a cookie. Sessions and the
 * list of players who ran /atlas hide are saved in the data directory.
 *
 * <p>Only a SHA-256 hash of each session token is kept, in memory and in sessions.json, so a copy
 * of the file cannot be used to sign in. Sessions are looked up by the hash of the cookie value.
 * Files from 0.2.2 and older held the plain tokens as a bare array. They are hashed once on load and
 * written back in the versioned format.
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

    public LinkService(Path dataDir) {
        this.sessionsFile = dataDir.resolve("sessions.json");
        this.hiddenFile = dataDir.resolve("hidden.json");
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

    /** IPv6 clients are limited per /64 so rotating addresses inside one network does not reset the limit. */
    public static String rateKey(String address) {
        if (address == null) {
            return "?";
        }
        if (address.indexOf(':') < 0) {
            return address;
        }
        String[] groups = address.split(":", -1);
        if (groups.length < 4) {
            return address;
        }
        return groups[0] + ":" + groups[1] + ":" + groups[2] + ":" + groups[3] + "::/64";
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
        return new Linked(token, s);
    }

    /** The session of a cookie value, or null. */
    public Session session(String token) {
        if (token == null || token.isEmpty() || token.length() > 200) {
            return null;
        }
        String key = hash(token);
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

    private static boolean isHash(String s) {
        if (s == null || s.length() != 64) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0 || Character.isUpperCase(s.charAt(i))) {
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
        if (token != null && !token.isEmpty() && token.length() <= 200 && sessions.remove(hash(token)) != null) {
            changes.incrementAndGet();
        }
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
