package dev.remgr.zulipbridge.mixin;

import dev.remgr.zulipbridge.ZulipBridgeClient;
import dev.remgr.zulipbridge.ZulipBridgeCommandHandler;
import dev.remgr.zulipbridge.image.PreviewHud;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts outgoing chat messages so we can relay them to Zulip.
 *
 * <p>The injection target is {@link ChatScreen#sendMessage(String, boolean)},
 * which is called right before the message is sent to the server. Messages are
 * only bridged through the local command syntax {@code /zulip send <message>}
 * using the configured command name.
 */
@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    @Inject(method = "mouseClicked(Lnet/minecraft/client/gui/Click;Z)Z", at = @At("HEAD"), cancellable = true)
    private void zulipBridge$blockClicksWhilePreviewOpen(Click click, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (!PreviewHud.isActive()) return;
        if (click.button() == 0) {
            PreviewHud.handleClick(click.x(), click.y());
        }
        cir.setReturnValue(true);
    }

    @Inject(method = "sendMessage(Ljava/lang/String;Z)V", at = @At("HEAD"), cancellable = true)
    private void onSendMessage(String chatText, boolean addToHistory, CallbackInfo ci) {
        if (chatText == null || chatText.isBlank()) return;

        var cfg = ZulipBridgeClient.CONFIG;
        String commandName = cfg.commandName();
        if (commandName == null || commandName.isBlank()) return;

        String trimmedCommandName = commandName.trim();
        if (trimmedCommandName.equals("zulip")) return;

        String commandPrefix = "/" + trimmedCommandName;
        if (!chatText.startsWith(commandPrefix)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        String remainder = chatText.substring(commandPrefix.length()).trim();

        if (addToHistory && client.inGameHud != null) {
            ChatHud chatHud = client.inGameHud.getChatHud();
            chatHud.addToMessageHistory(chatText);
        }
        client.setScreen(null);
        ci.cancel();
        ZulipBridgeCommandHandler.execute(client, remainder);
    }
}
