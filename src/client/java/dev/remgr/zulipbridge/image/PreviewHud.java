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
    private static final double MIN_ZOOM = 0.5D;
    private static final double MAX_ZOOM = 4.0D;

    private static String currentImageHash;
    private static long showUntil;
    private static double zoom = 1.0D;
    private static double panX;
    private static double panY;
    private static boolean dragging;
    private static double lastMouseX;
    private static double lastMouseY;
    private static PreviewLayout cachedLayout;
    private static double cachedZoom;

    public static void show(String imageHash) {
        currentImageHash = imageHash;
        showUntil = System.currentTimeMillis() + 30_000;
        zoom = 1.0D;
        panX = 0.0D;
        panY = 0.0D;
        dragging = false;
    }

    public static boolean isActive() {
        return currentImageHash != null && System.currentTimeMillis() <= showUntil;
    }

    public static void close() {
        currentImageHash = null;
        showUntil = 0L;
        dragging = false;
        cachedLayout = null;
    }

    public static boolean handleEscape(int keyCode) {
        if (!isActive() || keyCode != GLFW.GLFW_KEY_ESCAPE) return false;
        close();
        return true;
    }

    public static boolean handleMousePressed(double mouseX, double mouseY) {
        PreviewLayout layout = getLayout();
        if (layout == null) return false;

        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (layout.closeButton().contains(mouseX, mouseY)) {
            close();
            return true;
        }

        dragging = layout.frame().contains(mouseX, mouseY);
        return true;
    }

    public static void handleMouseReleased() {
        dragging = false;
    }

    public static boolean handleScroll(double mouseX, double mouseY, double verticalAmount) {
        if (!isActive()) return false;
        if (verticalAmount == 0.0D) return false;

        double previousZoom = zoom;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * (verticalAmount > 0.0D ? 1.15D : (1.0D / 1.15D))));
        if (Math.abs(previousZoom - zoom) < 0.0001D) {
            return true;
        }

        double factor = zoom / previousZoom;
        panX *= factor;
        panY *= factor;
        clampPan();
        return true;
    }

    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        if (MinecraftClient.getInstance().currentScreen != null) return;
        renderOverlay(context);
    }

    public static void renderOverlay(DrawContext context) {
        if (dragging) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.getWindow() != null) {
                double sx = mc.mouse.getX() * mc.getWindow().getScaledWidth() / mc.getWindow().getWidth();
                double sy = mc.mouse.getY() * mc.getWindow().getScaledHeight() / mc.getWindow().getHeight();
                double dx = sx - lastMouseX;
                double dy = sy - lastMouseY;
                lastMouseX = sx;
                lastMouseY = sy;
                if (dx != 0 || dy != 0) {
                    panX += dx;
                    panY += dy;
                    clampPan();
                }
            }
        }

        PreviewLayout layout = getLayout();
        if (layout == null) {
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

        Rect frame = layout.frame();
        context.fill(frame.x(), frame.y(), frame.x() + frame.width(), frame.y() + frame.height(), 0xF01A1D26);
        drawBorder(context, frame.x(), frame.y(), frame.width(), frame.height(), 0xFF58657E);
        // Render at exact float pan position (sub-pixel) via matrix translate so panning
        // is smooth even when the delta per frame is less than one integer pixel.
        int imageBaseX = (windowWidth - layout.image().width()) / 2;
        int imageBaseY = (windowHeight - layout.image().height()) / 2;
        context.getMatrices().pushMatrix();
        context.getMatrices().translate((float) panX, (float) panY);
        ImageRenderer.draw(context, image, imageBaseX, imageBaseY, new ImageRenderer.Size(layout.image().width(), layout.image().height()));
        context.getMatrices().popMatrix();
        context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
                image.width() + " x " + image.height() + "  |  preview  |  zoom " + Math.round(zoom * 100.0D) + "%",
                frame.x() + 6,
                frame.y() - 14,
                0xFFD8E2F2);

        Rect closeButton = layout.closeButton();
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

    private static PreviewLayout getLayout() {
        if (cachedLayout != null
                && Math.abs(cachedZoom - zoom) < 0.0001D) {
            return cachedLayout;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null || !isActive()) return null;

        ImageCache.CachedImage image = ImageCache.lookup(currentImageHash);
        if (image == null) return null;

        int windowWidth = client.getWindow().getScaledWidth();
        int windowHeight = client.getWindow().getScaledHeight();
        int viewportWidth = Math.max(1, windowWidth * 9 / 10);
        int viewportHeight = Math.max(1, windowHeight * 9 / 10);
        ImageRenderer.Size baseSize = ImageRenderer.fit(image.width(), image.height(), viewportWidth, viewportHeight);
        int drawWidth = Math.max(1, (int) Math.round(baseSize.width() * zoom));
        int drawHeight = Math.max(1, (int) Math.round(baseSize.height() * zoom));
        int frameX = (windowWidth - viewportWidth) / 2 - FRAME_PADDING;
        int frameY = (windowHeight - viewportHeight) / 2 - FRAME_PADDING;
        int frameWidth = viewportWidth + FRAME_PADDING * 2;
        int frameHeight = viewportHeight + FRAME_PADDING * 2;
        Rect closeButton = new Rect(
                frameX + frameWidth - CLOSE_BUTTON_MARGIN - CLOSE_BUTTON_SIZE,
                frameY + CLOSE_BUTTON_MARGIN,
                CLOSE_BUTTON_SIZE,
                CLOSE_BUTTON_SIZE
        );

        cachedLayout = new PreviewLayout(
                new Rect(frameX, frameY, frameWidth, frameHeight),
                new Rect(0, 0, drawWidth, drawHeight),
                closeButton,
                viewportWidth,
                viewportHeight
        );
        cachedZoom = zoom;
        return cachedLayout;
    }

    private static void clampPan() {
        PreviewLayout layout = getLayout();
        if (layout == null) return;

        double maxPanX = Math.max(layout.image().width(), layout.viewportWidth()) / 2.0D;
        double maxPanY = Math.max(layout.image().height(), layout.viewportHeight()) / 2.0D;
        panX = Math.max(-maxPanX, Math.min(maxPanX, panX));
        panY = Math.max(-maxPanY, Math.min(maxPanY, panY));
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

    private record PreviewLayout(Rect frame, Rect image, Rect closeButton, int viewportWidth, int viewportHeight) {
    }
}
