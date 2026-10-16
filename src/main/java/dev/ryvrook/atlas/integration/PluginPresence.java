package dev.ryvrook.atlas.integration;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * Asks the engine's plugin manager about other plugins by group and name. Never touches a class of the
 * plugin it looks for, so it is safe whether that plugin is installed or not, from any thread. Every look goes
 * through one {@link GatedLookup}, so a timer, the HTTP server or the dependents thread never waits for the
 * manager's lock during a stop or a reload.
 */
public final class PluginPresence {
    /** A loaded plugin object and its manifest version. */
    public record Found(Object identity, String version) {
    }

    private static final GatedLookup GATE = new GatedLookup(PluginPresence::managerLock, PluginPresence::serverStopping);
    private static volatile Field lockField;
    private static volatile boolean lockFieldMissing;

    private PluginPresence() {
    }

    /** The loaded plugin, in any state, or null when the plugin manager does not hold it. */
    public static Found find(String group, String name) {
        try {
            PluginBase p = plugin(new PluginIdentifier(group, name));
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

    /**
     * True when the plugin manager answers and holds no running copy of the plugin. False when it holds
     * one or when there is no plugin manager to ask, as in tests.
     */
    public static boolean missing(String group, String name) {
        try {
            if (PluginManager.get() == null) {
                return false;
            }
            PluginBase p = plugin(new PluginIdentifier(group, name));
            if (p == null) {
                return true;
            }
            PluginState state = p.getState();
            return state == PluginState.DISABLED || state == PluginState.FAILED;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * The plugin the manager holds under {@code id}, or null. Answers the last instance seen while the manager
     * holds its write lock or the server is stopping.
     */
    public static PluginBase plugin(PluginIdentifier id) {
        if (PluginManager.get() == null) {
            return null;
        }
        String key = (id.getGroup() + ":" + id.getName()).toLowerCase(Locale.ROOT);
        return GATE.find(key, () -> {
            PluginManager manager = PluginManager.get();
            return manager == null ? null : manager.getPlugin(id);
        });
    }

    /** True once the server is shutting down. */
    public static boolean serverStopping() {
        try {
            HytaleServer server = HytaleServer.get();
            return server != null && server.isShuttingDown();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * The plugin manager's private {@code lock} field, a ReentrantReadWriteLock in 0.7.1, read by reflection.
     * Null when the manager is not up or the field is not there, and then lookups are not gated.
     */
    static ReadWriteLock managerLock() {
        if (lockFieldMissing) {
            return null;
        }
        try {
            PluginManager manager = PluginManager.get();
            if (manager == null) {
                return null;
            }
            Field f = lockField;
            if (f == null) {
                f = PluginManager.class.getDeclaredField("lock");
                f.setAccessible(true);
                lockField = f;
            }
            return f.get(manager) instanceof ReadWriteLock rw ? rw : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            lockFieldMissing = true;
            return null;
        }
    }
}
