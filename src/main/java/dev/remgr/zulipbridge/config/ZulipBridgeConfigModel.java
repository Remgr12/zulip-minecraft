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
        RAW_MARKDOWN,
        MARKDOWN
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
    /** Default Zulip stream (channel) used for outgoing messages. */
    public String streamName = "general";

    /** Default Zulip topic used for outgoing stream messages. */
    public String topicName = "minecraft";

    /** Where outgoing messages should be sent from Minecraft. */
    public MessageTarget messageTarget = MessageTarget.STREAM;

    /**
     * Comma-separated Zulip API email addresses used when the outgoing
     * message target is direct message. Do not include your own email.
     */
    public String directMessageRecipients = "";

    // ── Behaviour ─────────────────────────────────────────────────────────────

    @SectionHeader("behaviour")
    /**
     * Local command name used to send to Zulip.
     * For example, "zulip" means the command is "/zulip send <message>".
     */
    public String commandName = "zulip";

    /** How long (in seconds) to wait before retrying after an event-stream error. */
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

    /** Whether to show the incoming Zulip text prefix on messages. */
    public boolean showIncomingPrefix = true;

    /** Hex color for incoming sender names (for example, #67FF67). */
    public String senderColor = "#67FF67";

    /** Hex color for incoming message text (for example, #B5FFB5). */
    public String messageColor = "#B5FFB5";

    /** How incoming Zulip message content should be shown in Minecraft chat. */
    public IncomingMessageFormat incomingMessageFormat = IncomingMessageFormat.MARKDOWN;

    /** Whether to play a local sound when a direct message or mention arrives. */
    public boolean playIncomingSound = true;

    /** Whether to show an upper-right toast notification when a direct message or mention arrives. */
    public boolean showIncomingToast = false;

    /** Whether successful sends should print a local confirmation line in chat. */
    public boolean showSuccessfulSendMessages = false;
}
