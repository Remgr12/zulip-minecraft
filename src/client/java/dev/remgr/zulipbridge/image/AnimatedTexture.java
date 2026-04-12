package dev.remgr.zulipbridge.image;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

public final class AnimatedTexture extends NativeImageBackedTexture {
    private static final long DEFAULT_FRAME_DELAY_MS = 100L;

    private final List<NativeImage> frames;
    private final List<Long> frameDurationsMs;
    private int currentFrame;
    private long nextFrameAtMs;

    private AnimatedTexture(Supplier<String> nameSupplier, List<NativeImage> frames, List<Long> frameDurationsMs) {
        super(nameSupplier, cloneImage(frames.get(0)));
        this.frames = frames;
        this.frameDurationsMs = frameDurationsMs;
        this.currentFrame = 0;
        this.nextFrameAtMs = System.currentTimeMillis() + frameDurationsMs.get(0);
    }

    public static AnimatedTexture fromGif(Supplier<String> nameSupplier, byte[] bytes) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) {
            throw new IOException("No GIF reader is available");
        }

        ImageReader reader = readers.next();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input, false, false);

            int frameCount = reader.getNumImages(true);
            if (frameCount <= 0) {
                throw new IOException("GIF did not contain any frames");
            }

            List<NativeImage> frames = new ArrayList<>(frameCount);
            List<Long> durations = new ArrayList<>(frameCount);
            for (int i = 0; i < frameCount; i++) {
                BufferedImage bufferedImage = reader.read(i);
                frames.add(toNativeImage(bufferedImage));
                durations.add(readDelayMillis(reader.getImageMetadata(i)));
            }

            return new AnimatedTexture(nameSupplier, frames, durations);
        } finally {
            reader.dispose();
        }
    }

    public int getWidth() {
        return frames.get(0).getWidth();
    }

    public int getHeight() {
        return frames.get(0).getHeight();
    }

    public int getFrameCount() {
        return frames.size();
    }

    public void tick() {
        if (frames.size() <= 1) return;

        long now = System.currentTimeMillis();
        if (now < nextFrameAtMs) return;

        currentFrame = (currentFrame + 1) % frames.size();
        setImage(cloneImage(frames.get(currentFrame)));
        upload();
        nextFrameAtMs = now + frameDurationsMs.get(currentFrame);
    }

    @Override
    public void close() {
        super.close();
        for (NativeImage frame : frames) {
            frame.close();
        }
    }

    private static NativeImage toNativeImage(BufferedImage image) {
        NativeImage nativeImage = new NativeImage(image.getWidth(), image.getHeight(), true);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int abgr = (argb & 0xFF00FF00)
                        | ((argb >> 16) & 0x000000FF)
                        | ((argb << 16) & 0x00FF0000);
                nativeImage.setColorArgb(x, y, abgr);
            }
        }
        return nativeImage;
    }

    private static NativeImage cloneImage(NativeImage source) {
        NativeImage copy = new NativeImage(source.getWidth(), source.getHeight(), true);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                copy.setColorArgb(x, y, source.getColorArgb(x, y));
            }
        }
        return copy;
    }

    private static long readDelayMillis(IIOMetadata metadata) {
        if (metadata == null) return DEFAULT_FRAME_DELAY_MS;

        try {
            Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
            Node child = root.getFirstChild();
            while (child != null) {
                if ("GraphicControlExtension".equals(child.getNodeName())) {
                    NamedNodeMap attributes = child.getAttributes();
                    Node delay = attributes != null ? attributes.getNamedItem("delayTime") : null;
                    if (delay != null) {
                        int hundredths = Integer.parseInt(delay.getNodeValue());
                        return Math.max(1L, hundredths) * 10L;
                    }
                }
                child = child.getNextSibling();
            }
        } catch (Exception ignored) {
        }

        return DEFAULT_FRAME_DELAY_MS;
    }
}
