package dev.remgr.zulipbridge;

import dev.remgr.zulipbridge.config.ZulipBridgeConfig;
import dev.remgr.zulipbridge.config.ZulipBridgeConfigModel;
import io.wispforest.owo.config.ui.ConfigScreenProviders;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class ZulipBridgeCommandHandler {

    private static final String IMAGE_PREFIX_GLYPH = "\uE000";
    private static final Identifier IMAGE_PREFIX_FONT = Identifier.of("zulip-bridge", "zulip_prefix");
    private static final StyleSpriteSource IMAGE_PREFIX_FONT_SOURCE = new StyleSpriteSource.Font(IMAGE_PREFIX_FONT);

    private ZulipBridgeCommandHandler() {
    }

    public static void execute(MinecraftClient client, String remainder) {
        var cfg = ZulipBridgeClient.CONFIG;

        if (remainder.isBlank() || remainder.equals("help")) {
            showHelp(client, cfg.commandName().trim());
            return;
        }

        String[] parts = remainder.split("\\s+", 2);
        String subcommand = parts[0];
        String arguments = parts.length > 1 ? parts[1].trim() : "";

        switch (subcommand) {
            case "test" -> ZulipPollingThread.testConnection(cfg, localCallback(client));
            case "whoami" -> ZulipPollingThread.fetchOwnUserSummary(cfg, localCallback(client));
            case "status" -> showStatus(client);
            case "tree" -> ZulipPollingThread.fetchTreeSummary(cfg, localCallback(client));
            case "reload" -> {
                ZulipBridgeClient.reloadConfig();
                showLocalMessage(client, "Reloaded config from disk.");
                showStatus(client);
            }
            case "enable" -> {
                cfg.enabled(true);
                ZulipBridgeClient.saveConfig();
                ZulipBridgeClient.applyConfigState();
                showLocalMessage(client, "Bridge enabled.");
            }
            case "disable" -> {
                cfg.enabled(false);
                ZulipBridgeClient.saveConfig();
                ZulipBridgeClient.applyConfigState();
                showLocalMessage(client, "Bridge disabled.");
            }
            case "target" -> handleTargetCommand(client, arguments);
            case "send" -> handleSendCommand(client, arguments);
            case "openconfig", "config" -> openConfig(client);
            case "gui" -> openBridgeScreen(client);
            default -> showLocalMessage(client, "Unknown subcommand. Use /zulip help");
        }
    }

    public static void showLocalMessage(MinecraftClient client, String message) {
        if (client.inGameHud == null) return;
        client.inGameHud.getChatHud().addMessage(
                Text.empty()
                        .append(Text.literal(IMAGE_PREFIX_GLYPH).setStyle(Style.EMPTY.withFont(IMAGE_PREFIX_FONT_SOURCE)))
                        .append(Text.literal(" "))
                        .append(Text.literal(message))
        );
    }

    public static void showHelp(MinecraftClient client, String commandName) {
        showLocalMessage(client, "Usage: /" + commandName + " send <message>");
        showLocalMessage(client, "/s <message> - quick alias for /zulip send <message>");
        showLocalMessage(client, "/zulip test - verify Zulip auth and API access");
        showLocalMessage(client, "/zulip whoami - show authenticated Zulip account");
        showLocalMessage(client, "/zulip status - show bridge state");
        showLocalMessage(client, "/zulip tree - list channels and recent DMs");
        showLocalMessage(client, "/zulip reload - reload config from disk");
        showLocalMessage(client, "/zulip enable|disable - toggle bridge");
        showLocalMessage(client, "/zulip target show - show current Zulip target");
        showLocalMessage(client, "/zulip target stream <stream> <topic>");
        showLocalMessage(client, "/zulip target dm <email1,email2,...>");
        showLocalMessage(client, "/zulip gui - open the Zulip channel browser");
        showLocalMessage(client, "/zulip openconfig - open the config screen");
    }

    public static void showStatus(MinecraftClient client) {
        var cfg = ZulipBridgeClient.CONFIG;
        showLocalMessage(client, "Enabled: " + cfg.enabled() + ", Running: " + ZulipBridgeClient.isRunning());
        showLocalMessage(client, "Account: " + cfg.botEmail());
        showLocalMessage(client, "Command: /" + cfg.commandName().trim());
        showLocalMessage(client, "Outgoing target: " + describeTarget());
        showLocalMessage(client, "Incoming feed: all subscribed channels + DMs");
        showLocalMessage(client, "Self-echo suppressed: " + cfg.suppressSelfEcho()
                + ", Format: " + cfg.incomingMessageFormat());
        showLocalMessage(client, "Sound: " + cfg.playIncomingSound()
                + ", Toast: " + cfg.showIncomingToast());
        showLocalMessage(client, "Successful send notices: " + cfg.showSuccessfulSendMessages());
        showLocalMessage(client, "Outgoing MC name: " + cfg.prependPlayerDisplayNameToOutgoing());
    }

    public static void showTarget(MinecraftClient client) {
        showLocalMessage(client, "Current outgoing target: " + describeTarget());
    }

    public static String describeTarget() {
        var cfg = ZulipBridgeClient.CONFIG;
        return switch (cfg.messageTarget()) {
            case STREAM -> "stream " + cfg.streamName() + " > " + cfg.topicName();
            case DIRECT_MESSAGE -> "DM with " + cfg.directMessageRecipients();
        };
    }

    public static void openConfig(MinecraftClient client) {
        var provider = ConfigScreenProviders.get("zulip-bridge");
        if (provider == null) {
            showLocalMessage(client, "No config screen provider is registered.");
            return;
        }

        var parent = client.currentScreen;
        client.execute(() -> client.setScreen(provider.apply(parent)));
    }

    public static void openBridgeScreen(MinecraftClient client) {
        ZulipBridgeClient.openBridgeScreen(client);
    }

    public static void handleSendCommand(MinecraftClient client, String arguments) {
        var cfg = ZulipBridgeClient.CONFIG;

        if (arguments.isBlank()) {
            showLocalMessage(client, "Nothing to send. Use /zulip send <message>");
            return;
        }

        String validationError = validateConfig(cfg);
        if (validationError != null) {
            showLocalMessage(client, validationError);
            return;
        }

        String outgoingContent = cfg.prependPlayerDisplayNameToOutgoing()
                ? "**" + resolveOutgoingName(client, cfg) + "**: " + arguments
                : arguments;

        ZulipPollingThread.sendToZulip(cfg, outgoingContent, localSendCallback(client, cfg));
    }

    public static void handleTargetCommand(MinecraftClient client, String arguments) {
        var cfg = ZulipBridgeClient.CONFIG;

        if (arguments.isBlank() || arguments.equals("show")) {
            showTarget(client);
            return;
        }

        if (arguments.startsWith("stream ")) {
            String streamArgs = arguments.substring("stream ".length()).trim();
            String[] parts = streamArgs.split("\\s+", 2);
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                showLocalMessage(client, "Usage: /zulip target stream <stream> <topic>");
                return;
            }

            cfg.messageTarget(ZulipBridgeConfigModel.MessageTarget.STREAM);
            cfg.streamName(parts[0]);
            cfg.topicName(parts[1]);
            ZulipBridgeClient.saveConfig();
            ZulipBridgeClient.applyConfigState();
            showLocalMessage(client, "Target set to stream " + parts[0] + " > " + parts[1]);
            return;
        }

        if (arguments.startsWith("dm ")) {
            String recipients = arguments.substring("dm ".length()).trim();
            if (recipients.isBlank()) {
                showLocalMessage(client, "Usage: /zulip target dm <email1,email2,...>");
                return;
            }

            cfg.messageTarget(ZulipBridgeConfigModel.MessageTarget.DIRECT_MESSAGE);
            cfg.directMessageRecipients(recipients);
            ZulipBridgeClient.saveConfig();
            ZulipBridgeClient.applyConfigState();
            showLocalMessage(client, "Target set to DM with " + recipients);
            return;
        }

        showLocalMessage(client, "Usage: /zulip target [show|stream|dm]");
    }

    public static String validateConfig(ZulipBridgeConfig cfg) {
        if (cfg.zulipBaseUrl() == null || cfg.zulipBaseUrl().isBlank()) {
            return "Missing Zulip Base URL.";
        }
        if (cfg.botEmail() == null || cfg.botEmail().isBlank()) {
            return "Missing Account Email.";
        }
        if (cfg.botApiKey() == null || cfg.botApiKey().isBlank()) {
            return "Missing Account API Key.";
        }

        return switch (cfg.messageTarget()) {
            case STREAM -> {
                if (cfg.streamName() == null || cfg.streamName().isBlank()) yield "Missing Stream Name.";
                if (cfg.topicName() == null || cfg.topicName().isBlank()) yield "Missing Topic Name.";
                yield null;
            }
            case DIRECT_MESSAGE -> (cfg.directMessageRecipients() == null || cfg.directMessageRecipients().isBlank())
                    ? "Missing DM Recipients."
                    : null;
        };
    }

    private static String resolveOutgoingName(MinecraftClient client, ZulipBridgeConfig cfg) {
        String name = cfg.playerDisplayName();
        if (name != null && !name.isBlank()) return name;
        return client.player != null ? client.player.getName().getString() : "Minecraft";
    }

    private static java.util.function.BiConsumer<Boolean, String> localCallback(MinecraftClient client) {
        return (success, message) -> client.execute(() -> showLocalMessage(client, message));
    }

    private static java.util.function.BiConsumer<Boolean, String> localSendCallback(MinecraftClient client, ZulipBridgeConfig cfg) {
        return (success, message) -> {
            if (success && !cfg.showSuccessfulSendMessages()) return;
            client.execute(() -> showLocalMessage(client, message));
        };
    }
}
