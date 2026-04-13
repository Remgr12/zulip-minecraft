package dev.remgr.zulipbridge.mixin;

import dev.remgr.zulipbridge.chat.ChatImageRegistry;
import dev.remgr.zulipbridge.image.ImageCache;
import dev.remgr.zulipbridge.image.ImageRenderer;
import dev.remgr.zulipbridge.text.InlineEmoji;
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

import java.util.List;

@Mixin(ChatHud.class)
public abstract class ChatInlineEmojiRendererMixin {
    @Shadow @Final private MinecraftClient client;
    @Shadow @Final private List<ChatHudLine.Visible> visibleMessages;
    @Shadow private int scrolledLines;

    @Shadow protected abstract int getLineHeight();
    @Shadow protected abstract double getChatScale();
    @Shadow public abstract int getVisibleLineCount();
    @Shadow protected abstract int getWidth();

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V", at = @At("TAIL"))
    private void zulipBridge$bindInlineEmojiLines(Text message, MessageSignatureData signatureData, MessageIndicator indicator, CallbackInfo ci) {
        String messageHash = ChatImageRegistry.takeMessageHashForDisplay(message);
        if (messageHash == null) return;

        List<OrderedText> wrappedLines = new ChatHudLine(0, message, signatureData, indicator)
                .breakLines(this.client.textRenderer, this.getWidth());
        int visibleLineCount = Math.min(wrappedLines.size(), this.visibleMessages.size());
        int emojiIndex = 0;
        for (int i = 0; i < visibleLineCount; i++) {
            ChatHudLine.Visible visibleLine = this.visibleMessages.get(i);
            String lineText = orderedTextToString(wrappedLines.get(i));
            ChatImageRegistry.bindVisibleLine(visibleLine, messageHash, emojiIndex);
            emojiIndex += InlineEmoji.countPlaceholders(lineText);
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;IIIZZ)V", at = @At("TAIL"))
    private void zulipBridge$renderInlineEmojis(DrawContext context, TextRenderer textRenderer, int currentTick, int mouseX, int mouseY, boolean interactable, boolean focused, CallbackInfo ci) {
        double chatScale = this.getChatScale();
        int lineHeight = Math.max(1, (int) Math.round(this.getLineHeight() * chatScale));
        int visibleLineCount = Math.min(this.getVisibleLineCount(), Math.max(0, this.visibleMessages.size() - this.scrolledLines));
        int baseY = context.getScaledWindowHeight() - 40;

        for (int i = 0; i < visibleLineCount; i++) {
            ChatHudLine.Visible visibleLine = this.visibleMessages.get(i + this.scrolledLines);
            String messageHash = ChatImageRegistry.findMessageHashForVisibleLine(visibleLine);
            if (messageHash == null) continue;

            String lineText = orderedTextToString(visibleLine.content());
            int emojiCount = InlineEmoji.countPlaceholders(lineText);
            if (emojiCount == 0) continue;

            List<String> lineEmojis = InlineEmoji.slice(
                    ChatImageRegistry.getInlineEmojis(messageHash),
                    ChatImageRegistry.findInlineEmojiStartForVisibleLine(visibleLine),
                    emojiCount
            );
            if (lineEmojis.isEmpty()) continue;

            int lineY = baseY - (i + 1) * lineHeight + 1;
            int emojiOffset = 0;
            for (int charIndex = 0; charIndex < lineText.length() && emojiOffset < lineEmojis.size(); charIndex++) {
                if (lineText.charAt(charIndex) != InlineEmoji.PLACEHOLDER) continue;

                ImageCache.CachedImage image = ImageCache.lookup(lineEmojis.get(emojiOffset));
                emojiOffset++;
                if (image == null) continue;

                int prefixWidth = (int) Math.round(textRenderer.getWidth(lineText.substring(0, charIndex)) * chatScale);
                int drawX = 2 + prefixWidth;
                int drawY = lineY - 1;
                int size = Math.max(8, lineHeight + 1);
                ImageRenderer.draw(context, image, drawX, drawY, new ImageRenderer.Size(size, size));
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
