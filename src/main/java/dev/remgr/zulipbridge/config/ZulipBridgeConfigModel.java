package dev.remgr.zulipbridge.config;

import io.wispforest.owo.config.annotation.Config;
import io.wispforest.owo.config.annotation.Modmenu;
import io.wispforest.owo.config.annotation.SectionHeader;
import io.wispforest.owo.config.annotation.RangeConstraint;

@Modmenu(modId = "zulip-bridge")
@Config(name = "zulip-bridge", wrapperName = "ZulipBridgeConfig")
public class ZulipBridgeConfigModel {

    // ── Connection ────────────────────────────────────────────────────────────

    /** Base URL of your Zulip organisation, e.g. https://myorg.zulipchat.com */
    public String zulipBaseUrl = "https://your-org.zulipchat.com";

    /** Email address of the Zulip bot (or your own account). */
    public String botEmail = "bridge-bot@your-org.zulipchat.com";

    /** API key for the bot account. Generate one at Settings → Your account. */
    public String botApiKey = "";

    // ── Target stream / topic ─────────────────────────────────────────────────

    @SectionHeader("stream")
    /** Zulip stream (channel) name to bridge. */
    public String streamName = "general";

    /** Zulip topic within the stream to bridge. */
    public String topicName = "minecraft";

    // ── Behaviour ─────────────────────────────────────────────────────────────

    @SectionHeader("behaviour")
    /** How often (in seconds) to poll Zulip for new messages. */
    @RangeConstraint(min = 1, max = 60)
    public int pollIntervalSeconds = 2;

    /** Whether the bridge is currently active. */
    public boolean enabled = true;

    /**
     * Player name shown in Zulip messages. Leave blank to use the
     * in-game profile name automatically.
     */
    public String playerDisplayName = "";

    /** Prefix prepended to incoming Zulip messages in the Minecraft chat. */
    public String incomingPrefix = "[Zulip] ";
}
