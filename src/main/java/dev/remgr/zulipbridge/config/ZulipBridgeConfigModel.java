package dev.remgr.zulipbridge.config;

import io.wispforest.owo.config.annotation.Config;
import io.wispforest.owo.config.annotation.Modmenu;
import io.wispforest.owo.config.annotation.SectionHeader;
import io.wispforest.owo.config.annotation.RangeConstraint;

@Modmenu(modId = "zulip-bridge")
@Config(name = "zulip-bridge", wrapperName = "ZulipBridgeConfig")
public class ZulipBridgeConfigModel {

    public enum MessageTarget {
        STREAM,
        DIRECT_MESSAGE
    }

    public enum IncomingMessageFormat {
        PLAIN_TEXT,
        RAW_MARKDOWN
    }

    // ── Connection ────────────────────────────────────────────────────────────

    /** Base URL of your Zulip organisation, e.g. https://myorg.zulipchat.com */
    public String zulipBaseUrl = "https://your-org.zulipchat.com";

    /** Email address of the Zulip account used by this local client. */
    public String botEmail = "bridge-bot@your-org.zulipchat.com";

    /** API key for the Zulip account used by this local client. */
    public String botApiKey = "";

    // ── Target stream / topic ─────────────────────────────────────────────────

    @SectionHeader("stream")
    /** Zulip stream (channel) name to bridge. */
    public String streamName = "general";

    /** Zulip topic within the stream to bridge. */
    public String topicName = "minecraft";

    /** Where messages should be sent and polled from. */
    public MessageTarget messageTarget = MessageTarget.STREAM;

    /**
     * Comma-separated Zulip API email addresses for a direct-message
     * conversation. Do not include the current user's own email.
     */
    public String directMessageRecipients = "";

    // ── Behaviour ─────────────────────────────────────────────────────────────

    @SectionHeader("behaviour")
    /**
     * Local command name used to send to Zulip.
     * For example, "zulip" means the command is "/zulip send <message>".
     */
    public String commandName = "zulip";

    /** How often (in seconds) to poll Zulip for new messages. */
    @RangeConstraint(min = 1, max = 60)
    public int pollIntervalSeconds = 2;

    /** Whether the bridge is currently active. */
    public boolean enabled = true;

    /** Ignore incoming Zulip messages authored by the configured account. */
    public boolean suppressSelfEcho = true;

    /**
     * Player name shown in Zulip messages. Leave blank to use the
     * in-game profile name automatically.
     */
    public String playerDisplayName = "";

    /** Whether outgoing Zulip messages should be prefixed with the Minecraft player name. */
    public boolean prependPlayerDisplayNameToOutgoing = false;

    /** Prefix prepended to incoming Zulip messages in the Minecraft chat. */
    public String incomingPrefix = "[Zulip] ";

    /** Whether to show the Zulip prefix/badge on incoming messages. */
    public boolean showIncomingPrefix = true;

    /** Hex color for incoming sender names (for example, #3A9E5C). */
    public String senderColor = "#3A9E5C";

    /** Hex color for incoming message text (for example, #50C878). */
    public String messageColor = "#50C878";

    /** How incoming Zulip message content should be shown in Minecraft chat. */
    public IncomingMessageFormat incomingMessageFormat = IncomingMessageFormat.PLAIN_TEXT;

    /** Whether to play a local sound when a Zulip message arrives. */
    public boolean playIncomingSound = true;

    /** Whether to show a toast notification when a Zulip message arrives. */
    public boolean showIncomingToast = false;

    /** Whether successful sends should print a local confirmation line in chat. */
    public boolean showSuccessfulSendMessages = false;
}
