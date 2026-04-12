package dev.remgr.zulipbridge;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

public final class ZulipBridgeClientCommands {

    private ZulipBridgeClientCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = ClientCommandManager.literal("zulip")
                    .executes(context -> {
                        ZulipBridgeCommandHandler.showHelp(context.getSource().getClient(), "zulip");
                        return Command.SINGLE_SUCCESS;
                    });

            root.then(ClientCommandManager.literal("help").executes(context -> {
                ZulipBridgeCommandHandler.showHelp(context.getSource().getClient(), "zulip");
                return Command.SINGLE_SUCCESS;
            }));

            root.then(ClientCommandManager.literal("test").executes(context -> {
                ZulipPollingThread.testConnection(ZulipBridgeClient.CONFIG,
                        (success, message) -> context.getSource().getClient().execute(
                                () -> ZulipBridgeCommandHandler.showLocalMessage(context.getSource().getClient(), message)));
                return Command.SINGLE_SUCCESS;
            }));

            root.then(ClientCommandManager.literal("whoami").executes(context -> {
                ZulipPollingThread.fetchOwnUserSummary(ZulipBridgeClient.CONFIG,
                        (success, message) -> context.getSource().getClient().execute(
                                () -> ZulipBridgeCommandHandler.showLocalMessage(context.getSource().getClient(), message)));
                return Command.SINGLE_SUCCESS;
            }));

            root.then(ClientCommandManager.literal("status").executes(context -> {
                ZulipBridgeCommandHandler.showStatus(context.getSource().getClient());
                return Command.SINGLE_SUCCESS;
            }));

            root.then(ClientCommandManager.literal("tree").executes(context -> {
                ZulipPollingThread.fetchTreeSummary(ZulipBridgeClient.CONFIG,
                        (success, message) -> context.getSource().getClient().execute(
                                () -> ZulipBridgeCommandHandler.showLocalMessage(context.getSource().getClient(), message)));
                return Command.SINGLE_SUCCESS;
            }));

            root.then(ClientCommandManager.literal("reload").executes(context -> {
                ZulipBridgeClient.reloadConfig();
                ZulipBridgeCommandHandler.showLocalMessage(context.getSource().getClient(), "Reloaded config from disk.");
                ZulipBridgeCommandHandler.showStatus(context.getSource().getClient());
                return Command.SINGLE_SUCCESS;
            }));

            root.then(ClientCommandManager.literal("enable").executes(context -> {
                ZulipBridgeClient.CONFIG.enabled(true);
                ZulipBridgeClient.saveConfig();
                ZulipBridgeClient.applyConfigState();
                ZulipBridgeCommandHandler.showLocalMessage(context.getSource().getClient(), "Bridge enabled.");
                return Command.SINGLE_SUCCESS;
            }));

            root.then(ClientCommandManager.literal("disable").executes(context -> {
                ZulipBridgeClient.CONFIG.enabled(false);
                ZulipBridgeClient.saveConfig();
                ZulipBridgeClient.applyConfigState();
                ZulipBridgeCommandHandler.showLocalMessage(context.getSource().getClient(), "Bridge disabled.");
                return Command.SINGLE_SUCCESS;
            }));

            root.then(ClientCommandManager.literal("openconfig").executes(context -> {
                ZulipBridgeCommandHandler.openConfig(context.getSource().getClient());
                return Command.SINGLE_SUCCESS;
            }));

            root.then(ClientCommandManager.literal("config").executes(context -> {
                ZulipBridgeCommandHandler.openConfig(context.getSource().getClient());
                return Command.SINGLE_SUCCESS;
            }));

            root.then(ClientCommandManager.literal("gui").executes(context -> {
                ZulipBridgeCommandHandler.openBridgeScreen(context.getSource().getClient());
                return Command.SINGLE_SUCCESS;
            }));

            var target = ClientCommandManager.literal("target")
                    .executes(context -> {
                        ZulipBridgeCommandHandler.showTarget(context.getSource().getClient());
                        return Command.SINGLE_SUCCESS;
                    });

            target.then(ClientCommandManager.literal("show").executes(context -> {
                ZulipBridgeCommandHandler.showTarget(context.getSource().getClient());
                return Command.SINGLE_SUCCESS;
            }));

            target.then(ClientCommandManager.literal("stream")
                    .then(ClientCommandManager.argument("stream", StringArgumentType.word())
                            .then(ClientCommandManager.argument("topic", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        ZulipBridgeCommandHandler.handleTargetCommand(
                                                context.getSource().getClient(),
                                                "stream " + StringArgumentType.getString(context, "stream")
                                                        + " " + StringArgumentType.getString(context, "topic")
                                        );
                                        return Command.SINGLE_SUCCESS;
                                    }))));

            target.then(ClientCommandManager.literal("dm")
                    .then(ClientCommandManager.argument("recipients", StringArgumentType.greedyString())
                            .executes(context -> {
                                ZulipBridgeCommandHandler.handleTargetCommand(
                                        context.getSource().getClient(),
                                        "dm " + StringArgumentType.getString(context, "recipients")
                                );
                                return Command.SINGLE_SUCCESS;
                            })));

            root.then(target);

            root.then(ClientCommandManager.literal("send")
                    .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                            .executes(context -> {
                                ZulipBridgeCommandHandler.handleSendCommand(
                                        context.getSource().getClient(),
                                        StringArgumentType.getString(context, "message")
                                );
                                return Command.SINGLE_SUCCESS;
                            })));

            dispatcher.register(ClientCommandManager.literal("s")
                    .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                            .executes(context -> {
                                ZulipBridgeCommandHandler.handleSendCommand(
                                        context.getSource().getClient(),
                                        StringArgumentType.getString(context, "message")
                                );
                                return Command.SINGLE_SUCCESS;
                            })));

            // /zulip preview <hash>
            root.then(ClientCommandManager.literal("preview")
                    .then(ClientCommandManager.argument("hash", StringArgumentType.word())
                            .executes(context -> {
                                String hash = StringArgumentType.getString(context, "hash");
                                var image = dev.remgr.zulipbridge.image.ImageCache.lookup(hash);
                                if (image == null) {
                                    ZulipBridgeCommandHandler.showLocalMessage(context.getSource().getClient(), "Image not loaded: " + hash);
                                } else {
                                    dev.remgr.zulipbridge.image.PreviewHud.show(hash);
                                }
                                return Command.SINGLE_SUCCESS;
                            })));

            dispatcher.register(root);
        });
    }
}
