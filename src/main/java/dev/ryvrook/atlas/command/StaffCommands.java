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
import dev.ryvrook.atlas.api.MarkerEvent;
import dev.ryvrook.atlas.markers.MarkerService;
import dev.ryvrook.atlas.timeline.EventLog;
import dev.ryvrook.atlas.timeline.TimelineCommands;

import java.util.List;
import java.util.UUID;

import static dev.ryvrook.atlas.command.AtlasCommand.bad;
import static dev.ryvrook.atlas.command.AtlasCommand.good;
import static dev.ryvrook.atlas.command.AtlasCommand.say;

/**
 * Staff commands added in Atlas 1.0, hide and show for other players, admin markers, the timeline and
 * isometric views, and the key places of 1.2.
 */
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
                    long at = System.currentTimeMillis();
                    if (plugin.atlas().markers().adminTimelineVisible()) {
                        EventLog.record(w, at, "marker.add", bx, bz, null, "A server marker was placed, " + text + ".");
                    }
                    fire(plugin, MarkerEvent.Kind.ADDED, w, markerId, bx, bz, at);
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
                    double[] where = plugin.atlas().markers().adminRemove(w, markerId);
                    if (where != null) {
                        good(context, "Removed the server marker " + markerId + " from " + w + ".");
                        long at = System.currentTimeMillis();
                        if (plugin.atlas().markers().adminTimelineVisible()) {
                            EventLog.record(w, at, "marker.remove", null, null, null, "A server marker was removed.");
                        }
                        fire(plugin, MarkerEvent.Kind.REMOVED, w, markerId, where[0], where[1], at);
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
                    long at = System.currentTimeMillis();
                    if (plugin.atlas().markers().adminTimelineVisible()) {
                        EventLog.record(w, at, "marker.add", (ax + bx) / 2, (az + bz) / 2, null,
                                "A server area was marked, " + text + ".");
                    }
                    fire(plugin, MarkerEvent.Kind.ADDED, w, markerId, (ax + bx) / 2, (az + bz) / 2, at);
                } catch (MarkerService.AdminProblem e) {
                    bad(context, e.getMessage());
                }
            });
        }
    }

    /** Tells the plugins listening through AtlasApi.onMarker about a server marker. Staff markers have no author. */
    private static void fire(AtlasPlugin plugin, MarkerEvent.Kind kind, String world, String id, double x, double z,
                             long at) {
        plugin.atlas().markers().fire(new MarkerEvent(kind, null, world,
                MarkerService.ADMIN_PLUGIN + ":" + MarkerService.ADMIN_LAYER, id, x, z, at));
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

    // ---- isometric views --------------------------------------------------------

    /** /atlas iso world x z [radius] [scale] and /atlas iso status. */
    static final class Iso extends CommandBase {
        Iso(AtlasPlugin plugin) {
            super("iso", "Draw an isometric view of an area into the data folder.");
            requirePermission(Atlas.ADMIN_PERMISSION);
            addSubCommand(new IsoStatus(plugin));
            addUsageVariant(new IsoDraw(plugin, 0));
            addUsageVariant(new IsoDraw(plugin, 1));
            addUsageVariant(new IsoDraw(plugin, 2));
        }

        @Override
        protected void executeSync(CommandContext context) {
            say(context, "Use /atlas iso <world> <x> <z> [radius] [scale], or /atlas iso status.");
        }
    }

    static final class IsoDraw extends CommandBase {
        private final AtlasPlugin plugin;
        private final RequiredArg<String> world;
        private final RequiredArg<Integer> x;
        private final RequiredArg<Integer> z;
        private final RequiredArg<Integer> radius;
        private final RequiredArg<Integer> scale;

        IsoDraw(AtlasPlugin plugin, int extra) {
            super("Draw an isometric view around a block position.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.world = withRequiredArg("world", "The world name", ArgTypes.STRING);
            this.x = withRequiredArg("x", "Block x of the centre", ArgTypes.INTEGER);
            this.z = withRequiredArg("z", "Block z of the centre", ArgTypes.INTEGER);
            this.radius = extra >= 1 ? withRequiredArg("radius", "Radius in chunks, 4 by default", ArgTypes.INTEGER) : null;
            this.scale = extra >= 2 ? withRequiredArg("scale", "Pixels per block edge, 1 to 3, 2 by default", ArgTypes.INTEGER) : null;
        }

        @Override
        protected void executeSync(CommandContext context) {
            if (!staff(plugin, context)) {
                return;
            }
            String w = context.get(world);
            if (!knownWorld(plugin, context, w)) {
                return;
            }
            if (!plugin.atlas().config().worldEnabled(w)) {
                bad(context, "The world " + w + " is disabled in atlas.json.");
                return;
            }
            dev.ryvrook.atlas.iso.IsoService iso = plugin.iso();
            String r = radius == null ? null : Integer.toString(context.get(radius));
            String s = scale == null ? null : Integer.toString(context.get(scale));
            dev.ryvrook.atlas.iso.IsoView.Parsed p = dev.ryvrook.atlas.iso.IsoView.of(w, context.get(x), context.get(z), r, "0", s,
                    plugin.atlas().config().isoMaxRadius());
            if (p.view() == null) {
                bad(context, p.error());
                return;
            }
            if (!iso.anyAllowed(p.view())) {
                bad(context, "Nothing has been explored in that area of " + w + ".");
                return;
            }
            say(context, "Drawing the isometric view of " + w + " with radius " + p.view().radius() + " and scale "
                    + p.view().scale() + ". It runs in the background.");
            iso.render(p.view()).whenComplete((d, err) -> {
                if (err != null || d == null) {
                    bad(context, "The isometric view could not be drawn" + (err == null || err.getMessage() == null ? "."
                            : ", " + err.getMessage() + "."));
                    return;
                }
                try {
                    java.nio.file.Path file = iso.export(d);
                    good(context, "Saved " + d.width() + " by " + d.height() + " pixels in "
                            + d.ms() + " ms to " + file
                            + (d.partial() ? ". Some chunks were missing and show as grey floor." : "."));
                } catch (java.io.IOException e) {
                    bad(context, "The isometric view could not be saved, " + e.getMessage() + ".");
                }
            });
        }
    }

    static final class IsoStatus extends CommandBase {
        private final AtlasPlugin plugin;

        IsoStatus(AtlasPlugin plugin) {
            super("status", "Show the isometric render queue and caches.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
        }

        @Override
        protected void executeSync(CommandContext context) {
            for (String line : plugin.iso().status()) {
                say(context, line);
            }
        }
    }
    // ---- key places -------------------------------------------------------------

    /** /atlas key add, remove, list and render. */
    static final class Key extends CommandBase {
        Key(AtlasPlugin plugin) {
            super("key", "Pick the key places drawn from all four directions with the first snapshot of every day.");
            requirePermission(Atlas.ADMIN_PERMISSION);
            addSubCommand(new KeyAdd(plugin));
            addSubCommand(new KeyRemove(plugin));
            addSubCommand(new KeyList(plugin));
            addSubCommand(new KeyRender(plugin));
        }

        @Override
        protected void executeSync(CommandContext context) {
            say(context, "Use /atlas key add <world> <x> <z> <label> [radius] [name], remove <label>, list or render <label>.");
        }
    }

    /**
     * /atlas key add with world, x, z, label and then the radius and the name as one trailing text,
     * since a name may hold spaces. Its variant takes no radius and no name.
     */
    static final class KeyAdd extends CommandBase {
        private final AtlasPlugin plugin;
        private final RequiredArg<String> world;
        private final RequiredArg<Integer> x;
        private final RequiredArg<Integer> z;
        private final RequiredArg<String> label;
        private final RequiredArg<String> rest;

        KeyAdd(AtlasPlugin plugin) {
            super("add", "Add a key place, or move the one with the same label.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.world = withRequiredArg("world", "The world name", ArgTypes.STRING);
            this.x = withRequiredArg("x", "Block x of the centre", ArgTypes.INTEGER);
            this.z = withRequiredArg("z", "Block z of the centre", ArgTypes.INTEGER);
            this.label = withRequiredArg("label", "A short label such as town_hall", ArgTypes.STRING);
            this.rest = withRequiredArg("radius and name", "The radius in chunks, 1 to 16, then the name shown on the map",
                    ArgTypes.GREEDY_STRING);
            addUsageVariant(new KeyAddPlain(plugin));
        }

        @Override
        protected void executeSync(CommandContext context) {
            String text = context.get(rest).trim();
            int space = text.indexOf(' ');
            String first = space < 0 ? text : text.substring(0, space);
            int r;
            try {
                r = Integer.parseInt(first);
            } catch (NumberFormatException e) {
                bad(context, "Give the radius in chunks before the name, as in /atlas key add default 1280 416 town_hall 6 Town Hall.");
                return;
            }
            addKeyPlace(plugin, context, context.get(world), context.get(x), context.get(z), context.get(label), r,
                    space < 0 ? "" : text.substring(space + 1).trim());
        }
    }

    static final class KeyAddPlain extends CommandBase {
        private final AtlasPlugin plugin;
        private final RequiredArg<String> world;
        private final RequiredArg<Integer> x;
        private final RequiredArg<Integer> z;
        private final RequiredArg<String> label;

        KeyAddPlain(AtlasPlugin plugin) {
            super("Add a key place with the default radius of 6 and no name.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.world = withRequiredArg("world", "The world name", ArgTypes.STRING);
            this.x = withRequiredArg("x", "Block x of the centre", ArgTypes.INTEGER);
            this.z = withRequiredArg("z", "Block z of the centre", ArgTypes.INTEGER);
            this.label = withRequiredArg("label", "A short label such as town_hall", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext context) {
            addKeyPlace(plugin, context, context.get(world), context.get(x), context.get(z), context.get(label),
                    dev.ryvrook.atlas.config.AtlasConfig.KeyPlace.DEFAULT_RADIUS, "");
        }
    }

    private static void addKeyPlace(AtlasPlugin plugin, CommandContext context, String w, int x, int z, String l, int r, String n) {
        if (!staff(plugin, context) || !knownWorld(plugin, context, w)) {
            return;
        }
        if (!dev.ryvrook.atlas.config.AtlasConfig.validIsoLabel(l)) {
            bad(context, "The label must be 1 to 40 lower case letters, digits, dashes or underscores, such as town_hall.");
            return;
        }
        dev.ryvrook.atlas.config.AtlasConfig.KeyPlace p = new dev.ryvrook.atlas.config.AtlasConfig.KeyPlace(w, x, z, r, l, n);
        plugin.runAsync(() -> {
            try {
                boolean added = dev.ryvrook.atlas.iso.KeyPlaces.put(plugin.atlas(), p);
                good(context, (added ? "Added the key place " : "Moved the key place ") + p.displayName() + " to " + p.x + " "
                        + p.z + " in " + w + " with radius " + p.radius + ". It is drawn from all four directions with "
                        + "the first snapshot of every day. Run /atlas key render " + l + " to draw it now.");
            } catch (dev.ryvrook.atlas.iso.KeyPlaces.Problem e) {
                bad(context, e.getMessage());
            }
        });
    }

    static final class KeyRemove extends CommandBase {
        private final AtlasPlugin plugin;
        private final RequiredArg<String> label;

        KeyRemove(AtlasPlugin plugin) {
            super("remove", "Remove a key place. Its earlier pictures stay in the timeline.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.label = withRequiredArg("label", "The label of the key place", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext context) {
            if (!staff(plugin, context)) {
                return;
            }
            String l = context.get(label);
            plugin.runAsync(() -> {
                try {
                    if (dev.ryvrook.atlas.iso.KeyPlaces.remove(plugin.atlas(), l)) {
                        good(context, "Removed the key place " + l + ". Its earlier pictures stay in the timeline.");
                    } else {
                        bad(context, "There is no key place " + l + ". /atlas key list shows them.");
                    }
                } catch (dev.ryvrook.atlas.iso.KeyPlaces.Problem e) {
                    bad(context, e.getMessage());
                }
            });
        }
    }

    static final class KeyList extends CommandBase {
        private final AtlasPlugin plugin;

        KeyList(AtlasPlugin plugin) {
            super("list", "List the key places.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
        }

        @Override
        protected void executeSync(CommandContext context) {
            dev.ryvrook.atlas.config.AtlasConfig c = plugin.atlas().config();
            List<String> lines = dev.ryvrook.atlas.iso.KeyPlaces.lines(c);
            if (lines.isEmpty()) {
                say(context, "There are no key places yet, up to " + c.isoKeyPlaceLimit() + " are allowed. Add one with "
                        + "/atlas key add <world> <x> <z> <label> [radius] [name].");
                return;
            }
            good(context, lines.size() + " of " + c.isoKeyPlaceLimit() + " key places.");
            for (String line : lines) {
                say(context, line);
            }
        }
    }

    static final class KeyRender extends CommandBase {
        private final AtlasPlugin plugin;
        private final RequiredArg<String> label;

        KeyRender(AtlasPlugin plugin) {
            super("render", "Draw a key place from all four directions now.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.label = withRequiredArg("label", "The label of the key place", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext context) {
            if (!staff(plugin, context)) {
                return;
            }
            dev.ryvrook.atlas.config.AtlasConfig c = plugin.atlas().config();
            String l = context.get(label);
            dev.ryvrook.atlas.config.AtlasConfig.KeyPlace p = dev.ryvrook.atlas.iso.KeyPlaces.find(c, l);
            if (p == null) {
                bad(context, "There is no key place " + l + ". /atlas key list shows them.");
                return;
            }
            if (!c.isoEnabled()) {
                bad(context, "3D views are switched off with isoEnabled in atlas.json.");
                return;
            }
            if (!plugin.atlas().worldExists(p.world) || !c.worldEnabled(p.world)) {
                bad(context, "The world " + p.world + " of that key place is not loaded or is disabled.");
                return;
            }
            say(context, "Drawing " + p.displayName() + " from all four directions. It runs in the background.");
            plugin.isoSnapshots().renderNow(p).whenComplete((msg, err) -> {
                if (err != null || msg == null) {
                    bad(context, "The key place could not be drawn" + (err == null || err.getMessage() == null ? "."
                            : ", " + err.getMessage() + "."));
                } else {
                    good(context, msg);
                }
            });
        }
    }
}
