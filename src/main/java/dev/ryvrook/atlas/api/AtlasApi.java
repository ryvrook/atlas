package dev.ryvrook.atlas.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Atlas web map API for other plugins. Compile against the Atlas jar and list Atlas as an optional
 * dependency in your manifest. Register your layers from {@link #onEnable(Consumer)}, which runs at once
 * when Atlas is already up and again whenever Atlas starts, and stop using the API in
 * {@link #onDisable(Runnable)}. Both hooks stay registered across /atlas reload. A /plugin reload of Atlas
 * loads these classes again, so a plugin linked to the old ones has to be reloaded too.
 *
 * <pre>{@code
 * AtlasApi.onEnable(api -> api.layer("myplugin", "shops")
 *         .label("Shops")
 *         .point("default", "market", 120, -40).label("Market").icon("Coins"));
 * }</pre>
 *
 * <p>Every method is thread safe and never touches a world thread.
 */
public abstract class AtlasApi {
    private static final Logger LOG = Logger.getLogger("Atlas");
    private static volatile AtlasApi current;
    private static final List<Consumer<AtlasApi>> ENABLE = new CopyOnWriteArrayList<>();
    private static final List<Runnable> DISABLE = new CopyOnWriteArrayList<>();

    protected AtlasApi() {
    }

    /** The running API, or null when Atlas is not loaded or not started yet. */
    public static AtlasApi get() {
        return current;
    }

    /** Runs the hook now when Atlas is up, and every time Atlas starts. */
    public static void onEnable(Consumer<AtlasApi> hook) {
        Objects.requireNonNull(hook, "hook");
        ENABLE.add(hook);
        AtlasApi api = current;
        if (api != null) {
            run(() -> hook.accept(api));
        }
    }

    /** Runs the hook every time Atlas stops. Layers and markers are dropped with it. */
    public static void onDisable(Runnable hook) {
        Objects.requireNonNull(hook, "hook");
        DISABLE.add(hook);
    }

    /**
     * Removes hooks added with {@link #onEnable} and {@link #onDisable}, for a plugin that stops while Atlas
     * keeps running. Either may be null. Not in Atlas 1.6.0 and older.
     */
    public static void removeHooks(Consumer<AtlasApi> enable, Runnable disable) {
        if (enable != null) {
            ENABLE.remove(enable);
        }
        if (disable != null) {
            DISABLE.remove(disable);
        }
    }

    /** The layer with this id, made on first use. pluginId and layerId are 1 to 64 letters, digits or _ . : - */
    public abstract MarkerLayer layer(String pluginId, String layerId);

    /** Every layer, API, drop folder and admin layers alike. */
    public abstract List<MarkerLayer> layers();

    /** The layers that hold markers in a world. */
    public abstract List<MarkerLayer> layers(String world);

    /** Removes a layer made through the API with all its markers. True when it existed. */
    public abstract boolean removeLayer(String pluginId, String layerId);

    /**
     * Hides a player from everyone but admins and the player themself, as /atlas hide does, for as long as
     * Atlas runs. Meant for vanish plugins. The reason is shown to admins. Not saved, so call it again
     * after a restart.
     */
    public abstract void setHidden(UUID player, boolean hidden, String reason);

    /** True when the player is hidden on the map for any reason. */
    public abstract boolean isHidden(UUID player);

    /** Atlas internal. Publishes the API and runs the enable hooks. */
    protected static void enable(AtlasApi api) {
        Objects.requireNonNull(api, "api");
        current = api;
        for (Consumer<AtlasApi> hook : ENABLE) {
            run(() -> hook.accept(api));
        }
    }

    /** Atlas internal. Withdraws the API and runs the disable hooks. */
    protected static void disable(AtlasApi api) {
        if (current != api || api == null) {
            return;
        }
        current = null;
        for (Runnable hook : DISABLE) {
            run(hook);
        }
    }

    /** Atlas internal. A layer that reports its changes to the listener, or to nobody when it is null. */
    protected static MarkerLayer newLayer(String pluginId, String layerId, LayerListener listener) {
        return new MarkerLayer(pluginId, layerId, listener);
    }

    /** Atlas internal. Told about every change, with the world it happened in, or null for the whole layer. */
    protected interface LayerListener {
        void changed(MarkerLayer layer, String world);
    }

    private static void run(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "An Atlas API hook of another plugin failed", t);
        }
    }
}
