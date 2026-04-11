package dev.remgr.zulipbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.remgr.zulipbridge.config.ZulipBridgeConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Background thread that polls the Zulip REST API for new messages and
 * injects them into the Minecraft chat GUI.
 *
 * <p>One instance is started when the bridge is enabled, and stopped
 * (via {@link #shutdown()}) when disabled or the client disconnects.
 */
public class ZulipPollingThread extends Thread {

    private static final Logger LOGGER = LoggerFactory.getLogger("zulip-bridge/poll");
    private static final String DEFAULT_TEXT_PREFIX = "[Zulip] ";
    private static final String IMAGE_PREFIX_GLYPH = "\uE000";
    private static final Identifier IMAGE_PREFIX_FONT = Identifier.of("zulip-bridge", "zulip_prefix");
    private static final StyleSpriteSource IMAGE_PREFIX_FONT_SOURCE = new StyleSpriteSource.Font(IMAGE_PREFIX_FONT);
    private static final int DEFAULT_SENDER_COLOR = 0x3A9E5C;
    private static final int DEFAULT_MESSAGE_COLOR = 0x50C878;

    private final ZulipBridgeConfig config;
    private volatile boolean running = true;
    private String cachedDirectRecipients;
    private int[] cachedDirectRecipientIds;

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
        String narrowJson = buildNarrowJson(http);
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
            lastSeenId = id;

            String sender  = msg.get("sender_full_name").getAsString();
            String senderEmail = msg.has("sender_email") ? msg.get("sender_email").getAsString() : "";
            if (shouldSuppressMessage(senderEmail)) continue;

            String content = formatIncomingContent(msg.get("content").getAsString());
            displayInChat(sender, senderEmail, content);
        }
    }

    /** Fetch the ID of the most-recent message in the target topic. Returns 0 if empty. */
    private long fetchNewestId(HttpClient http) {
        try {
            String narrowJson = buildNarrowJson(http);
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

    private void displayInChat(String sender, String senderEmail, String content) {
        boolean showPrefix = config.showIncomingPrefix();
        String prefix = normalizePrefix(config.incomingPrefix());
        boolean useImagePrefix = DEFAULT_TEXT_PREFIX.equals(prefix);
        int senderColor = parseHexColor(config.senderColor(), DEFAULT_SENDER_COLOR);
        int messageColor = parseHexColor(config.messageColor(), DEFAULT_MESSAGE_COLOR);

        Style prefixStyle = Style.EMPTY
                .withClickEvent(new ClickEvent.SuggestCommand("/zulip target show"))
                .withHoverEvent(new HoverEvent.ShowText(
                        Text.literal("Target: " + ZulipBridgeCommandHandler.describeTarget())));

        Text prefixText = useImagePrefix
                ? Text.literal(IMAGE_PREFIX_GLYPH).setStyle(prefixStyle.withFont(IMAGE_PREFIX_FONT_SOURCE))
                : Text.literal(prefix).setStyle(prefixStyle.withColor(Formatting.AQUA));

        Style senderStyle = Style.EMPTY.withColor(senderColor);
        if (senderEmail != null && !senderEmail.isBlank()) {
            senderStyle = senderStyle
                    .withClickEvent(new ClickEvent.SuggestCommand("/zulip target dm " + senderEmail))
                    .withHoverEvent(new HoverEvent.ShowText(
                            Text.literal("Sender: " + senderEmail + "\nClick to prepare a DM target command")));
        }

        var message = Text.empty().copy();
        if (showPrefix) {
            message.append(prefixText);
            if (useImagePrefix) {
                message.append(Text.literal(" "));
            }
        }
        message.append(Text.literal(sender).setStyle(senderStyle))
                .append(Text.literal(": ").setStyle(Style.EMPTY.withColor(messageColor)))
                .append(Text.literal(content).setStyle(Style.EMPTY.withColor(messageColor)));

        MinecraftClient client = MinecraftClient.getInstance();
        // Must be dispatched on the render thread to avoid threading issues.
        client.execute(() -> {
            if (client.inGameHud != null) {
                client.inGameHud.getChatHud().addMessage(message);
            }
            notifyIncomingMessage(client, sender, content);
        });
    }

    private boolean shouldSuppressMessage(String senderEmail) {
        return config.suppressSelfEcho()
                && senderEmail != null
                && !senderEmail.isBlank()
                && senderEmail.equalsIgnoreCase(config.botEmail());
    }

    private String formatIncomingContent(String content) {
        return switch (config.incomingMessageFormat()) {
            case RAW_MARKDOWN -> content.replace("\n", " | ");
            case PLAIN_TEXT -> content
                    .replaceAll("\\*\\*(.*?)\\*\\*", "$1")
                    .replaceAll("\\*(.*?)\\*", "$1")
                    .replaceAll("`(.*?)`", "$1")
                    .replace("\n", " | ");
        };
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return "[Zulip] ";
        return prefix.endsWith(" ") ? prefix : prefix + " ";
    }

    private static int parseHexColor(String raw, int fallback) {
        if (raw == null) return fallback;

        String value = raw.trim();
        if (value.isEmpty()) return fallback;

        if (value.startsWith("#")) {
            value = value.substring(1);
        } else if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }

        // Allow AARRGGBB input by discarding the alpha component.
        if (value.length() == 8) {
            value = value.substring(2);
        }

        if (value.length() != 6) return fallback;
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) return fallback;
        }

        try {
            return Integer.parseUnsignedInt(value, 16);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void notifyIncomingMessage(MinecraftClient client, String sender, String content) {
        if (config.playIncomingSound()) {
            client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }

        if (config.showIncomingToast()) {
            String description = content.length() > 120 ? content.substring(0, 117) + "..." : content;
            SystemToast.add(
                    client.getToastManager(),
                    SystemToast.Type.PERIODIC_NOTIFICATION,
                    Text.literal("Zulip: " + sender),
                    Text.literal(description)
            );
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildAuthHeader() {
        String creds = config.botEmail() + ":" + config.botApiKey();
        return "Basic " + Base64.getEncoder().encodeToString(
                creds.getBytes(StandardCharsets.UTF_8));
    }

    private String buildNarrowJson(HttpClient http) throws Exception {
        if (config.messageTarget() == dev.remgr.zulipbridge.config.ZulipBridgeConfigModel.MessageTarget.DIRECT_MESSAGE) {
            return "[{\"operator\":\"dm\",\"operand\":" + buildDirectRecipientIdsJson(http) + "}]";
        }

        return "[{\"operator\":\"stream\",\"operand\":\""
                + escapeJson(config.streamName()) + "\"},"
                + "{\"operator\":\"topic\",\"operand\":\""
                + escapeJson(config.topicName()) + "\"}]";
    }

    private String buildDirectRecipientIdsJson(HttpClient http) throws Exception {
        int[] recipients = resolveDirectRecipientIds(http);
        StringBuilder builder = new StringBuilder("[");

        for (int i = 0; i < recipients.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(recipients[i]);
        }

        builder.append(']');
        return builder.toString();
    }

    private int[] resolveDirectRecipientIds(HttpClient http) throws Exception {
        String rawRecipients = config.directMessageRecipients();
        if (rawRecipients != null && rawRecipients.equals(cachedDirectRecipients) && cachedDirectRecipientIds != null) {
            return cachedDirectRecipientIds;
        }

        String[] recipients = parseDirectRecipients(rawRecipients);
        int[] ids = new int[recipients.length];

        for (int i = 0; i < recipients.length; i++) {
            ids[i] = fetchUserIdByEmail(http, recipients[i]);
        }

        cachedDirectRecipients = rawRecipients;
        cachedDirectRecipientIds = ids;
        return ids;
    }

    private int fetchUserIdByEmail(HttpClient http, String email) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.zulipBaseUrl() + "/api/v1/users/" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                        + "?client_gravatar=false"))
                .header("Authorization", buildAuthHeader())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Could not resolve Zulip user '" + email + "': HTTP " + resp.statusCode());
        }

        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!"success".equals(body.get("result").getAsString())) {
            throw new IllegalStateException("Could not resolve Zulip user '" + email + "': " + body.get("msg").getAsString());
        }

        return body.getAsJsonObject("user").get("user_id").getAsInt();
    }

    private static String[] parseDirectRecipients(String raw) {
        if (raw == null || raw.isBlank()) return new String[0];

        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toArray(String[]::new);
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
    public static void sendToZulip(ZulipBridgeConfig cfg, String content, BiConsumer<Boolean, String> callback) {
        Thread.ofVirtual().name("ZulipBridgeSend").start(() -> {
            try {
                String validationError = validateSendConfig(cfg);
                if (validationError != null) {
                    callback.accept(false, validationError);
                    return;
                }

                String auth = "Basic " + Base64.getEncoder().encodeToString(
                        (cfg.botEmail() + ":" + cfg.botApiKey()).getBytes(StandardCharsets.UTF_8));

                String form;
                if (cfg.messageTarget() == dev.remgr.zulipbridge.config.ZulipBridgeConfigModel.MessageTarget.DIRECT_MESSAGE) {
                    String[] recipients = parseDirectRecipients(cfg.directMessageRecipients());
                    if (recipients.length == 0) {
                        callback.accept(false, "No DM recipients configured.");
                        return;
                    }

                    StringBuilder recipientsJson = new StringBuilder("[");
                    for (int i = 0; i < recipients.length; i++) {
                        if (i > 0) recipientsJson.append(',');
                        recipientsJson.append('"').append(recipients[i].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                    }
                    recipientsJson.append(']');

                    form = "type=direct"
                            + "&to=" + URLEncoder.encode(recipientsJson.toString(), StandardCharsets.UTF_8)
                            + "&content=" + URLEncoder.encode(content, StandardCharsets.UTF_8);
                } else {
                    form = "type=stream"
                            + "&to=" + URLEncoder.encode(cfg.streamName(), StandardCharsets.UTF_8)
                            + "&topic=" + URLEncoder.encode(cfg.topicName(), StandardCharsets.UTF_8)
                            + "&content=" + URLEncoder.encode(content, StandardCharsets.UTF_8);
                }

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
                    callback.accept(false, "Send failed: HTTP " + resp.statusCode());
                    return;
                }

                JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
                if (!"success".equals(body.get("result").getAsString())) {
                    callback.accept(false, "Send failed: " + body.get("msg").getAsString());
                    return;
                }

                callback.accept(true, "Sent to " + describeTarget(cfg) + ".");
            } catch (Exception e) {
                LOGGER.warn("Zulip send error: {}", e.getMessage());
                callback.accept(false, "Send failed: " + e.getMessage());
            }
        });
    }

    public static void testConnection(ZulipBridgeConfig cfg, BiConsumer<Boolean, String> callback) {
        runOwnUserRequest(cfg, callback, (user, ok) -> ok.accept(
                true,
                "Zulip connection OK as " + user.get("full_name").getAsString()
                        + " <" + user.get("email").getAsString() + ">"
        ));
    }

    public static void fetchOwnUserSummary(ZulipBridgeConfig cfg, BiConsumer<Boolean, String> callback) {
        runOwnUserRequest(cfg, callback, (user, ok) -> ok.accept(
                true,
                "Authenticated as " + user.get("full_name").getAsString()
                        + " <" + user.get("email").getAsString() + "> (id "
                        + user.get("user_id").getAsInt() + ")"
        ));
    }

    public static void fetchTreeSummary(ZulipBridgeConfig cfg, BiConsumer<Boolean, String> callback) {
        Thread.ofVirtual().name("ZulipBridgeTree").start(() -> {
            try {
                String validationError = validateAccountConfig(cfg);
                if (validationError != null) {
                    callback.accept(false, validationError);
                    return;
                }

                HttpClient http = HttpClient.newHttpClient();
                List<String> lines = new ArrayList<>();
                lines.add("tree/");
                lines.add("|- target: " + describeTarget(cfg));

                List<String> streams = fetchSubscribedStreams(cfg, http);
                lines.add("|- channels/");
                if (streams.isEmpty()) {
                    lines.add("|  |- (none)");
                } else {
                    for (String stream : streams) {
                        lines.add("|  |- " + stream);
                    }
                }

                List<String> dms = fetchRecentDmConversations(cfg, http);
                lines.add("|- dms/");
                if (dms.isEmpty()) {
                    lines.add("   |- (none found in recent history)");
                } else {
                    for (String dm : dms) {
                        lines.add("   |- " + dm);
                    }
                }

                for (String line : lines) {
                    callback.accept(true, line);
                }
            } catch (Exception e) {
                callback.accept(false, "Tree fetch failed: " + e.getMessage());
            }
        });
    }

    private static void runOwnUserRequest(
            ZulipBridgeConfig cfg,
            BiConsumer<Boolean, String> callback,
            BiConsumer<JsonObject, BiConsumer<Boolean, String>> onSuccess
    ) {
        Thread.ofVirtual().name("ZulipBridgeWhoAmI").start(() -> {
            try {
                String validationError = validateAccountConfig(cfg);
                if (validationError != null) {
                    callback.accept(false, validationError);
                    return;
                }

                HttpClient http = HttpClient.newHttpClient();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(cfg.zulipBaseUrl() + "/api/v1/users/me"))
                        .header("Authorization", buildAuthHeader(cfg))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    callback.accept(false, "Request failed: HTTP " + resp.statusCode());
                    return;
                }

                JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
                if (!"success".equals(body.get("result").getAsString())) {
                    callback.accept(false, "Request failed: " + body.get("msg").getAsString());
                    return;
                }

                onSuccess.accept(body, callback);
            } catch (Exception e) {
                callback.accept(false, "Request failed: " + e.getMessage());
            }
        });
    }

    private static List<String> fetchSubscribedStreams(ZulipBridgeConfig cfg, HttpClient http) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(cfg.zulipBaseUrl() + "/api/v1/users/me/subscriptions"))
                .header("Authorization", buildAuthHeader(cfg))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("subscriptions HTTP " + resp.statusCode());
        }

        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!"success".equals(body.get("result").getAsString())) {
            throw new IllegalStateException(body.get("msg").getAsString());
        }

        List<String> streams = new ArrayList<>();
        JsonArray subscriptions = body.getAsJsonArray("subscriptions");
        for (int i = 0; i < subscriptions.size(); i++) {
            JsonObject stream = subscriptions.get(i).getAsJsonObject();
            String name = stream.get("name").getAsString();
            boolean muted = stream.has("is_muted") && stream.get("is_muted").getAsBoolean();
            boolean pinned = stream.has("pin_to_top") && stream.get("pin_to_top").getAsBoolean();
            streams.add(name + (pinned ? " [pinned]" : "") + (muted ? " [muted]" : ""));
        }

        streams.sort(String::compareToIgnoreCase);
        return streams;
    }

    private static List<String> fetchRecentDmConversations(ZulipBridgeConfig cfg, HttpClient http) throws Exception {
        String narrow = "[{\"operator\":\"is\",\"operand\":\"dm\"}]";
        String query = "anchor=newest&num_before=200&num_after=0"
                + "&narrow=" + URLEncoder.encode(narrow, StandardCharsets.UTF_8)
                + "&apply_markdown=false&client_gravatar=false";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(cfg.zulipBaseUrl() + "/api/v1/messages?" + query))
                .header("Authorization", buildAuthHeader(cfg))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("messages HTTP " + resp.statusCode());
        }

        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!"success".equals(body.get("result").getAsString())) {
            throw new IllegalStateException(body.get("msg").getAsString());
        }

        Set<String> conversations = new LinkedHashSet<>();
        JsonArray messages = body.getAsJsonArray("messages");
        for (int i = 0; i < messages.size(); i++) {
            JsonObject message = messages.get(i).getAsJsonObject();
            if (!message.has("display_recipient") || !message.get("display_recipient").isJsonArray()) continue;

            JsonArray recipients = message.getAsJsonArray("display_recipient");
            List<String> names = new ArrayList<>();
            for (int j = 0; j < recipients.size(); j++) {
                JsonObject recipient = recipients.get(j).getAsJsonObject();
                String email = recipient.has("email") ? recipient.get("email").getAsString() : "";
                if (email.equalsIgnoreCase(cfg.botEmail())) continue;

                String fullName = recipient.has("full_name") ? recipient.get("full_name").getAsString() : email;
                names.add(fullName + (email.isBlank() ? "" : " <" + email + ">"));
            }

            if (names.isEmpty()) continue;
            names.sort(Comparator.naturalOrder());
            conversations.add(String.join(", ", names));
        }

        return new ArrayList<>(conversations);
    }

    private static String validateSendConfig(ZulipBridgeConfig cfg) {
        String accountValidationError = validateAccountConfig(cfg);
        if (accountValidationError != null) return accountValidationError;

        return switch (cfg.messageTarget()) {
            case STREAM -> {
                if (cfg.streamName() == null || cfg.streamName().isBlank()) yield "Missing Stream Name.";
                if (cfg.topicName() == null || cfg.topicName().isBlank()) yield "Missing Topic Name.";
                yield null;
            }
            case DIRECT_MESSAGE -> {
                if (cfg.directMessageRecipients() == null || cfg.directMessageRecipients().isBlank()) {
                    yield "Missing DM Recipients.";
                }
                yield null;
            }
        };
    }

    private static String validateAccountConfig(ZulipBridgeConfig cfg) {
        if (cfg.zulipBaseUrl() == null || cfg.zulipBaseUrl().isBlank()) return "Missing Zulip Base URL.";
        if (cfg.botEmail() == null || cfg.botEmail().isBlank()) return "Missing Account Email.";
        if (cfg.botApiKey() == null || cfg.botApiKey().isBlank()) return "Missing Account API Key.";
        return null;
    }

    private static String buildAuthHeader(ZulipBridgeConfig cfg) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (cfg.botEmail() + ":" + cfg.botApiKey()).getBytes(StandardCharsets.UTF_8));
    }

    private static String describeTarget(ZulipBridgeConfig cfg) {
        return switch (cfg.messageTarget()) {
            case STREAM -> "stream " + cfg.streamName() + " > " + cfg.topicName();
            case DIRECT_MESSAGE -> "DM with " + cfg.directMessageRecipients();
        };
    }
}
