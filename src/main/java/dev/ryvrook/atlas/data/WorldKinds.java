package dev.ryvrook.atlas.data;

import com.google.gson.JsonObject;
import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.builtin.portals.components.voidevent.config.VoidEventConfig;
import com.hypixel.hytale.builtin.portals.components.voidevent.config.VoidEventStage;
import com.hypixel.hytale.builtin.portals.resources.PortalWorld;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.portalworld.PortalType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.atlas.util.Text;

/**
 * Tells portal and instance worlds apart from ordinary worlds. Runs on the world thread.
 *
 * <p>What javap of 0.7.0 shows. PortalWorld is an entity store resource whose exists() is true when
 * its portal type id resolves to a PortalType asset. getRemainingSeconds returns -1 without a time
 * limit. getActiveVoidEventStage(store) reads the VoidEvent component of the void event entity and
 * has no side effects, unlike computeLatestStage which may start the void event resource. Stages
 * carry no index, so the stage number is counted with VoidEventConfig.forEachStage from the start
 * up to the active stage. InstanceWorldConfig.get(worldConfig) reads the plugin config map and
 * returns null for ordinary worlds.
 */
final class WorldKinds {
    private WorldKinds() {
    }

    static LiveState.WorldKind read(World world, Store<EntityStore> store) {
        String name = world.getName();
        try {
            if (store != null) {
                PortalWorld pw = store.getResource(PortalWorld.getResourceType());
                if (pw != null && pw.exists()) {
                    return portal(world, store, pw);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Portals plugin missing or resource not registered on this world.
        }
        try {
            InstanceWorldConfig ic = InstanceWorldConfig.get(world.getWorldConfig());
            if (ic != null) {
                String display = ic.getInstanceName();
                if (display == null || display.isBlank()) {
                    display = ic.getInstanceKey();
                }
                display = display == null || display.isBlank() ? name : Text.translateOrPretty(display);
                return new LiveState.WorldKind("instance", display, null);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Instances plugin missing.
        }
        return LiveState.WorldKind.plain(name);
    }

    private static LiveState.WorldKind portal(World world, Store<EntityStore> store, PortalWorld pw) {
        PortalType type = pw.getPortalType();
        String id = type == null ? null : type.getId();
        String typeName = null;
        try {
            var msg = type == null ? null : type.getDisplayName();
            if (msg != null) {
                typeName = msg.getRawText() != null && !msg.getRawText().isBlank()
                        ? msg.getRawText() : Text.translate(msg.getMessageId());
            }
        } catch (RuntimeException ignored) {
            // No description on this portal type.
        }
        if (typeName == null || typeName.isBlank()) {
            typeName = id == null ? world.getName() : id.replace('_', ' ');
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", id);
        o.addProperty("typeName", typeName);
        boolean limit = pw.hasTimeLimit();
        o.addProperty("timeLimit", limit);
        if (limit) {
            o.addProperty("remainingSeconds", round1(Math.max(0, pw.getRemainingSeconds(world))));
            o.addProperty("elapsedSeconds", round1(pw.getElapsedSeconds(world)));
        } else {
            o.add("remainingSeconds", com.google.gson.JsonNull.INSTANCE);
            o.add("elapsedSeconds", com.google.gson.JsonNull.INSTANCE);
        }
        Integer stage = null;
        boolean active = false;
        try {
            active = pw.isVoidEventActive();
            VoidEventStage current = pw.getActiveVoidEventStage(store);
            VoidEventConfig cfg = pw.getVoidEventConfig();
            if (current != null && cfg != null) {
                int[] count = {0};
                cfg.forEachStage(null, current, count, null, null, (c, a, b, s) -> c[0]++);
                stage = count[0] > 0 ? count[0] : null;
            }
        } catch (RuntimeException ignored) {
            // No void event on this portal.
        }
        if (stage == null) {
            o.add("voidStage", com.google.gson.JsonNull.INSTANCE);
        } else {
            o.addProperty("voidStage", stage);
        }
        o.addProperty("voidActive", active);
        int entries = 0;
        try {
            entries = pw.getSeesUi().size();
        } catch (RuntimeException ignored) {
            // Entry set not ready.
        }
        o.addProperty("entries", entries);
        return new LiveState.WorldKind("portal", typeName, o);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
