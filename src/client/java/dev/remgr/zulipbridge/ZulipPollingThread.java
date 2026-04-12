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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Background thread that long-polls a Zulip event queue for new messages and
 * injects them into the Minecraft chat GUI.
 *
 * <p>One instance is started when the bridge is enabled, and stopped
 * (via {@link #shutdown()}) when disabled or the client disconnects.
 */
public class ZulipPollingThread extends Thread {

    private static final Logger LOGGER = LoggerFactory.getLogger("zulip-bridge/poll");
    private static final String DEFAULT_TEXT_PREFIX = "[Zulip] ";
    private static final int LEGACY_SENDER_COLOR = 0x3A9E5C;
    private static final int LEGACY_MESSAGE_COLOR = 0x50C878;
    private static final int DEFAULT_SENDER_COLOR = 0x67FF67;
    private static final int DEFAULT_MESSAGE_COLOR = 0xB5FFB5;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EVENT_REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Set<Long> CHAT_DM_SELF_ECHO_MESSAGE_IDS = new HashSet<>();

    private record EventQueueState(String queueId, long lastEventId) {
    }

    private static final class QueueExpiredException extends Exception {
        private QueueExpiredException(String message) {
            super(message);
        }
    }

    private final ZulipBridgeConfig config;
    private volatile boolean running = true;

    public ZulipPollingThread(ZulipBridgeConfig config) {
        super("ZulipBridgePollThread");
        this.config = config;
        setDaemon(true);
    }

    /** Signal the thread to stop at the next request boundary. */
    public void shutdown() {
        running = false;
        interrupt();
    }

    // ── Main loop ─────────────────────────────────────────────────────────────

    @Override
    public void run() {
        LOGGER.info("Zulip polling thread started.");
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();

        EventQueueState queueState = null;

        while (running) {
            try {
                if (queueState == null) {
                    queueState = registerEventQueue(http);
                    LOGGER.info("Registered Zulip event queue {} (last_event_id={}).",
                            queueState.queueId(), queueState.lastEventId());
                }

                queueState = pollEventsAndDisplay(http, queueState);
            } catch (QueueExpiredException e) {
                LOGGER.info("Event queue expired; re-registering: {}", e.getMessage());
                queueState = null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.warn("Event polling error: {}", e.getMessage());
                queueState = null;

                try {
                    int delaySeconds = Math.max(1, config.pollIntervalSeconds());
                    Thread.sleep(delaySeconds * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        LOGGER.info("Zulip polling thread stopped.");
    }

    // ── Event queue polling ───────────────────────────────────────────────────

    private EventQueueState registerEventQueue(HttpClient http) throws Exception {
        String form = "event_types=" + URLEncoder.encode("[\"message\"]", StandardCharsets.UTF_8)
                + "&narrow=" + URLEncoder.encode("[]", StandardCharsets.UTF_8)
                + "&apply_markdown=false"
                + "&client_gravatar=false";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.zulipBaseUrl() + "/api/v1/register"))
                .header("Authorization", buildAuthHeader())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("register HTTP " + resp.statusCode() + ": " + summarizeBody(resp.body()));
        }

        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!"success".equals(body.get("result").getAsString())) {
            throw new IllegalStateException("register failed: " + body.get("msg").getAsString());
        }
        if (!body.has("queue_id") || !body.has("last_event_id")) {
            throw new IllegalStateException("register response missing queue_id/last_event_id");
        }

        return new EventQueueState(
                body.get("queue_id").getAsString(),
                body.get("last_event_id").getAsLong()
        );
    }

    private EventQueueState pollEventsAndDisplay(HttpClient http, EventQueueState queueState) throws Exception {
        String query = "queue_id=" + URLEncoder.encode(queueState.queueId(), StandardCharsets.UTF_8)
                + "&last_event_id=" + queueState.lastEventId()
                + "&dont_block=false";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.zulipBaseUrl() + "/api/v1/events?" + query))
                .header("Authorization", buildAuthHeader())
                .timeout(EVENT_REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            JsonObject errorBody = parseJsonObjectOrNull(resp.body());
            if (errorBody != null && errorBody.has("code")
                    && "BAD_EVENT_QUEUE_ID".equals(errorBody.get("code").getAsString())) {
                String message = errorBody.has("msg") ? errorBody.get("msg").getAsString() : "expired queue";
                throw new QueueExpiredException(message);
            }
            throw new IllegalStateException("events HTTP " + resp.statusCode() + ": " + summarizeBody(resp.body()));
        }

        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!"success".equals(body.get("result").getAsString())) {
            String code = body.has("code") ? body.get("code").getAsString() : "";
            String message = body.has("msg") ? body.get("msg").getAsString() : "unknown error";
            if ("BAD_EVENT_QUEUE_ID".equals(code)) {
                throw new QueueExpiredException(message);
            }
            throw new IllegalStateException("events failed: " + message);
        }

        long lastEventId = queueState.lastEventId();
        JsonArray events = body.has("events") && body.get("events").isJsonArray()
                ? body.getAsJsonArray("events")
                : new JsonArray();
        for (int i = 0; i < events.size(); i++) {
            JsonObject event = events.get(i).getAsJsonObject();
            if (event.has("id")) {
                lastEventId = Math.max(lastEventId, event.get("id").getAsLong());
            }

            if (!event.has("type") || !"message".equals(event.get("type").getAsString())) continue;
            if (!event.has("message") || !event.get("message").isJsonObject()) continue;

            handleIncomingMessage(event, event.getAsJsonObject("message"));
        }

        return new EventQueueState(queueState.queueId(), lastEventId);
    }

    private void handleIncomingMessage(JsonObject event, JsonObject message) {
        String sourceLabel = describeIncomingSource(message);
        String sender = message.has("sender_full_name") ? message.get("sender_full_name").getAsString() : "Zulip";
        String senderEmail = message.has("sender_email") ? message.get("sender_email").getAsString() : "";
        boolean selfMessage = isSelfMessage(senderEmail);
        if (selfMessage && !shouldDisplaySelfMessage(message)) return;
        if (!shouldDisplayIncomingMessage(message)) return;

        String alertTitle = selfMessage ? null : buildAlertTitle(event, message, sourceLabel);
        String content = message.has("content") ? formatIncomingContent(message.get("content").getAsString()) : "";
        displayInChat(sourceLabel, sender, senderEmail, content, alertTitle);
    }

    private String buildAlertTitle(JsonObject event, JsonObject message, String sourceLabel) {
        if (isDirectMessage(message)) {
            return "Direct message";
        }
        if (hasMentionLikeFlag(event) || hasMentionLikeFlag(message)) {
            String source = sourceLabel == null || sourceLabel.isBlank() ? "Zulip" : sourceLabel;
            return "Mention in " + source;
        }
        return null;
    }

    private static boolean isDirectMessage(JsonObject message) {
        return message.has("type") && "private".equals(message.get("type").getAsString());
    }

    private static boolean hasMentionLikeFlag(JsonObject payload) {
        if (!payload.has("flags") || !payload.get("flags").isJsonArray()) return false;
        JsonArray flags = payload.getAsJsonArray("flags");
        for (int i = 0; i < flags.size(); i++) {
            String flag = flags.get(i).getAsString();
            if ("mentioned".equals(flag)
                    || "wildcard_mentioned".equals(flag)
                    || "stream_wildcard_mentioned".equals(flag)
                    || "topic_wildcard_mentioned".equals(flag)
                    || "has_alert_word".equals(flag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStreamMessage(JsonObject message) {
        return message.has("type") && "stream".equals(message.get("type").getAsString());
    }

    private static String streamNameFromMessage(JsonObject message) {
        if (!message.has("display_recipient") || !message.get("display_recipient").isJsonPrimitive()) {
            return "";
        }
        return message.get("display_recipient").getAsString();
    }

    private static boolean shouldDisplayIncomingMessage(JsonObject message) {
        if (!isStreamMessage(message)) return true;
        String streamName = streamNameFromMessage(message);
        return ZulipBridgeScreen.shouldReceiveIncomingStream(streamName);
    }

    private boolean isSelfMessage(String senderEmail) {
        return senderEmail != null
                && !senderEmail.isBlank()
                && senderEmail.equalsIgnoreCase(config.botEmail());
    }

    private boolean shouldDisplaySelfMessage(JsonObject message) {
        if (isDirectMessage(message)) {
            long messageId = extractMessageId(message);
            return messageId > 0 && consumeChatDmSelfEchoMessageId(messageId);
        }
        return !config.suppressSelfEcho();
    }

    private static long extractMessageId(JsonObject message) {
        if (!message.has("id")) return -1;
        try {
            return message.get("id").getAsLong();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static void allowChatDmSelfEchoMessageId(long messageId) {
        synchronized (CHAT_DM_SELF_ECHO_MESSAGE_IDS) {
            CHAT_DM_SELF_ECHO_MESSAGE_IDS.add(messageId);
        }
    }

    private static boolean consumeChatDmSelfEchoMessageId(long messageId) {
        synchronized (CHAT_DM_SELF_ECHO_MESSAGE_IDS) {
            return CHAT_DM_SELF_ECHO_MESSAGE_IDS.remove(messageId);
        }
    }

    private String describeIncomingSource(JsonObject message) {
        String type = message.has("type") ? message.get("type").getAsString() : "";
        if ("stream".equals(type)) {
            String stream = message.has("display_recipient") && message.get("display_recipient").isJsonPrimitive()
                    ? message.get("display_recipient").getAsString()
                    : "unknown-stream";
            String topic = message.has("subject")
                    ? message.get("subject").getAsString()
                    : (message.has("topic") ? message.get("topic").getAsString() : "");
            return topic == null || topic.isBlank()
                    ? "#" + stream
                    : "#" + stream + " > " + topic;
        }

        if ("private".equals(type)) {
            String recipients = describeDirectRecipients(message);
            return recipients.isBlank() ? "DM" : "DM " + recipients;
        }

        return "Zulip";
    }

    private String describeDirectRecipients(JsonObject message) {
        if (!message.has("display_recipient") || !message.get("display_recipient").isJsonArray()) {
            return "";
        }

        JsonArray recipients = message.getAsJsonArray("display_recipient");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < recipients.size(); i++) {
            JsonObject recipient = recipients.get(i).getAsJsonObject();
            String email = recipient.has("email") ? recipient.get("email").getAsString() : "";
            if (!email.isBlank() && email.equalsIgnoreCase(config.botEmail())) continue;

            String fullName = recipient.has("full_name") ? recipient.get("full_name").getAsString() : "";
            if (fullName != null && !fullName.isBlank()) {
                names.add(fullName);
            } else if (!email.isBlank()) {
                names.add(email);
            }
        }

        if (names.isEmpty()) return "";
        return "(" + String.join(", ", names) + ")";
    }

    // ── Chat display ──────────────────────────────────────────────────────────

    private void displayInChat(String sourceLabel, String sender, String senderEmail, String content, String alertTitle) {
        boolean showPrefix = config.showIncomingPrefix();
        String prefix = normalizePrefix(config.incomingPrefix());
        int senderColor = resolveConfiguredSenderColor(config.senderColor());
        int messageColor = resolveConfiguredMessageColor(config.messageColor());

        Style prefixStyle = Style.EMPTY
                .withClickEvent(new ClickEvent.SuggestCommand("/zulip target show"))
                .withHoverEvent(new HoverEvent.ShowText(
                        Text.literal("Incoming: all subscribed channels + DMs\nOutgoing target: "
                                + ZulipBridgeCommandHandler.describeTarget())));

        Text prefixText = Text.literal(prefix).setStyle(prefixStyle.withColor(Formatting.AQUA));

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
        }
        if (sourceLabel != null && !sourceLabel.isBlank()) {
            message.append(Text.literal("[" + sourceLabel + "] ").setStyle(Style.EMPTY.withColor(Formatting.GRAY)));
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
            if (alertTitle != null) {
                notifyIncomingMessage(client, alertTitle, sender, content);
            }
        });
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
        if (prefix == null || prefix.isBlank()) return DEFAULT_TEXT_PREFIX;
        return prefix.endsWith(" ") ? prefix : prefix + " ";
    }

    private static int resolveConfiguredSenderColor(String raw) {
        int parsed = parseHexColor(raw, DEFAULT_SENDER_COLOR);
        return parsed == LEGACY_SENDER_COLOR ? DEFAULT_SENDER_COLOR : parsed;
    }

    private static int resolveConfiguredMessageColor(String raw) {
        int parsed = parseHexColor(raw, DEFAULT_MESSAGE_COLOR);
        return parsed == LEGACY_MESSAGE_COLOR ? DEFAULT_MESSAGE_COLOR : parsed;
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

    private void notifyIncomingMessage(MinecraftClient client, String alertTitle, String sender, String content) {
        if (config.playIncomingSound()) {
            client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }

        if (!config.showIncomingToast()) return;

        String messagePreview = content.length() > 100 ? content.substring(0, 97) + "..." : content;
        SystemToast.add(
                client.getToastManager(),
                SystemToast.Type.PERIODIC_NOTIFICATION,
                Text.literal(alertTitle),
                Text.literal(sender + ": " + messagePreview)
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildAuthHeader() {
        String creds = config.botEmail() + ":" + config.botApiKey();
        return "Basic " + Base64.getEncoder().encodeToString(
                creds.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject parseJsonObjectOrNull(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String summarizeBody(String body) {
        if (body == null || body.isBlank()) return "(empty body)";
        String singleLine = body.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() > 200 ? singleLine.substring(0, 197) + "..." : singleLine;
    }

    private static String[] parseDirectRecipients(String raw) {
        if (raw == null || raw.isBlank()) return new String[0];

        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toArray(String[]::new);
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

                if (cfg.messageTarget() == dev.remgr.zulipbridge.config.ZulipBridgeConfigModel.MessageTarget.DIRECT_MESSAGE) {
                    long sentMessageId = body.has("id") ? body.get("id").getAsLong() : -1;
                    if (sentMessageId > 0) {
                        allowChatDmSelfEchoMessageId(sentMessageId);
                    }
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
