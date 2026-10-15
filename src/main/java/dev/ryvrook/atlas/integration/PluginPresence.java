package dev.ryvrook.atlas.integration;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;

/**
 * Asks the engine's plugin manager about other plugins by group and name. Never touches a class of the
 * plugin it looks for, so it is safe whether that plugin is installed or not, from any thread.
 */
public final class PluginPresence {
    /** A loaded plugin object and its manifest version. */
    public record Found(Object identity, String version) {
    }

    private PluginPresence() {
    }

    /** The loaded plugin, in any state, or null when the plugin manager does not hold it. */
    public static Found find(String group, String name) {
        try {
            PluginManager manager = PluginManager.get();
            if (manager == null) {
                return null;
            }
            PluginBase p = manager.getPlugin(new PluginIdentifier(group, name));
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
            PluginManager manager = PluginManager.get();
            if (manager == null) {
                return false;
            }
            PluginBase p = manager.getPlugin(new PluginIdentifier(group, name));
            if (p == null) {
                return true;
            }
            PluginState state = p.getState();
            return state == PluginState.DISABLED || state == PluginState.FAILED;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
