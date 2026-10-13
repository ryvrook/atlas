package dev.ryvrook.atlas.timeline;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import dev.ryvrook.atlas.AtlasPlugin;

/**
 * The bodies of /atlas snapshot [world] and /atlas timeline status. The sub commands themselves are
 * registered under command, they only call these. Both return at once, the snapshot reports back
 * through the context when it is done.
 */
public final class TimelineCommands {
    private static final String TEXT = "#c9d4df";
    private static final String BAD = "#ff6b6b";

    private TimelineCommands() {
    }

    /** /atlas snapshot [world]. A null or blank world snapshots every world the daily snapshot covers. */
    public static void snapshot(AtlasPlugin plugin, CommandContext context, String world) {
        Timeline t = plugin.timeline();
        if (t == null) {
            context.sendMessage(Message.raw("The timeline is not running.").color(BAD));
            return;
        }
        String w = world == null || world.isBlank() ? null : world.trim();
        String answer = t.requestSnapshot(w, text -> context.sendMessage(Message.raw(text).color(TEXT)));
        context.sendMessage(Message.raw(answer).color(TEXT));
    }

    /** /atlas timeline status. Days held, disk used and the next run. */
    public static void status(AtlasPlugin plugin, CommandContext context) {
        Timeline t = plugin.timeline();
        if (t == null) {
            context.sendMessage(Message.raw("The timeline is not running.").color(BAD));
            return;
        }
        // Summing the disk use walks the snapshot folder, so it runs on the Atlas scheduler.
        plugin.runAsync(() -> {
            for (String line : t.status()) {
                context.sendMessage(Message.raw(line).color(TEXT));
            }
        });
    }
}
