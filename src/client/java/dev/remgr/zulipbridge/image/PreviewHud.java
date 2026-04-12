package dev.remgr.zulipbridge.image;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import org.lwjgl.glfw.GLFW;

public class PreviewHud implements HudRenderCallback {
    private static final int FRAME_PADDING = 8;
    private static final int CLOSE_BUTTON_SIZE = 12;
    private static final int CLOSE_BUTTON_MARGIN = 6;
    private static String currentImageHash;
    private static long showUntil;

    public static void show(String imageHash) {
        currentImageHash = imageHash;
        showUntil = System.currentTimeMillis() + 30_000;
    }

    public static boolean isActive() {
        return currentImageHash != null && System.currentTimeMillis() <= showUntil;
    }

    public static void close() {
        currentImageHash = null;
        showUntil = 0L;
    }

    public static boolean handleEscape(int keyCode) {
        if (!isActive() || keyCode != GLFW.GLFW_KEY_ESCAPE) return false;
        close();
        return true;
    }

    public static boolean handleClick(double mouseX, double mouseY) {
        if (!isActive()) return false;

        Rect closeButton = getCloseButtonRect();
        if (closeButton != null && closeButton.contains(mouseX, mouseY)) {
            close();
        }

        return true;
    }

    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        if (MinecraftClient.getInstance().currentScreen != null) return;
        renderOverlay(context);
    }

    public static void renderOverlay(DrawContext context) {
        if (!isActive()) {
            currentImageHash = null;
            return;
        }

        ImageCache.CachedImage image = ImageCache.lookup(currentImageHash);
        if (image == null) {
            currentImageHash = null;
            return;
        }

        int windowWidth = context.getScaledWindowWidth();
        int windowHeight = context.getScaledWindowHeight();
        context.fill(0, 0, windowWidth, windowHeight, 0xCC0F1118);

        int maxWidth = Math.max(1, windowWidth * 9 / 10);
        int maxHeight = Math.max(1, windowHeight * 9 / 10);
        ImageRenderer.Size size = ImageRenderer.fit(image.width(), image.height(), maxWidth, maxHeight);
        int x = (windowWidth - size.width()) / 2;
        int y = (windowHeight - size.height()) / 2;
        int frameX = x - FRAME_PADDING;
        int frameY = y - FRAME_PADDING;
        int frameWidth = size.width() + FRAME_PADDING * 2;
        int frameHeight = size.height() + FRAME_PADDING * 2;
        context.fill(frameX, frameY, frameX + frameWidth, frameY + frameHeight, 0xF01A1D26);
        drawBorder(context, frameX, frameY, frameWidth, frameHeight, 0xFF58657E);
        ImageRenderer.draw(context, image, x, y, size);
        context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
                image.width() + " x " + image.height() + "  |  preview",
                frameX + 6,
                frameY - 14,
                0xFFD8E2F2);

        Rect closeButton = new Rect(
                frameX + frameWidth - CLOSE_BUTTON_MARGIN - CLOSE_BUTTON_SIZE,
                frameY + CLOSE_BUTTON_MARGIN,
                CLOSE_BUTTON_SIZE,
                CLOSE_BUTTON_SIZE
        );
        context.fill(closeButton.x(), closeButton.y(), closeButton.x() + closeButton.width(), closeButton.y() + closeButton.height(), 0xE03A2028);
        drawBorder(context, closeButton.x(), closeButton.y(), closeButton.width(), closeButton.height(), 0xFFB4848E);
        context.drawCenteredTextWithShadow(
                MinecraftClient.getInstance().textRenderer,
                "x",
                closeButton.x() + closeButton.width() / 2,
                closeButton.y() + 2,
                0xFFF6EDEF
        );
    }

    private static Rect getCloseButtonRect() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null || !isActive()) return null;

        ImageCache.CachedImage image = ImageCache.lookup(currentImageHash);
        if (image == null) return null;

        int windowWidth = client.getWindow().getScaledWidth();
        int windowHeight = client.getWindow().getScaledHeight();
        int maxWidth = Math.max(1, windowWidth * 9 / 10);
        int maxHeight = Math.max(1, windowHeight * 9 / 10);
        ImageRenderer.Size size = ImageRenderer.fit(image.width(), image.height(), maxWidth, maxHeight);
        int x = (windowWidth - size.width()) / 2;
        int y = (windowHeight - size.height()) / 2;
        int frameX = x - FRAME_PADDING;
        int frameY = y - FRAME_PADDING;
        int frameWidth = size.width() + FRAME_PADDING * 2;

        return new Rect(
                frameX + frameWidth - CLOSE_BUTTON_MARGIN - CLOSE_BUTTON_SIZE,
                frameY + CLOSE_BUTTON_MARGIN,
                CLOSE_BUTTON_SIZE,
                CLOSE_BUTTON_SIZE
        );
    }

    private static void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }

    private record Rect(int x, int y, int width, int height) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }
}
