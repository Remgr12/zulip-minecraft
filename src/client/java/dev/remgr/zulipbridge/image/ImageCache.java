package dev.remgr.zulipbridge.image;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ImageCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("zulip-bridge/img-cache");
    private static final long MAX_TOTAL_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 128;

    private static final Map<String, CachedImage> CACHE = new LinkedHashMap<>(32, 0.75f, true);
    private static long currentTotalBytes = 0;

    private ImageCache() {
    }

    public static synchronized CachedImage getOrLoad(String imageHash, byte[] bytes, String suggestedName, boolean animated) {
        CachedImage existing = CACHE.get(imageHash);
        if (existing != null) {
            existing.tick();
            return existing;
        }

        try {
            NativeImageBackedTexture texture;
            int width;
            int height;
            long estimatedBytes;

            if (animated) {
                AnimatedTexture animatedTexture = AnimatedTexture.fromGif(() -> suggestedName, bytes);
                texture = animatedTexture;
                width = animatedTexture.getWidth();
                height = animatedTexture.getHeight();
                estimatedBytes = Math.max(bytes.length, (long) width * height * 4L * Math.max(1, animatedTexture.getFrameCount()));
            } else {
                NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
                width = image.getWidth();
                height = image.getHeight();
                estimatedBytes = Math.max(bytes.length, (long) width * height * 4L);
                texture = new NativeImageBackedTexture(() -> suggestedName, image);
            }

            Identifier id = Identifier.of("zulip-bridge", "dyn/" + imageHash);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);

            CachedImage entry = new CachedImage(imageHash, id, width, height, estimatedBytes, texture);
            CACHE.put(imageHash, entry);
            currentTotalBytes += estimatedBytes;
            enforceLimits();

            entry.tick();
            return entry;
        } catch (IOException e) {
            LOGGER.warn("Failed to decode image {}: {}", suggestedName, e.toString());
            return null;
        }
    }

    public static synchronized CachedImage lookup(String imageHash) {
        CachedImage entry = CACHE.get(imageHash);
        if (entry != null) {
            entry.tick();
        }
        return entry;
    }

    public static synchronized String hashUrl(String url) {
        return sha256Hex(url.getBytes(StandardCharsets.UTF_8));
    }

    public static synchronized void clear() {
        List<CachedImage> entries = new ArrayList<>(CACHE.values());
        CACHE.clear();
        currentTotalBytes = 0;

        for (CachedImage entry : entries) {
            destroy(entry);
        }
    }

    private static void enforceLimits() {
        Iterator<Map.Entry<String, CachedImage>> iterator = CACHE.entrySet().iterator();
        while ((currentTotalBytes > MAX_TOTAL_BYTES || CACHE.size() > MAX_ENTRIES) && iterator.hasNext()) {
            CachedImage entry = iterator.next().getValue();
            iterator.remove();
            currentTotalBytes -= entry.estimatedBytes();
            destroy(entry);
        }
    }

    private static void destroy(CachedImage entry) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.execute(() -> {
                client.getTextureManager().destroyTexture(entry.identifier());
                entry.texture().close();
            });
            return;
        }
        entry.texture().close();
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(data);
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record CachedImage(
            String imageHash,
            Identifier identifier,
            int width,
            int height,
            long estimatedBytes,
            NativeImageBackedTexture texture
    ) {
        public void tick() {
            if (texture instanceof AnimatedTexture animatedTexture) {
                animatedTexture.tick();
            }
        }
    }
}
