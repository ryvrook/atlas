package dev.ryvrook.atlas.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.atlas.Atlas;
import dev.ryvrook.atlas.AtlasPlugin;
import dev.ryvrook.atlas.auth.LinkService;

import java.util.UUID;

/** /atlas with link, hide, admin and reload. */
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
        addSubCommand(new Admin(plugin));
        addSubCommand(new Reload(plugin));
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
        context.sendMessage(Message.raw("Use /atlas link to link your browser, /atlas hide to hide from the map, /atlas admin for the address and your access.").color(TEXT));
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
            super("hide", "Hide or show yourself on the web map.");
            this.plugin = plugin;
            requireNoPermission();
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
}
