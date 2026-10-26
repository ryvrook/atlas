package dev.ryvrook.atlas.integration;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginState;
import dev.ryvrook.atlas.config.TrackerMode;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Reads who is vanished from VanishBridge, so a vanished player is left out of the live map, the online list and
 * the player counts as if they were offline. Leaderboards, stats and history are not touched. VanishBridge is
 * reached through its own class loader by name, so Atlas builds and runs without it, and a reload of
 * VanishBridge is picked up on the next refresh.
 *
 * <p>{@link #refresh()} runs on the Atlas scheduler before each player sample. {@link #isVanished} is safe from
 * any thread.
 */
public final class VanishLink {
    public static final String GROUP = "Ryvrook";
    public static final String NAME = "VanishBridge";
    private static final String API = "dev.ryvrook.vanishbridge.api.VanishBridgeApi";

    private final Supplier<TrackerMode> mode;
    private volatile Set<UUID> vanished = Set.of();
    private Object plugin;
    private Method read;

    public VanishLink(Supplier<TrackerMode> mode) {
        this.mode = mode;
    }

    public boolean isVanished(UUID uuid) {
        return uuid != null && vanished.contains(uuid);
    }

    /** True while VanishBridge is bound. */
    public boolean bound() {
        return read != null;
    }

    public void refresh() {
        if (mode.get() == TrackerMode.NEVER) {
            unbind();
            return;
        }
        try {
            PluginBase p = PluginPresence.plugin(new PluginIdentifier(GROUP, NAME));
            if (p == null || p.getState() != PluginState.ENABLED) {
                unbind();
                return;
            }
            if (p != plugin || read == null) {
                Class<?> api = Class.forName(API, true, p.getClass().getClassLoader());
                read = api.getMethod("vanished");
                plugin = p;
            }
            Object out = read.invoke(null);
            vanished = out instanceof Set<?> set ? copy(set) : Set.of();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            unbind();
        }
    }

    private static Set<UUID> copy(Set<?> set) {
        java.util.HashSet<UUID> ids = new java.util.HashSet<>();
        for (Object o : set) {
            if (o instanceof UUID u) {
                ids.add(u);
            }
        }
        return Set.copyOf(ids);
    }

    private void unbind() {
        plugin = null;
        read = null;
        vanished = Set.of();
    }
}
