package dev.ryvrook.atlas.command;

import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.AtlasPlugin;
import dev.ryvrook.atlas.markers.MarkerService;
import dev.ryvrook.atlas.timeline.EventLog;
import dev.ryvrook.atlas.timeline.TimelineCommands;

import java.util.List;
import java.util.UUID;

import static dev.ryvrook.atlas.command.AtlasCommand.bad;
import static dev.ryvrook.atlas.command.AtlasCommand.good;
import static dev.ryvrook.atlas.command.AtlasCommand.say;

/** Staff commands added in Atlas 1.0, hide and show for other players, admin markers and the timeline. */
final class StaffCommands {
    private StaffCommands() {
    }

    /** A player found by name or UUID, online or known from a linked browser or the last seen list. */
    record Target(UUID uuid, String name) {
    }

    /** Finds a player by UUID, online name, linked browser name or last seen name. Null when nobody matches. */
    static Target resolve(AtlasPlugin plugin, String input) {
        String raw = input == null ? "" : input.trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return new Target(UUID.fromString(raw), raw);
        } catch (IllegalArgumentException ignored) {
            // A name, looked up below.
        }
        Universe u = Universe.get();
        PlayerRef ref = u == null ? null : u.getPlayerByUsername(raw, NameMatching.EXACT_IGNORE_CASE);
        if (ref != null) {
            return new Target(ref.getUuid(), ref.getUsername());
        }
        UUID uuid = plugin.atlas().links().playerByName(raw);
        if (uuid == null) {
            uuid = plugin.atlas().lastSeen().byName(raw);
        }
        return uuid == null ? null : new Target(uuid, raw);
    }

    /** True when the sender is the console or a player with the admin permission. Says why not otherwise. */
    static boolean staff(AtlasPlugin plugin, CommandContext context) {
        if (!context.isPlayer() || plugin.atlas().hasAdminPermission(context.sender().getUuid())) {
            return true;
        }
        bad(context, "You need the " + Atlas.ADMIN_PERMISSION + " permission for that.");
        return false;
    }

    /** /atlas hide player and /atlas show player, which change the saved hide list for someone else. */
    static final class SetHidden extends CommandBase {
        private final AtlasPlugin plugin;
        private final boolean hide;
        private final RequiredArg<String> player;

        SetHidden(AtlasPlugin plugin, boolean hide) {
            super(hide ? "Hide a player from non admins on the web map." : "Show a hidden player on the web map again.");
            this.plugin = plugin;
            this.hide = hide;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.player = withRequiredArg("player", "A player name or a player UUID", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext context) {
            if (!staff(plugin, context)) {
                return;
            }
            String input = context.get(player);
            Target t = resolve(plugin, input);
            if (t == null) {
                bad(context, "Atlas knows no player called " + input.trim() + ". Use the player UUID instead.");
                return;
            }
            boolean changed = plugin.atlas().setHiddenPlayer(t.uuid(), hide);
            plugin.runAsync(plugin::saveQuietly);
            if (!changed) {
                say(context, t.name() + (hide ? " is already hidden on the web map." : " is not on the hide list."));
                return;
            }
            good(context, hide ? t.name() + " is now hidden from non admins on the web map."
                    : t.name() + " is visible on the web map again.");
            String other = plugin.atlas().hiddenReason(t.uuid());
            if (!hide && other != null) {
                say(context, t.name() + " stays hidden for another reason, " + other + ".");
            }
        }
    }

    /** /atlas show with no player, the counterpart of /atlas hide for yourself. */
    static final class ShowSelf extends CommandBase {
        private final AtlasPlugin plugin;

        ShowSelf(AtlasPlugin plugin) {
            super("show", "Show yourself on the web map again, or a player when you are staff.");
            this.plugin = plugin;
            requireNoPermission();
            addUsageVariant(new SetHidden(plugin, false));
        }

        @Override
        protected void executeSync(CommandContext context) {
            if (!context.isPlayer()) {
                bad(context, "Name a player, as in /atlas show <player>.");
                return;
            }
            boolean changed = plugin.atlas().setHiddenPlayer(context.sender().getUuid(), false);
            plugin.runAsync(plugin::saveQuietly);
            say(context, changed ? "You are visible on the web map again." : "You were not hidden on the web map.");
        }
    }

    // ---- markers --------------------------------------------------------------

    /** /atlas marker add, remove, list and area add. */
    static final class Marker extends CommandBase {
        Marker(AtlasPlugin plugin) {
            super("marker", "Add, remove and list the server markers on the web map.");
            requirePermission(Atlas.ADMIN_PERMISSION);
            addSubCommand(new MarkerAdd(plugin));
            addSubCommand(new MarkerRemove(plugin));
            addSubCommand(new MarkerList(plugin));
            addSubCommand(new Area(plugin));
        }

        @Override
        protected void executeSync(CommandContext context) {
            say(context, "Use /atlas marker add <world> <id> <x> <z> <label>, remove <world> <id>, list <world> "
                    + "or area add <world> <id> <x1> <z1> <x2> <z2> <label>.");
        }
    }

    static final class MarkerAdd extends CommandBase {
        private final AtlasPlugin plugin;
        private final RequiredArg<String> world;
        private final RequiredArg<String> id;
        private final RequiredArg<Double> x;
        private final RequiredArg<Double> z;
        private final RequiredArg<String> label;

        MarkerAdd(AtlasPlugin plugin) {
            super("add", "Add a server marker, or move the one with the same id.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.world = withRequiredArg("world", "The world name", ArgTypes.STRING);
            this.id = withRequiredArg("id", "A short id such as market", ArgTypes.STRING);
            this.x = withRequiredArg("x", "Block x", ArgTypes.DOUBLE);
            this.z = withRequiredArg("z", "Block z", ArgTypes.DOUBLE);
            this.label = withRequiredArg("label", "The text shown on the map", ArgTypes.GREEDY_STRING);
        }

        @Override
        protected void executeSync(CommandContext context) {
            String w = context.get(world);
            String markerId = context.get(id);
            double bx = context.get(x);
            double bz = context.get(z);
            String text = context.get(label);
            if (!knownWorld(plugin, context, w)) {
                return;
            }
            plugin.runAsync(() -> {
                try {
                    plugin.atlas().markers().adminPoint(w, markerId, bx, bz, text);
                    good(context, "Placed the server marker " + markerId + " at " + Math.round(bx) + " " + Math.round(bz)
                            + " in " + w + ".");
                    if (plugin.atlas().markers().adminTimelineVisible()) {
                        EventLog.record(w, System.currentTimeMillis(), "marker.add", bx, bz, null,
                                "A server marker was placed, " + text + ".");
                    }
                } catch (MarkerService.AdminProblem e) {
                    bad(context, e.getMessage());
                }
            });
        }
    }

    static final class MarkerRemove extends CommandBase {
        private final AtlasPlugin plugin;
        private final RequiredArg<String> world;
        private final RequiredArg<String> id;

        MarkerRemove(AtlasPlugin plugin) {
            super("remove", "Remove a server marker or area.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.world = withRequiredArg("world", "The world name", ArgTypes.STRING);
            this.id = withRequiredArg("id", "The marker id", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext context) {
            String w = context.get(world);
            String markerId = context.get(id);
            plugin.runAsync(() -> {
                try {
                    if (plugin.atlas().markers().adminRemove(w, markerId)) {
                        good(context, "Removed the server marker " + markerId + " from " + w + ".");
                        if (plugin.atlas().markers().adminTimelineVisible()) {
                            EventLog.record(w, System.currentTimeMillis(), "marker.remove", null, null, null,
                                    "A server marker was removed.");
                        }
                    } else {
                        bad(context, "There is no server marker " + markerId + " in " + w + ".");
                    }
                } catch (MarkerService.AdminProblem e) {
                    bad(context, e.getMessage());
                }
            });
        }
    }

    static final class MarkerList extends CommandBase {
        private final AtlasPlugin plugin;
        private final RequiredArg<String> world;

        MarkerList(AtlasPlugin plugin) {
            super("list", "List the server markers of a world.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.world = withRequiredArg("world", "The world name", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext context) {
            String w = context.get(world);
            List<String> lines = plugin.atlas().markers().adminList(w);
            if (lines.isEmpty()) {
                say(context, w + " has no server markers.");
                return;
            }
            good(context, w + " has " + lines.size() + (lines.size() == 1 ? " server marker." : " server markers."));
            int shown = 0;
            for (String line : lines) {
                if (++shown > 50) {
                    say(context, "And " + (lines.size() - 50) + " more in markers/admin.json.");
                    break;
                }
                say(context, line);
            }
        }
    }

    static final class Area extends CommandBase {
        Area(AtlasPlugin plugin) {
            super("area", "Add a rectangle to the server markers.");
            requirePermission(Atlas.ADMIN_PERMISSION);
            addSubCommand(new AreaAdd(plugin));
        }

        @Override
        protected void executeSync(CommandContext context) {
            say(context, "Use /atlas marker area add <world> <id> <x1> <z1> <x2> <z2> <label>.");
        }
    }

    static final class AreaAdd extends CommandBase {
        private final AtlasPlugin plugin;
        private final RequiredArg<String> world;
        private final RequiredArg<String> id;
        private final RequiredArg<Double> x1;
        private final RequiredArg<Double> z1;
        private final RequiredArg<Double> x2;
        private final RequiredArg<Double> z2;
        private final RequiredArg<String> label;

        AreaAdd(AtlasPlugin plugin) {
            super("add", "Add a rectangle between two corners, or replace the one with the same id.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.world = withRequiredArg("world", "The world name", ArgTypes.STRING);
            this.id = withRequiredArg("id", "A short id such as market_square", ArgTypes.STRING);
            this.x1 = withRequiredArg("x1", "Block x of one corner", ArgTypes.DOUBLE);
            this.z1 = withRequiredArg("z1", "Block z of one corner", ArgTypes.DOUBLE);
            this.x2 = withRequiredArg("x2", "Block x of the opposite corner", ArgTypes.DOUBLE);
            this.z2 = withRequiredArg("z2", "Block z of the opposite corner", ArgTypes.DOUBLE);
            this.label = withRequiredArg("label", "The text shown on the map", ArgTypes.GREEDY_STRING);
        }

        @Override
        protected void executeSync(CommandContext context) {
            String w = context.get(world);
            String markerId = context.get(id);
            double ax = context.get(x1);
            double az = context.get(z1);
            double bx = context.get(x2);
            double bz = context.get(z2);
            String text = context.get(label);
            if (!knownWorld(plugin, context, w)) {
                return;
            }
            plugin.runAsync(() -> {
                try {
                    plugin.atlas().markers().adminArea(w, markerId, ax, az, bx, bz, text);
                    good(context, "Placed the server area " + markerId + " in " + w + ".");
                    if (plugin.atlas().markers().adminTimelineVisible()) {
                        EventLog.record(w, System.currentTimeMillis(), "marker.add", (ax + bx) / 2, (az + bz) / 2, null,
                                "A server area was marked, " + text + ".");
                    }
                } catch (MarkerService.AdminProblem e) {
                    bad(context, e.getMessage());
                }
            });
        }
    }

    /** Refuses a world the engine has not loaded, so a typo does not land in admin.json. */
    private static boolean knownWorld(AtlasPlugin plugin, CommandContext context, String world) {
        if (plugin.atlas().worldExists(world)) {
            return true;
        }
        bad(context, "No loaded world is called " + world + ".");
        return false;
    }

    // ---- timeline -------------------------------------------------------------

    /** /atlas snapshot, every world, and /atlas snapshot world through its variant. */
    static final class Snapshot extends CommandBase {
        private final AtlasPlugin plugin;

        Snapshot(AtlasPlugin plugin) {
            super("snapshot", "Take a timeline snapshot of every world now, or of one world.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            addUsageVariant(new SnapshotWorld(plugin));
        }

        @Override
        protected void executeSync(CommandContext context) {
            TimelineCommands.snapshot(plugin, context, null);
        }
    }

    static final class SnapshotWorld extends CommandBase {
        private final AtlasPlugin plugin;
        private final RequiredArg<String> world;

        SnapshotWorld(AtlasPlugin plugin) {
            super("Take a timeline snapshot of one world now.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.world = withRequiredArg("world", "The world name", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext context) {
            TimelineCommands.snapshot(plugin, context, context.get(world));
        }
    }

    /** /atlas timeline status. */
    static final class Timeline extends CommandBase {
        Timeline(AtlasPlugin plugin) {
            super("timeline", "Look after the daily map snapshots.");
            requirePermission(Atlas.ADMIN_PERMISSION);
            addSubCommand(new TimelineStatus(plugin));
        }

        @Override
        protected void executeSync(CommandContext context) {
            say(context, "Use /atlas timeline status, or /atlas snapshot [world] to take a snapshot now.");
        }
    }

    static final class TimelineStatus extends CommandBase {
        private final AtlasPlugin plugin;

        TimelineStatus(AtlasPlugin plugin) {
            super("status", "Show the days held, the disk used and the next snapshot.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
        }

        @Override
        protected void executeSync(CommandContext context) {
            TimelineCommands.status(plugin, context);
        }
    }
}
