package dev.ryvrook.atlas.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Gson helpers. Gson ships inside the Hytale server jar so nothing is bundled. */
public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    public static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private Json() {
    }

    public static void writeAtomic(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
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
