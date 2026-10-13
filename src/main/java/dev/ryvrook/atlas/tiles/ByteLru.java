package dev.ryvrook.atlas.tiles;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** A size bounded least recently used cache of encoded tiles. */
public final class ByteLru {
    public record Entry(byte[] data, boolean partial, long createdAt) {
    }

    private final LinkedHashMap<String, Entry> map = new LinkedHashMap<>(256, 0.75f, true);
    private long maxBytes;
    private long bytes;

    public ByteLru(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public synchronized void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
        trim();
    }

    public synchronized Entry get(String key) {
        return map.get(key);
    }

    public synchronized void put(String key, Entry e) {
        Entry old = map.put(key, e);
        if (old != null) {
            bytes -= old.data().length;
        }
        bytes += e.data().length;
        trim();
    }

    public synchronized void remove(String key) {
        Entry old = map.remove(key);
        if (old != null) {
            bytes -= old.data().length;
        }
    }

    public synchronized void removeIf(Predicate<String> keyTest) {
        Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> e = it.next();
            if (keyTest.test(e.getKey())) {
                bytes -= e.getValue().data().length;
                it.remove();
            }
        }
    }

    public synchronized long bytes() {
        return bytes;
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized long maxBytes() {
        return maxBytes;
    }

    private void trim() {
        Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
        while (bytes > maxBytes && it.hasNext()) {
            bytes -= it.next().getValue().data().length;
            it.remove();
        }
    }
}
