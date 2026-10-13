package dev.ryvrook.atlas.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.AtlasPlugin;
import dev.ryvrook.atlas.auth.LinkService;
import dev.ryvrook.atlas.tiles.TileService;

import java.util.Map;
import java.util.UUID;

/** /atlas with link, hide, show, admin, reload, unlink, tiles, render, marker, snapshot and timeline. */
public final class AtlasCommand extends CommandBase {
    private static final String ACCENT = "#5fb3ff";
    private static final String TEXT = "#c9d4df";
    private static final String BAD = "#ff6b6b";

    private final AtlasPlugin plugin;

    public AtlasCommand(AtlasPlugin plugin) {
        super("atlas", "Live web map. Link your browser, hide yourself or get the address.");
        this.plugin = plugin;
        requireNoPermission();
        addAliases("webmap");
        addSubCommand(new Link(plugin));
        addSubCommand(new Hide(plugin));
        addSubCommand(new StaffCommands.ShowSelf(plugin));
        addSubCommand(new Admin(plugin));
        addSubCommand(new Reload(plugin));
        addSubCommand(new Unlink(plugin));
        addSubCommand(new Tiles(plugin));
        addSubCommand(new Render(plugin));
        addSubCommand(new StaffCommands.Marker(plugin));
        addSubCommand(new StaffCommands.Snapshot(plugin));
        addSubCommand(new StaffCommands.Timeline(plugin));
    }

    @Override
    protected void executeSync(CommandContext context) {
        String url = plugin.publicUrl();
        if (url != null) {
            context.sendMessage(Message.join(
                    Message.raw("Atlas ").color(ACCENT).bold(true),
                    Message.raw("is the live web map at ").color(TEXT),
                    Message.raw(url).color("#ffffff")));
        } else {
            context.sendMessage(Message.join(
                    Message.raw("Atlas ").color(ACCENT).bold(true),
                    Message.raw("is the live web map. " + noAddress(plugin)).color(TEXT)));
        }
        context.sendMessage(Message.raw("Use /atlas link to link your browser, /atlas hide and /atlas show to hide from the map or come back, /atlas admin for the address and your access.").color(TEXT));
    }

    /** Said when publicUrl is empty. Never prints the bind address, since 0.0.0.0 is no use to a player. */
    static String noAddress(AtlasPlugin plugin) {
        return "The server owner has not set a public address for it yet. It listens on port " + plugin.port() + ".";
    }

    static final class Link extends AbstractPlayerCommand {
        private final AtlasPlugin plugin;

        Link(AtlasPlugin plugin) {
            super("link", "Get a code that links your browser to your player.");
            this.plugin = plugin;
            requireNoPermission();
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef playerRef, World world) {
            Atlas atlas = plugin.atlas();
            String code = atlas.links().issueCode(playerRef.getUuid(), playerRef.getUsername());
            long minutes = LinkService.CODE_TTL_MS / 60_000;
            context.sendMessage(Message.join(
                    Message.raw("Your Atlas link code is ").color(TEXT),
                    Message.raw(code).color("#ffd166").bold(true),
                    Message.raw(" and it works for " + minutes + " minutes.").color(TEXT)));
            String url = plugin.publicUrl();
            if (url != null) {
                context.sendMessage(Message.join(
                        Message.raw("Open ").color(TEXT),
                        Message.raw(url).color("#ffffff"),
                        Message.raw(" and enter it in the link dialog.").color(TEXT)));
            } else {
                context.sendMessage(Message.raw("Open the map and enter it in the link dialog. " + noAddress(plugin)).color(TEXT));
            }
        }
    }

    static final class Hide extends AbstractPlayerCommand {
        private final AtlasPlugin plugin;

        Hide(AtlasPlugin plugin) {
            super("hide", "Hide or show yourself on the web map, or hide a player when you are staff.");
            this.plugin = plugin;
            requireNoPermission();
            addUsageVariant(new StaffCommands.SetHidden(plugin, true));
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef playerRef, World world) {
            boolean hidden = plugin.atlas().links().toggleHidden(playerRef.getUuid());
            // Save off the world thread so a slow disk never stalls the tick.
            plugin.runAsync(plugin::saveQuietly);
            context.sendMessage(Message.raw(hidden
                    ? "You are now hidden from the web map. Admins can still see you."
                    : "You are visible on the web map again.").color(TEXT));
        }
    }

    static final class Admin extends CommandBase {
        private final AtlasPlugin plugin;

        Admin(AtlasPlugin plugin) {
            super("admin", "Show the map address and whether you have admin access.");
            this.plugin = plugin;
            requireNoPermission();
        }

        @Override
        protected void executeSync(CommandContext context) {
            boolean up = plugin.server().running();
            String url = plugin.publicUrl();
            if (url != null) {
                context.sendMessage(Message.join(
                        Message.raw("Atlas runs at ").color(TEXT),
                        Message.raw(url).color("#ffffff"),
                        Message.raw(up ? " and the web server is up." : " but the web server is not running.")
                                .color(up ? TEXT : BAD)));
            } else {
                context.sendMessage(Message.join(
                        Message.raw("Atlas has no public address set. Put one in publicUrl in atlas.json. ").color(TEXT),
                        Message.raw(up ? "The web server is up on port " + plugin.port() + "."
                                : "The web server is not running.").color(up ? TEXT : BAD)));
            }
            boolean admin;
            if (context.isPlayer()) {
                CommandSender sender = context.sender();
                UUID uuid = sender.getUuid();
                admin = plugin.atlas().hasAdminPermission(uuid);
            } else {
                admin = true;
            }
            context.sendMessage(Message.raw(admin
                    ? "You have admin access. Linked browsers see every layer and can view the map as any faction."
                    : "You do not have admin access. Ask for the atlas.admin permission if you need it.").color(admin ? ACCENT : TEXT));
            if (!context.isPlayer()) {
                context.sendMessage(Message.raw("The admin token for scripts is in atlas.json under adminToken.").color(TEXT));
            }
        }
    }

    static final class Reload extends CommandBase {
        private final AtlasPlugin plugin;

        Reload(AtlasPlugin plugin) {
            super("reload", "Reload atlas.json and the Dominion snapshot.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
        }

        @Override
        protected void executeSync(CommandContext context) {
            String problem = plugin.reload();
            if (problem == null) {
                context.sendMessage(Message.raw("Atlas reloaded its settings and the faction snapshot.").color(ACCENT));
            } else {
                context.sendMessage(Message.raw("Atlas could not reload. " + problem).color(BAD));
            }
        }
    }

    /** Sends one line in the plain text colour. */
    static void say(CommandContext context, String text) {
        context.sendMessage(Message.raw(text).color(TEXT));
    }

    static void bad(CommandContext context, String text) {
        context.sendMessage(Message.raw(text).color(BAD));
    }

    static void good(CommandContext context, String text) {
        context.sendMessage(Message.raw(text).color(ACCENT));
    }

    /** /atlas unlink player, which signs every linked browser of that player out. */
    static final class Unlink extends CommandBase {
        private final AtlasPlugin plugin;
        private final RequiredArg<String> player;

        Unlink(AtlasPlugin plugin) {
            super("unlink", "Sign every linked browser of a player out of the web map.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.player = withRequiredArg("player", "A player name or a player UUID", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext context) {
            String input = context.get(player).trim();
            // Offline players are found by the name their browser was linked under, then by the last seen list.
            StaffCommands.Target target = StaffCommands.resolve(plugin, input);
            UUID uuid = target == null ? null : target.uuid();
            String name = target == null ? input : target.name();
            if (uuid == null) {
                bad(context, "Atlas knows no player called " + input + ". Use the player UUID instead.");
                return;
            }
            int removed = plugin.atlas().links().revokePlayer(uuid);
            plugin.runAsync(plugin::saveQuietly);
            if (removed == 0) {
                say(context, name + " had no linked browsers.");
            } else {
                good(context, "Signed " + removed + (removed == 1 ? " browser" : " browsers") + " of " + name
                        + " out of the web map.");
            }
        }
    }

    /** /atlas tiles purge and rerender. */
    static final class Tiles extends CommandBase {
        Tiles(AtlasPlugin plugin) {
            super("tiles", "Delete or redraw the saved map tiles of a world.");
            requirePermission(Atlas.ADMIN_PERMISSION);
            addSubCommand(new TilesPurge(plugin));
            addSubCommand(new TilesRerender(plugin));
        }

        @Override
        protected void executeSync(CommandContext context) {
            say(context, "Use /atlas tiles purge <world> to delete the saved tiles of a world, or /atlas tiles rerender <world> to redraw every tile.");
        }
    }

    static final class TilesPurge extends CommandBase {
        private final AtlasPlugin plugin;
        private final RequiredArg<String> world;

        TilesPurge(AtlasPlugin plugin) {
            super("purge", "Delete every saved and cached tile of a world. They are drawn again when needed.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.world = withRequiredArg("world", "The world name", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext context) {
            purge(plugin, context, context.get(world));
        }
    }

    static void purge(AtlasPlugin plugin, CommandContext context, String world) {
        TileService tiles = plugin.atlas().tiles();
        plugin.runAsync(() -> {
            if (tiles.purge(world)) {
                good(context, "Deleted the map tiles of " + world + ". They are drawn again on request and while the map is idle.");
            } else {
                bad(context, "Atlas knows no world called " + world + ".");
            }
        });
    }

    static final class TilesRerender extends CommandBase {
        private final AtlasPlugin plugin;
        private final RequiredArg<String> world;

        TilesRerender(AtlasPlugin plugin) {
            super("rerender", "Mark every explored tile of a world dirty so it is drawn again from fresh chunk images.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.world = withRequiredArg("world", "The world name", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext context) {
            String name = context.get(world);
            TileService tiles = plugin.atlas().tiles();
            plugin.runAsync(() -> {
                int n = tiles.rerender(name);
                if (n < 0) {
                    bad(context, "No loaded world is called " + name + ".");
                } else {
                    good(context, "Marked " + n + " explored chunks of " + name + " dirty. Tiles are drawn again in the background.");
                }
            });
        }
    }

    /** /atlas render status, radius, pause, resume, cancel and purge. */
    static final class Render extends CommandBase {
        Render(AtlasPlugin plugin) {
            super("render", "Watch and steer the background tile renders.");
            requirePermission(Atlas.ADMIN_PERMISSION);
            addSubCommand(new RenderStatus(plugin));
            addSubCommand(new RenderRadius(plugin));
            addSubCommand(new RenderSimple(plugin, "pause", "Hold the background renders. Browsers still get tiles drawn on request."));
            addSubCommand(new RenderSimple(plugin, "resume", "Continue the background renders."));
            addSubCommand(new RenderSimple(plugin, "cancel", "Drop every queued background render."));
            addSubCommand(new RenderPurge(plugin));
        }

        @Override
        protected void executeSync(CommandContext context) {
            say(context, "Use /atlas render status, radius <world> <x> <z> <chunks>, pause, resume, cancel or purge <world>.");
        }
    }

    static final class RenderStatus extends CommandBase {
        private final AtlasPlugin plugin;

        RenderStatus(AtlasPlugin plugin) {
            super("status", "Show the background render queue and the chunk image budget.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
        }

        @Override
        protected void executeSync(CommandContext context) {
            TileService tiles = plugin.atlas().tiles();
            TileService.RenderStatus st = tiles.renderStatus();
            good(context, "Atlas renders are " + (st.paused() ? "paused" : "running") + ". " + st.queued() + " queued, "
                    + st.running() + " running, " + st.done() + " done since start.");
            int left = tiles.budgetLeft();
            say(context, left < 0 ? "The chunk image budget is unlimited."
                    : "The chunk image budget has " + left + " of " + tiles.budgetPerMinute() + " per minute left.");
            long idle = (System.currentTimeMillis() - tiles.lastTileRequestAt()) / 1000;
            say(context, "The last tile request was " + idle + " seconds ago.");
            Map<String, Integer> explored = tiles.exploredCounts();
            for (Map.Entry<String, Integer> e : explored.entrySet()) {
                int queued = st.perWorld().getOrDefault(e.getKey(), 0);
                say(context, e.getKey() + " has " + e.getValue() + " explored chunks and " + queued + " queued renders.");
            }
            if (tiles.diskWriteFailures() > 0) {
                bad(context, tiles.diskWriteFailures() + " tile writes failed since start. See the server log.");
            }
        }
    }

    static final class RenderRadius extends CommandBase {
        private final AtlasPlugin plugin;
        private final RequiredArg<String> world;
        private final RequiredArg<Integer> x;
        private final RequiredArg<Integer> z;
        private final RequiredArg<Integer> chunks;

        RenderRadius(AtlasPlugin plugin) {
            super("radius", "Redraw the explored tiles within a radius of chunks around a block position.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.world = withRequiredArg("world", "The world name", ArgTypes.STRING);
            this.x = withRequiredArg("x", "Block x", ArgTypes.INTEGER);
            this.z = withRequiredArg("z", "Block z", ArgTypes.INTEGER);
            this.chunks = withRequiredArg("chunks", "Radius in chunks, at most " + TileService.MAX_RADIUS_CHUNKS, ArgTypes.INTEGER);
        }

        @Override
        protected void executeSync(CommandContext context) {
            String name = context.get(world);
            int bx = context.get(x);
            int bz = context.get(z);
            int r = context.get(chunks);
            if (r < 0 || r > TileService.MAX_RADIUS_CHUNKS) {
                bad(context, "The radius must be between 0 and " + TileService.MAX_RADIUS_CHUNKS + " chunks.");
                return;
            }
            TileService tiles = plugin.atlas().tiles();
            plugin.runAsync(() -> {
                int n = tiles.renderRadius(name, bx, bz, r);
                if (n < 0) {
                    bad(context, "No loaded world is called " + name + ".");
                } else if (n == 0) {
                    say(context, "No explored chunks lie within " + r + " chunks of " + bx + " " + bz + " in " + name + ".");
                } else {
                    good(context, "Marked " + n + " explored chunks around " + bx + " " + bz + " in " + name + " for a redraw.");
                }
            });
        }
    }

    static final class RenderSimple extends CommandBase {
        private final AtlasPlugin plugin;
        private final String action;

        RenderSimple(AtlasPlugin plugin, String action, String description) {
            super(action, description);
            this.plugin = plugin;
            this.action = action;
            requirePermission(Atlas.ADMIN_PERMISSION);
        }

        @Override
        protected void executeSync(CommandContext context) {
            TileService tiles = plugin.atlas().tiles();
            switch (action) {
                case "pause" -> {
                    tiles.pause();
                    good(context, "Background renders are paused. Use /atlas render resume to continue.");
                }
                case "resume" -> {
                    tiles.resume();
                    good(context, "Background renders continue.");
                }
                default -> {
                    int n = tiles.cancel();
                    good(context, "Dropped " + n + " queued background renders. Renders that already run finish.");
                }
            }
        }
    }

    static final class RenderPurge extends CommandBase {
        private final AtlasPlugin plugin;
        private final RequiredArg<String> world;

        RenderPurge(AtlasPlugin plugin) {
            super("purge", "Drop the queued background renders of one world.");
            this.plugin = plugin;
            requirePermission(Atlas.ADMIN_PERMISSION);
            this.world = withRequiredArg("world", "The world name", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext context) {
            String name = context.get(world);
            int n = plugin.atlas().tiles().purgeQueue(name);
            good(context, "Dropped " + n + " queued background renders of " + name + ".");
        }
    }
}
