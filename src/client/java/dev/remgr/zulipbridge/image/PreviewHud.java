package dev.remgr.zulipbridge.image;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

public class PreviewHud implements HudRenderCallback {
    private static String currentImageHash;
    private static long showUntil;

    public static void show(String imageHash) {
        currentImageHash = imageHash;
        showUntil = System.currentTimeMillis() + 20_000;
    }

    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        if (currentImageHash == null) return;
        if (System.currentTimeMillis() > showUntil || MinecraftClient.getInstance().currentScreen != null) {
            currentImageHash = null;
            return;
        }

        ImageCache.CachedImage image = ImageCache.lookup(currentImageHash);
        if (image == null) {
            currentImageHash = null;
            return;
        }

        int maxWidth = Math.max(1, context.getScaledWindowWidth() * 4 / 5);
        int maxHeight = Math.max(1, context.getScaledWindowHeight() * 4 / 5);
        ImageRenderer.Size size = ImageRenderer.fit(image.width(), image.height(), maxWidth, maxHeight);
        int x = (context.getScaledWindowWidth() - size.width()) / 2;
        int y = (context.getScaledWindowHeight() - size.height()) / 2;
        ImageRenderer.draw(context, image, x, y, size);
    }
}
