package dev.remgr.zulipbridge.mixin;

import dev.remgr.zulipbridge.ZulipBridgeClient;
import dev.remgr.zulipbridge.ZulipPollingThread;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts outgoing chat messages so we can relay them to Zulip.
 *
 * <p>The injection target is {@link ChatScreen#sendMessage(String, boolean)},
 * which is called right before the message is sent to the server. We hook
 * into its entry point so the message is always forwarded, even if the user
 * presses Enter on a command (commands are filtered out in
 * {@link ZulipPollingThread#sendToZulip}).
 */
@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    @Inject(method = "sendMessage", at = @At("HEAD"))
    private void onSendMessage(String chatText, boolean addToHistory, CallbackInfo ci) {
        // Only relay if the bridge is active and the text is not a command.
        if (!ZulipBridgeClient.isRunning()) return;
        if (chatText == null || chatText.isBlank()) return;
        if (chatText.startsWith("/")) return;

        var cfg = ZulipBridgeClient.CONFIG;

        // Determine the player display name.
        String name = cfg.playerDisplayName();
        if (name == null || name.isBlank()) {
            var player = MinecraftClient.getInstance().player;
            name = player != null ? player.getName().getString() : "Minecraft";
        }

        ZulipPollingThread.sendToZulip(cfg, "**" + name + "**: " + chatText);
    }
}
