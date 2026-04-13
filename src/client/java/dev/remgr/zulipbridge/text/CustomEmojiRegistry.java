package dev.remgr.zulipbridge.text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.remgr.zulipbridge.ZulipBridgeClient;
import dev.remgr.zulipbridge.config.ZulipBridgeConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class CustomEmojiRegistry {
    private static volatile Map<String, CustomEmoji> emojis = Map.of();

    private CustomEmojiRegistry() {
    }

    public static void clear() {
        emojis = Map.of();
    }

    public static void refreshAsync(ZulipBridgeConfig cfg) {
        refreshAsync(cfg, null);
    }

    public static void refreshAsync(ZulipBridgeConfig cfg, Runnable onComplete) {
        if (cfg == null || cfg.zulipBaseUrl() == null || cfg.zulipBaseUrl().isBlank()
                || cfg.botEmail() == null || cfg.botEmail().isBlank()
                || cfg.botApiKey() == null || cfg.botApiKey().isBlank()) {
            clear();
            return;
        }

        Thread.ofVirtual().name("ZulipBridgeCustomEmoji").start(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(cfg.zulipBaseUrl() + "/api/v1/realm/emoji"))
                        .header("Authorization", buildAuthHeader(cfg))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    ZulipBridgeClient.LOGGER.warn("Custom emoji fetch failed: HTTP {}", response.statusCode());
                    return;
                }

                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!body.has("result") || !"success".equals(body.get("result").getAsString()) || !body.has("emoji")) {
                    ZulipBridgeClient.LOGGER.warn("Custom emoji fetch failed: {}", body.has("msg") ? body.get("msg").getAsString() : "unknown response");
                    return;
                }

                URI baseUri = URI.create(cfg.zulipBaseUrl() + "/");
                Map<String, CustomEmoji> loaded = new HashMap<>();
                JsonObject emojiObject = body.getAsJsonObject("emoji");
                for (Map.Entry<String, JsonElement> entry : emojiObject.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonObject emojiJson = entry.getValue().getAsJsonObject();
                    if (emojiJson.has("deactivated") && emojiJson.get("deactivated").getAsBoolean()) continue;
                    if (!emojiJson.has("name") || !emojiJson.has("source_url")) continue;

                    String name = emojiJson.get("name").getAsString().trim();
                    if (name.isEmpty()) continue;

                    String rawUrl = emojiJson.has("still_url") && !emojiJson.get("still_url").isJsonNull()
                            ? emojiJson.get("still_url").getAsString()
                            : emojiJson.get("source_url").getAsString();
                    if (rawUrl == null || rawUrl.isBlank()) continue;

                    loaded.put(name.toLowerCase(Locale.ROOT), new CustomEmoji(name, baseUri.resolve(rawUrl).toString()));
                }

                emojis = Map.copyOf(loaded);
                if (onComplete != null) onComplete.run();
            } catch (Exception e) {
                ZulipBridgeClient.LOGGER.warn("Custom emoji fetch failed: {}", e.getMessage());
            }
        });
    }

    public static CustomEmoji get(String shortcode) {
        if (shortcode == null || shortcode.isBlank()) return null;
        return emojis.get(shortcode.toLowerCase(Locale.ROOT));
    }

    public static Set<String> names() {
        return Set.copyOf(new TreeSet<>(emojis.keySet()));
    }

    private static String buildAuthHeader(ZulipBridgeConfig cfg) {
        String creds = cfg.botEmail() + ":" + cfg.botApiKey();
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    public record CustomEmoji(String name, String url) {
    }
}
