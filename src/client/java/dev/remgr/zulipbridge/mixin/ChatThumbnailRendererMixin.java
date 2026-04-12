package dev.remgr.zulipbridge.mixin;

import dev.remgr.zulipbridge.chat.ChatImageRegistry;
import dev.remgr.zulipbridge.chat.ChatImageThumbnail;
import dev.remgr.zulipbridge.image.ImageCache;
import dev.remgr.zulipbridge.image.ImageRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mixin(ChatHud.class)
public abstract class ChatThumbnailRendererMixin {
    @Shadow @Final private MinecraftClient client;
    @Shadow @Final private List<ChatHudLine.Visible> visibleMessages;
    @Shadow private int scrolledLines;

    @Shadow protected abstract int getLineHeight();
    @Shadow protected abstract double getChatScale();
    @Shadow public abstract int getVisibleLineCount();
    @Shadow protected abstract int getWidth();

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V", at = @At("TAIL"))
    private void zulipBridge$bindThumbnailLine(Text message, MessageSignatureData signatureData, MessageIndicator indicator, CallbackInfo ci) {
        String messageHash = ChatImageRegistry.takeMessageHashForDisplay(message);
        if (messageHash == null) return;

        int wrappedLineCount = new ChatHudLine(0, message, signatureData, indicator)
                .breakLines(this.client.textRenderer, this.getWidth())
                .size();
        int visibleLineCount = Math.min(wrappedLineCount, this.visibleMessages.size());
        for (int i = 0; i < visibleLineCount; i++) {
            ChatHudLine.Visible visibleLine = this.visibleMessages.get(i);
            ChatImageRegistry.bindVisibleLine(visibleLine, messageHash);
            ChatImageRegistry.bindVisibleLineText(orderedTextToString(visibleLine.content()), messageHash);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;IIIZZ)V", at = @At("TAIL"))
    private void zulipBridge$renderThumbnails(
            DrawContext context,
            TextRenderer textRenderer,
            int currentTick,
            int mouseX,
            int mouseY,
            boolean interactable,
            boolean focused,
            CallbackInfo ci
    ) {
        double chatScale = this.getChatScale();
        int lineHeight = Math.max(1, (int) Math.round(this.getLineHeight() * chatScale));
        int visibleLineCount = Math.min(this.getVisibleLineCount(), Math.max(0, this.visibleMessages.size() - this.scrolledLines));
        int baseY = context.getScaledWindowHeight() - 40;
        Set<String> renderedMessages = new HashSet<>();
        Map<String, Integer> lineOccurrences = new HashMap<>();

        for (int i = 0; i < visibleLineCount; i++) {
            ChatHudLine.Visible visible = this.visibleMessages.get(i + this.scrolledLines);
            String messageHash = ChatImageRegistry.findMessageHashForVisibleLine(visible);
            String lineText = orderedTextToString(visible.content());
            int occurrenceIndex = lineOccurrences.getOrDefault(lineText, 0);
            lineOccurrences.put(lineText, occurrenceIndex + 1);
            if (messageHash == null) {
                messageHash = ChatImageRegistry.findMessageHashForLineText(lineText, occurrenceIndex);
            }
            if (messageHash == null || !renderedMessages.add(messageHash)) continue;

            List<ChatImageThumbnail> thumbnails = ChatImageRegistry.getForMessage(messageHash);
            if (thumbnails.isEmpty()) continue;

            int lineBottom = baseY - i * lineHeight;
            int x = 2;
            for (ChatImageThumbnail thumbnail : thumbnails) {
                ImageCache.CachedImage image = ImageCache.lookup(thumbnail.imageHash());
                if (image == null) continue;

                ImageRenderer.Size size = ImageRenderer.fit(image.width(), image.height(), 64, 64);
                int y = lineBottom - size.height() + lineHeight;
                ImageRenderer.draw(context, image, x, y, size);
                x += size.width() + 4;
            }
        }
    }

    private static String orderedTextToString(OrderedText orderedText) {
        StringBuilder builder = new StringBuilder();
        orderedText.accept((index, style, codePoint) -> {
            builder.appendCodePoint(codePoint);
            return true;
        });
        return builder.toString();
    }
}
