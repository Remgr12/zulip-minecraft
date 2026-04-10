package dev.remgr.zulipbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.remgr.zulipbridge.config.ZulipBridgeConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Background thread that polls the Zulip REST API for new messages and
 * injects them into the Minecraft chat GUI.
 *
 * <p>One instance is started when the bridge is enabled, and stopped
 * (via {@link #shutdown()}) when disabled or the client disconnects.
 */
public class ZulipPollingThread extends Thread {

    private static final Logger LOGGER = LoggerFactory.getLogger("zulip-bridge/poll");

    private final ZulipBridgeConfig config;
    private volatile boolean running = true;

    /** The highest message ID we have already displayed. -1 = not yet seeded. */
    private long lastSeenId = -1;

    public ZulipPollingThread(ZulipBridgeConfig config) {
        super("ZulipBridgePollThread");
        this.config = config;
        setDaemon(true);
    }

    /** Signal the thread to stop at the next sleep boundary. */
    public void shutdown() {
        running = false;
        interrupt();
    }

    // ── Main loop ─────────────────────────────────────────────────────────────

    @Override
    public void run() {
        LOGGER.info("Zulip polling thread started.");
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Seed lastSeenId so we don't replay history on startup.
        lastSeenId = fetchNewestId(http);
        LOGGER.info("Seeded lastSeenId={}", lastSeenId);

        while (running) {
            try {
                int sleepMs = config.pollIntervalSeconds() * 1000;
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (!running) break;

            try {
                pollAndDisplay(http);
            } catch (Exception e) {
                LOGGER.warn("Poll error: {}", e.getMessage());
            }
        }

        LOGGER.info("Zulip polling thread stopped.");
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private void pollAndDisplay(HttpClient http) throws Exception {
        String narrowJson = buildNarrowJson();
        long anchor = lastSeenId < 0 ? 0 : lastSeenId + 1;

        String query = "anchor=" + anchor
                + "&num_before=0"
                + "&num_after=50"
                + "&narrow=" + URLEncoder.encode(narrowJson, StandardCharsets.UTF_8)
                + "&apply_markdown=false"
                + "&client_gravatar=false";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.zulipBaseUrl() + "/api/v1/messages?" + query))
                .header("Authorization", buildAuthHeader())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            LOGGER.warn("Zulip poll HTTP {}: {}", resp.statusCode(), resp.body());
            return;
        }

        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!"success".equals(body.get("result").getAsString())) {
            LOGGER.warn("Zulip API error: {}", body.get("msg").getAsString());
            return;
        }

        JsonArray messages = body.getAsJsonArray("messages");
        for (int i = 0; i < messages.size(); i++) {
            JsonObject msg = messages.get(i).getAsJsonObject();
            long id = msg.get("id").getAsLong();
            if (id <= lastSeenId) continue;

            String sender  = msg.get("sender_full_name").getAsString();
            String content = msg.get("content").getAsString();
            // Strip markdown for cleaner display; collapse newlines.
            content = content
                    .replaceAll("\\*\\*(.*?)\\*\\*", "$1")
                    .replaceAll("\\*(.*?)\\*",       "$1")
                    .replaceAll("`(.*?)`",            "$1")
                    .replaceAll("\n",                 " | ");

            displayInChat(sender, content);
            if (id > lastSeenId) lastSeenId = id;
        }
    }

    /** Fetch the ID of the most-recent message in the target topic. Returns 0 if empty. */
    private long fetchNewestId(HttpClient http) {
        try {
            String narrowJson = buildNarrowJson();
            String query = "anchor=newest&num_before=1&num_after=0"
                    + "&narrow=" + URLEncoder.encode(narrowJson, StandardCharsets.UTF_8)
                    + "&apply_markdown=false&client_gravatar=false";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.zulipBaseUrl() + "/api/v1/messages?" + query))
                    .header("Authorization", buildAuthHeader())
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
            if ("success".equals(body.get("result").getAsString())) {
                JsonArray msgs = body.getAsJsonArray("messages");
                if (!msgs.isEmpty()) {
                    return msgs.get(msgs.size() - 1).getAsJsonObject().get("id").getAsLong();
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not seed lastSeenId: {}", e.getMessage());
        }
        return 0;
    }

    // ── Chat display ──────────────────────────────────────────────────────────

    private void displayInChat(String sender, String content) {
        String prefix = config.incomingPrefix();
        Text message = Text.empty()
                .copy()
                .append(Text.literal(prefix).setStyle(Style.EMPTY.withColor(Formatting.AQUA)))
                .append(Text.literal(sender).setStyle(Style.EMPTY.withColor(Formatting.YELLOW)))
                .append(Text.literal(": ").setStyle(Style.EMPTY.withColor(Formatting.WHITE)))
                .append(Text.literal(content).setStyle(Style.EMPTY.withColor(Formatting.WHITE)));

        MinecraftClient client = MinecraftClient.getInstance();
        // Must be dispatched on the render thread to avoid threading issues.
        client.execute(() -> {
            if (client.inGameHud != null) {
                client.inGameHud.getChatHud().addMessage(message);
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildAuthHeader() {
        String creds = config.botEmail() + ":" + config.botApiKey();
        return "Basic " + Base64.getEncoder().encodeToString(
                creds.getBytes(StandardCharsets.UTF_8));
    }

    private String buildNarrowJson() {
        // JSON array: [{"operator":"stream","operand":"<stream>"},{"operator":"topic","operand":"<topic>"}]
        return "[{\"operator\":\"stream\",\"operand\":\""
                + escapeJson(config.streamName()) + "\"},"
                + "{\"operator\":\"topic\",\"operand\":\""
                + escapeJson(config.topicName()) + "\"}]";
    }

    /** Minimal JSON string escaping — only what we need for user-supplied strings. */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Static helper: send a message to Zulip ────────────────────────────────

    /**
     * POST a single message to the configured stream/topic.
     * Called from the mixin on the render thread; runs synchronously in a
     * virtual thread to avoid blocking.
     */
    public static void sendToZulip(ZulipBridgeConfig cfg, String content) {
        Thread.ofVirtual().name("ZulipBridgeSend").start(() -> {
            try {
                String auth = "Basic " + Base64.getEncoder().encodeToString(
                        (cfg.botEmail() + ":" + cfg.botApiKey()).getBytes(StandardCharsets.UTF_8));

                String form = "type=stream"
                        + "&to=" + URLEncoder.encode(cfg.streamName(), StandardCharsets.UTF_8)
                        + "&topic=" + URLEncoder.encode(cfg.topicName(), StandardCharsets.UTF_8)
                        + "&content=" + URLEncoder.encode(content, StandardCharsets.UTF_8);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(cfg.zulipBaseUrl() + "/api/v1/messages"))
                        .header("Authorization", auth)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();

                HttpClient http = HttpClient.newHttpClient();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    LOGGER.warn("Zulip send HTTP {}: {}", resp.statusCode(), resp.body());
                }
            } catch (Exception e) {
                LOGGER.warn("Zulip send error: {}", e.getMessage());
            }
        });
    }
}
