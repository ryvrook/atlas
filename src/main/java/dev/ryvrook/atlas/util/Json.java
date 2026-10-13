package dev.ryvrook.atlas.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicLong;

/** Gson helpers. Gson ships inside the Hytale server jar so nothing is bundled. */
public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    public static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private Json() {
    }

    private static final AtomicLong TMP_COUNTER = new AtomicLong();
    /** Suffix of every temporary file writeAtomic creates, so leftovers of a crash can be found and removed. */
    public static final String TMP_SUFFIX = ".tmp";

    public static void writeAtomic(Path file, String text) throws IOException {
        writeAtomic(file, text.getBytes(StandardCharsets.UTF_8), -1);
    }

    /**
     * Writes a file so readers see the old or the new content and never a torn one. The bytes go
     * to a temporary file with a name no other writer uses, are flushed to the disk, and the file
     * is then moved over the target. A modified time of zero or more is set on the file before the
     * move, a negative one leaves the current time.
     */
    public static void writeAtomic(Path file, byte[] data, long modifiedAtMs) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = parent.resolve(file.getFileName() + "." + ProcessHandle.current().pid() + "-"
                + TMP_COUNTER.incrementAndGet() + TMP_SUFFIX);
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.wrap(data);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            if (modifiedAtMs >= 0) {
                Files.setLastModifiedTime(tmp, FileTime.fromMillis(modifiedAtMs));
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static String str(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        return e.toString();
    }

    public static double num(JsonObject o, String key, double def) {
        if (o == null) {
            return def;
        }
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return def;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isNumber()) {
            return p.getAsDouble();
        }
        try {
            return Double.parseDouble(p.getAsString());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    public static boolean bool(JsonObject o, String key) {
        if (o == null) {
            return false;
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && (e.getAsJsonPrimitive().isBoolean()
                ? e.getAsBoolean() : "true".equalsIgnoreCase(e.getAsString()));
    }

    public static JsonArray arr(JsonObject o, String key) {
        if (o == null) {
            return new JsonArray();
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    public static JsonObject obj(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}
