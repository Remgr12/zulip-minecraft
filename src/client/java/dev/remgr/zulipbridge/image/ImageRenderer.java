package dev.remgr.zulipbridge.image;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;

public final class ImageRenderer {
    private ImageRenderer() {
    }

    public static Size fit(int imageWidth, int imageHeight, int maxWidth, int maxHeight) {
        if (imageWidth <= 0 || imageHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) {
            return new Size(0, 0);
        }

        double widthScale = (double) maxWidth / imageWidth;
        double heightScale = (double) maxHeight / imageHeight;
        double scale = Math.min(1.0D, Math.min(widthScale, heightScale));

        int drawWidth = Math.max(1, (int) Math.round(imageWidth * scale));
        int drawHeight = Math.max(1, (int) Math.round(imageHeight * scale));
        return new Size(drawWidth, drawHeight);
    }

    public static void draw(DrawContext context, ImageCache.CachedImage image, int x, int y, Size size) {
        if (size.width() <= 0 || size.height() <= 0) return;

        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                image.identifier(),
                x,
                y,
                0.0F,
                0.0F,
                size.width(),
                size.height(),
                image.width(),
                image.height(),
                image.width(),
                image.height()
        );
    }

    public record Size(int width, int height) {
    }
}
