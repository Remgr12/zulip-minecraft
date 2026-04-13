package dev.remgr.zulipbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.remgr.zulipbridge.config.ZulipBridgeConfig;
import dev.remgr.zulipbridge.chat.ChatImageRegistry;
import dev.remgr.zulipbridge.image.ImageCache;
import dev.remgr.zulipbridge.text.CustomEmojiRegistry;
import dev.remgr.zulipbridge.text.EmojiShortcodes;
import dev.remgr.zulipbridge.text.InlineEmoji;
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

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
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
    /** Maps IDs of messages sent by the configured account to their source label (e.g. "general > minecraft"). */
    private static final int OWN_MESSAGE_IDS_MAX = 500;
    private static final Map<Long, String> OWN_MESSAGE_SOURCES = Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    private static final String IMAGE_MARKER_PREFIX = "[[ZULIP_IMG:";
    private static final String IMAGE_MARKER_SUFFIX = "]]";
    private static final Pattern IMAGE_MARKER_PATTERN = Pattern.compile(Pattern.quote(IMAGE_MARKER_PREFIX) + "([^\\]]+)" + Pattern.quote(IMAGE_MARKER_SUFFIX));
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\(((?:[^()]|\\([^)]*\\))+?)\\)");
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern MARKDOWN_QUOTE_PATTERN = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern MARKDOWN_LIST_PATTERN = Pattern.compile("^(\\s*)([-*+]\\s+|\\d+\\.\\s+)(.*)$");
    private static final Pattern SHORTCODE_PATTERN = Pattern.compile(":([a-z0-9_+\\-]+):", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKDOWN_INLINE_TOKEN_PATTERN = Pattern.compile(
            Pattern.quote(IMAGE_MARKER_PREFIX) + "([^\\]]+)" + Pattern.quote(IMAGE_MARKER_SUFFIX)
                    + "|\\[([^\\]]+)]\\(((?:[^()]|\\([^)]*\\))+?)\\)"
                    + "|`([^`]+)`"
                    + "|\\*\\*([^*]+)\\*\\*"
                    + "|~~([^~]+)~~"
                    + "|\\*([^*]+)\\*"
                    + "|:([a-z0-9_+\\-]+):"
                    + "|((https?|ftp)://\\S+)"
    );
    private static final Pattern URL_PATTERN = Pattern.compile("((https?|ftp)://\\S+)");
    private static final Pattern USER_UPLOAD_PATH_PATTERN = Pattern.compile("^/user_uploads/([^/]+)/(.*)$");

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
        String form = "event_types=" + URLEncoder.encode("[\"message\",\"reaction\"]", StandardCharsets.UTF_8)
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

            if (!event.has("type")) continue;
            String eventType = event.get("type").getAsString();

            if ("reaction".equals(eventType)) {
                handleReactionEvent(event);
                continue;
            }

            if (!"message".equals(eventType)) continue;
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

        // Always track own message IDs so reaction events can reference them later.
        if (selfMessage) {
            long ownId = extractMessageId(message);
            if (ownId > 0) {
                synchronized (OWN_MESSAGE_SOURCES) {
                    if (OWN_MESSAGE_SOURCES.size() >= OWN_MESSAGE_IDS_MAX) {
                        OWN_MESSAGE_SOURCES.remove(OWN_MESSAGE_SOURCES.keySet().iterator().next());
                    }
                    OWN_MESSAGE_SOURCES.put(ownId, sourceLabel);
                }
            }
        }

        if (selfMessage && !shouldDisplaySelfMessage(message)) return;
        if (!shouldDisplayIncomingMessage(message)) return;

        String alertTitle = selfMessage ? null : buildAlertTitle(event, message, sourceLabel);
        String rawContent = message.has("content") ? message.get("content").getAsString() : "";
        String messageHash = buildMessageHash(message, sender, rawContent);
        String contentWithImages = processImagesInContent(rawContent, message, messageHash);
        displayInChat(sourceLabel, sender, senderEmail, contentWithImages, alertTitle, messageHash);
    }

    private void handleReactionEvent(JsonObject event) {
        String op = event.has("op") ? event.get("op").getAsString() : "";
        long messageId = event.has("message_id") ? event.get("message_id").getAsLong() : -1;
        if (messageId < 0) return;

        String emojiName = event.has("emoji_name") ? event.get("emoji_name").getAsString() : "";

        // Resolve emoji for display (used in the chat notification).
        String emojiDisplay = EmojiShortcodes.get(emojiName);
        if (emojiDisplay == null) emojiDisplay = ":" + emojiName + ":";

        // Get the reactor's display name.
        String reactorName = "Someone";
        if (event.has("user") && event.get("user").isJsonObject()) {
            JsonObject user = event.getAsJsonObject("user");
            if (user.has("full_name") && !user.get("full_name").getAsString().isBlank()) {
                reactorName = user.get("full_name").getAsString();
            }
        }

        String ownMessageSource = OWN_MESSAGE_SOURCES.get(messageId);
        boolean isOwnMessage = ownMessageSource != null;
        boolean isAdd = "add".equals(op);

        String prefix = normalizePrefix(config.incomingPrefix());
        final String finalEmoji = emojiDisplay;
        final String finalReactor = reactorName;
        final String finalSource = ownMessageSource;
        final String finalEmojiName = emojiName;

        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            // Forward to the GUI screen so its reaction counts update instantly.
            if (client.currentScreen instanceof ZulipBridgeScreen screen) {
                screen.onReactionEvent(messageId, finalEmojiName, op);
            }

            // Show a chat notification only when someone reacts to our own message.
            if (isOwnMessage && isAdd && client.inGameHud != null) {
                net.minecraft.text.MutableText notification = Text.empty();
                if (config.showIncomingPrefix()) {
                    notification.append(Text.literal(prefix).setStyle(Style.EMPTY.withColor(Formatting.AQUA)));
                }
                String location = (finalSource != null && !finalSource.isBlank())
                        ? " to your message in " + finalSource
                        : " to your message";
                notification
                        .append(Text.literal(finalReactor).setStyle(Style.EMPTY.withColor(Formatting.GREEN)))
                        .append(Text.literal(" reacted " + finalEmoji + location)
                                .setStyle(Style.EMPTY.withColor(Formatting.GRAY)));
                client.inGameHud.getChatHud().addMessage(notification);
            }
        });
    }

    /**
     * Registers a sent message ID and its source label so that incoming
     * reaction events can be matched against it and displayed with context.
     *
     * @param messageId the Zulip message ID returned by the send API
     * @param source    human-readable source, e.g. {@code "general > minecraft"} or {@code "DM"}
     */
    public static void addOwnMessageId(long messageId, String source) {
        if (messageId <= 0) return;
        synchronized (OWN_MESSAGE_SOURCES) {
            if (OWN_MESSAGE_SOURCES.size() >= OWN_MESSAGE_IDS_MAX) {
                OWN_MESSAGE_SOURCES.remove(OWN_MESSAGE_SOURCES.keySet().iterator().next());
            }
            OWN_MESSAGE_SOURCES.put(messageId, source == null ? "" : source);
        }
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

    private void displayInChat(String sourceLabel, String sender, String senderEmail, String content, String alertTitle, String messageHash) {
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

        ChatImageRegistry.clearInlineEmojis(messageHash);
        var message = Text.empty().copy();
        if (showPrefix) {
            message.append(prefixText);
        }
        if (sourceLabel != null && !sourceLabel.isBlank()) {
            message.append(Text.literal("[" + sourceLabel + "] ").setStyle(Style.EMPTY.withColor(Formatting.GRAY)));
        }
        message.append(Text.literal(sender).setStyle(senderStyle))
                .append(Text.literal(": ").setStyle(Style.EMPTY.withColor(messageColor)))
                .append(buildIncomingContentText(content, messageColor, messageHash));

        MinecraftClient client = MinecraftClient.getInstance();
        // Must be dispatched on the render thread to avoid threading issues.
        client.execute(() -> {
            if (client.inGameHud != null) {
                ChatImageRegistry.bindDisplayText(messageHash, message);
                client.inGameHud.getChatHud().addMessage(message);
            }
            if (alertTitle != null) {
                notifyIncomingMessage(client, alertTitle, sender, buildNotificationPreview(content));
            }
        });
    }

    private Text buildIncomingContentText(String content, int color, String inlineEmojiMessageHash) {
        String normalizedContent = EmojiShortcodes.replace(content);
        return switch (config.incomingMessageFormat()) {
            case RAW_MARKDOWN -> buildRawContentText(normalizedContent, color, inlineEmojiMessageHash);
            case PLAIN_TEXT -> buildRawContentText(stripMarkdownFormatting(normalizedContent), color, inlineEmojiMessageHash);
            case MARKDOWN -> buildMarkdownContentText(normalizedContent, color, inlineEmojiMessageHash);
        };
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return DEFAULT_TEXT_PREFIX;
        return prefix.endsWith(" ") ? prefix : prefix + " ";
    }

    private Text buildRawContentText(String content, int color, String inlineEmojiMessageHash) {
        String safeContent = content == null ? "" : content;
        int index = 0;
        net.minecraft.text.MutableText result = Text.empty();
        while (index < safeContent.length()) {
            int markerStart = safeContent.indexOf(IMAGE_MARKER_PREFIX, index);
            Matcher shortcodeMatcher = SHORTCODE_PATTERN.matcher(safeContent);
            boolean foundShortcode = shortcodeMatcher.find(index);
            int shortcodeStart = foundShortcode ? shortcodeMatcher.start() : -1;
            Matcher urlMatcher = URL_PATTERN.matcher(safeContent);
            boolean foundUrl = urlMatcher.find(index);
            int urlStart = foundUrl ? urlMatcher.start() : -1;

            int nextStart = -1;
            String tokenType = null;
            if (markerStart >= 0) {
                nextStart = markerStart;
                tokenType = "marker";
            }
            if (shortcodeStart >= 0 && (nextStart < 0 || shortcodeStart < nextStart)) {
                nextStart = shortcodeStart;
                tokenType = "shortcode";
            }
            if (urlStart >= 0 && (nextStart < 0 || urlStart < nextStart)) {
                nextStart = urlStart;
                tokenType = "url";
            }

            if (nextStart < 0) {
                appendStyledLiteral(result, safeContent.substring(index), Style.EMPTY.withColor(color));
                break;
            }

            if (nextStart > index) {
                appendStyledLiteral(result, safeContent.substring(index, nextStart), Style.EMPTY.withColor(color));
            }

            if ("marker".equals(tokenType)) {
                int markerEnd = safeContent.indexOf(IMAGE_MARKER_SUFFIX, markerStart + IMAGE_MARKER_PREFIX.length());
                if (markerEnd < 0) {
                    appendStyledLiteral(result, safeContent.substring(markerStart), Style.EMPTY.withColor(color));
                    break;
                }

                String imageHash = safeContent.substring(markerStart + IMAGE_MARKER_PREFIX.length(), markerEnd);
                appendImagePreviewToken(result, imageHash);
                index = markerEnd + IMAGE_MARKER_SUFFIX.length();
                continue;
            }

            if ("shortcode".equals(tokenType)) {
                if (appendCustomEmojiToken(result, shortcodeMatcher.group(1), Style.EMPTY.withColor(color), inlineEmojiMessageHash)) {
                    index = shortcodeMatcher.end();
                    continue;
                }

                appendStyledLiteral(result, ":" + shortcodeMatcher.group(1) + ":", Style.EMPTY.withColor(color));
                index = shortcodeMatcher.end();
                continue;
            }

            String url = urlMatcher.group(1);
            appendUrl(result, url, Style.EMPTY.withColor(color));
            index = urlMatcher.end();
        }

        return result;
    }

    private Text buildMarkdownContentText(String content, int color, String inlineEmojiMessageHash) {
        String safeContent = content == null ? "" : content;
        String[] lines = safeContent.split("\\R", -1);
        net.minecraft.text.MutableText result = Text.empty();
        boolean inCodeBlock = false;

        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append(Text.literal("\n").setStyle(Style.EMPTY.withColor(color)));
            }

            String line = lines[i];
            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }

            net.minecraft.text.MutableText lineText = Text.empty();
            if (inCodeBlock) {
                appendStyledLiteral(lineText, line, Style.EMPTY.withColor(0xD7BA7D));
                result.append(lineText);
                continue;
            }

            Matcher headingMatcher = MARKDOWN_HEADING_PATTERN.matcher(line);
            if (headingMatcher.matches()) {
                int level = headingMatcher.group(1).length();
                appendMarkdownInlineText(lineText, headingMatcher.group(2), headingStyle(level, color), inlineEmojiMessageHash);
                result.append(lineText);
                continue;
            }

            Matcher quoteMatcher = MARKDOWN_QUOTE_PATTERN.matcher(line);
            if (quoteMatcher.matches()) {
                appendStyledLiteral(lineText, "| ", Style.EMPTY.withColor(0x7F8EA8));
                appendMarkdownInlineText(lineText, quoteMatcher.group(1), Style.EMPTY.withColor(0xC8D4E3).withItalic(true), inlineEmojiMessageHash);
                result.append(lineText);
                continue;
            }

            Matcher listMatcher = MARKDOWN_LIST_PATTERN.matcher(line);
            if (listMatcher.matches()) {
                String marker = listMatcher.group(2).trim();
                String prefix = Character.isDigit(marker.charAt(0)) ? marker + " " : "• ";
                appendStyledLiteral(lineText, prefix, Style.EMPTY.withColor(0x9FB3D9));
                appendMarkdownInlineText(lineText, listMatcher.group(3), Style.EMPTY.withColor(color), inlineEmojiMessageHash);
                result.append(lineText);
                continue;
            }

            appendMarkdownInlineText(lineText, line, Style.EMPTY.withColor(color), inlineEmojiMessageHash);
            result.append(lineText);
        }

        return result;
    }

    private void appendMarkdownInlineText(net.minecraft.text.MutableText result, String content, Style baseStyle, String inlineEmojiMessageHash) {
        if (content == null || content.isEmpty()) return;

        Matcher matcher = MARKDOWN_INLINE_TOKEN_PATTERN.matcher(content);
        int index = 0;
        while (matcher.find()) {
            if (matcher.start() > index) {
                appendStyledLiteral(result, content.substring(index, matcher.start()), baseStyle);
            }

            if (matcher.group(1) != null) {
                appendImagePreviewToken(result, matcher.group(1));
            } else if (matcher.group(2) != null) {
                appendMarkdownLink(result, matcher.group(2), matcher.group(3), baseStyle);
            } else if (matcher.group(4) != null) {
                appendStyledLiteral(result, matcher.group(4), baseStyle.withColor(0xD7BA7D));
            } else if (matcher.group(5) != null) {
                appendMarkdownInlineText(result, matcher.group(5), baseStyle.withBold(true), inlineEmojiMessageHash);
            } else if (matcher.group(6) != null) {
                appendMarkdownInlineText(result, matcher.group(6), baseStyle.withStrikethrough(true), inlineEmojiMessageHash);
            } else if (matcher.group(7) != null) {
                appendMarkdownInlineText(result, matcher.group(7), baseStyle.withItalic(true), inlineEmojiMessageHash);
            } else if (matcher.group(8) != null) {
                if (!appendCustomEmojiToken(result, matcher.group(8), baseStyle, inlineEmojiMessageHash)) {
                    appendStyledLiteral(result, ":" + matcher.group(8) + ":", baseStyle);
                }
            } else if (matcher.group(9) != null) {
                appendUrl(result, matcher.group(9), baseStyle);
            }

            index = matcher.end();
        }

        if (index < content.length()) {
            appendStyledLiteral(result, content.substring(index), baseStyle);
        }
    }

    private void appendMarkdownLink(net.minecraft.text.MutableText result, String label, String rawUrl, Style baseStyle) {
        String resolvedUrl = resolveMarkdownLinkUrl(rawUrl);
        if (resolvedUrl == null || resolvedUrl.isBlank()) {
            appendStyledLiteral(result, label, baseStyle);
            return;
        }

        try {
            result.append(Text.literal(label).setStyle(baseStyle.withColor(0x77B7FF).withUnderline(true)
                    .withClickEvent(new ClickEvent.OpenUrl(URI.create(resolvedUrl)))
                    .withHoverEvent(new HoverEvent.ShowText(Text.literal("Open \"" + resolvedUrl + "\"")))));
        } catch (IllegalArgumentException exception) {
            appendStyledLiteral(result, label, baseStyle);
        }
    }

    private void appendUrl(net.minecraft.text.MutableText result, String url, Style baseStyle) {
        try {
            result.append(Text.literal(url).setStyle(baseStyle.withColor(0x77B7FF).withUnderline(true)
                    .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                    .withHoverEvent(new HoverEvent.ShowText(Text.literal("Open \"" + url + "\"")))));
        } catch (IllegalArgumentException exception) {
            appendStyledLiteral(result, url, baseStyle);
        }
    }

    private static void appendStyledLiteral(net.minecraft.text.MutableText result, String value, Style style) {
        if (value == null || value.isEmpty()) return;
        result.append(Text.literal(value).setStyle(style));
    }

    private boolean appendCustomEmojiToken(net.minecraft.text.MutableText result, String shortcode, Style baseStyle, String inlineEmojiMessageHash) {
        CustomEmojiRegistry.CustomEmoji customEmoji = CustomEmojiRegistry.get(shortcode);
        if (customEmoji == null) return false;

        String imageHash = resolveImageHash(customEmoji.url());
        ChatImageRegistry.addInlineEmoji(inlineEmojiMessageHash, imageHash);
        result.append(Text.literal(InlineEmoji.PLACEHOLDER_TEXT).setStyle(baseStyle.withClickEvent(new ClickEvent.RunCommand("/zulip preview " + imageHash))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("Preview custom emoji")))));
        return true;
    }

    private static Style headingStyle(int level, int fallbackColor) {
        int color = switch (level) {
            case 1 -> 0x9CDCFE;
            case 2 -> 0xB5CEA8;
            case 3 -> 0xDCDCAA;
            case 4 -> 0xC586C0;
            default -> fallbackColor;
        };
        return Style.EMPTY.withColor(color).withBold(true);
    }

    private String buildNotificationPreview(String content) {
        String preview = EmojiShortcodes.replaceCustomWithLabels(EmojiShortcodes.replace(stripMarkdownFormatting(content))).replace('\n', ' ').replace('\r', ' ').trim();
        preview = preview.replaceAll("\\s+", " ");
        return preview.length() > 100 ? preview.substring(0, 97) + "..." : preview;
    }

    private static String stripMarkdownFormatting(String content) {
        if (content == null || content.isEmpty()) return "";

        String plain = IMAGE_MARKER_PATTERN.matcher(content).replaceAll(" [image]");
        plain = MARKDOWN_LINK_PATTERN.matcher(plain).replaceAll("$1");
        plain = plain.replaceAll("(?m)^#{1,6}\\s+", "");
        plain = plain.replaceAll("(?m)^>\\s?", "");
        plain = plain.replaceAll("(?m)^\\s*([-*+]\\s+|\\d+\\.\\s+)", "");
        plain = plain.replace("```", "");
        plain = plain.replaceAll("\\*\\*(.*?)\\*\\*", "$1");
        plain = plain.replaceAll("~~(.*?)~~", "$1");
        plain = plain.replaceAll("\\*(.*?)\\*", "$1");
        plain = plain.replaceAll("`(.*?)`", "$1");
        return plain;
    }

    private static void appendImagePreviewToken(net.minecraft.text.MutableText result, String imageHash) {
        result.append(Text.literal("[image]").setStyle(Style.EMPTY.withColor(0x77B7FF).withUnderline(true)
                .withClickEvent(new ClickEvent.RunCommand("/zulip preview " + imageHash))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to preview")))));
    }

    private String processImagesInContent(String content, JsonObject message, String messageHash) {
        LinkedHashSet<String> imageUrls = collectImageUrls(content, message);
        LinkedHashSet<String> inlineUrls = new LinkedHashSet<>();
        String contentWithMarkdownImages = replaceMarkdownImageLinks(content, messageHash, inlineUrls);

        Matcher shortcodeMatcher = SHORTCODE_PATTERN.matcher(content == null ? "" : content);
        while (shortcodeMatcher.find()) {
            CustomEmojiRegistry.CustomEmoji customEmoji = CustomEmojiRegistry.get(shortcodeMatcher.group(1));
            if (customEmoji != null) {
                scheduleEmojiImageLoad(shortcodeMatcher.group(1).toLowerCase(), customEmoji.url());
            }
        }

        for (String imageUrl : imageUrls) {
            if (inlineUrls.contains(imageUrl)) continue;
            contentWithMarkdownImages += ' ' + imageMarker(resolveImageHash(imageUrl));
            scheduleImageLoad(messageHash, imageUrl);
        }

        return contentWithMarkdownImages;
    }

    private LinkedHashSet<String> collectImageUrls(String content, JsonObject message) {
        LinkedHashSet<String> imageUrls = new LinkedHashSet<>();

        Matcher markdownMatcher = MARKDOWN_LINK_PATTERN.matcher(content == null ? "" : content);
        while (markdownMatcher.find()) {
            String resolvedUrl = resolveMarkdownImageUrl(markdownMatcher.group(1), markdownMatcher.group(2));
            if (resolvedUrl != null) imageUrls.add(resolvedUrl);
        }

        if (message.has("attachments") && message.get("attachments").isJsonArray()) {
            JsonArray attachments = message.getAsJsonArray("attachments");
            for (int i = 0; i < attachments.size(); i++) {
                if (!attachments.get(i).isJsonObject()) continue;
                JsonObject attachment = attachments.get(i).getAsJsonObject();
                String url = attachment.has("url") ? attachment.get("url").getAsString() : "";
                String contentType = attachment.has("content_type") ? attachment.get("content_type").getAsString() : "";
                String fileName = attachment.has("name") ? attachment.get("name").getAsString() : "";
                if (url.isBlank()) continue;
                if (contentType.startsWith("image/") || looksLikeImageFile(fileName) || looksLikeImageFile(url)) {
                    imageUrls.add(resolveImageUrl(url));
                }
            }
        }

        return imageUrls;
    }

    private void scheduleImageLoad(String messageHash, String imageUrl) {
        scheduleImageLoad(messageHash, imageUrl, false);
    }

    private void scheduleEmojiImageLoad(String shortcode, String imageUrl) {
        scheduleImageLoad("emoji:" + shortcode, imageUrl, true);
    }

    private void scheduleImageLoad(String messageHash, String imageUrl, boolean withAuth) {
        String imageHash = resolveImageHash(imageUrl);
        Thread.ofVirtual().name("ZulipImageDownload-" + messageHash + '-' + imageHash).start(() -> {
            try {
                HttpClient httpClient = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(REQUEST_TIMEOUT)
                        .build();
                String downloadUrl = withAuth ? imageUrl : resolveImageDownloadUrl(httpClient, imageUrl);
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(downloadUrl))
                        .timeout(REQUEST_TIMEOUT)
                        .GET();
                if (withAuth) {
                    requestBuilder.header("Authorization", buildAuthHeader());
                }
                HttpRequest request = requestBuilder.build();

                httpClient
                        .sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                        .thenAccept(response -> {
                            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                                LOGGER.warn("Image download failed for {} (resolved to {}): HTTP {}", imageUrl, downloadUrl, response.statusCode());
                                return;
                            }

                            byte[] bytes = response.body();
                            String contentType = response.headers().firstValue("Content-Type").orElse("");
                            boolean gif = contentType.toLowerCase().endsWith("/gif") || imageUrl.toLowerCase().endsWith(".gif");
                            MinecraftClient.getInstance().execute(() -> {
                                ImageCache.CachedImage image = ImageCache.getOrLoad(imageHash, bytes, imageUrl, gif);
                                if (image == null) return;
                            });
                        })
                        .exceptionally(throwable -> {
                            LOGGER.warn("Image download failed for {}: {}", imageUrl, throwable.getMessage());
                            return null;
                        });
            } catch (Exception e) {
                LOGGER.warn("Image download failed for {}: {}", imageUrl, e.getMessage());
            }
        });
    }

    private String resolveImageDownloadUrl(HttpClient httpClient, String imageUrl) throws IOException, InterruptedException {
        URI uri = URI.create(imageUrl);
        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.isBlank()) return imageUrl;

        String pathId = extractUserUploadPathId(rawPath);
        if (pathId == null) {
            pathId = lookupAttachmentPathId(httpClient, rawPath);
        }
        if (pathId == null) return imageUrl;

        return fetchTemporaryUploadUrl(httpClient, pathId);
    }

    private static String extractUserUploadPathId(String rawPath) {
        Matcher matcher = USER_UPLOAD_PATH_PATTERN.matcher(rawPath);
        if (!matcher.matches()) return null;
        return matcher.group(1) + "/" + matcher.group(2);
    }

    private String lookupAttachmentPathId(HttpClient httpClient, String rawPath) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.zulipBaseUrl() + "/api/v1/attachments"))
                .header("Authorization", buildAuthHeader())
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("attachments HTTP " + response.statusCode());
        }

        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!"success".equals(body.get("result").getAsString()) || !body.has("attachments") || !body.get("attachments").isJsonArray()) {
            throw new IOException("attachments lookup failed: " + summarizeBody(response.body()));
        }

        JsonArray attachments = body.getAsJsonArray("attachments");
        for (int i = 0; i < attachments.size(); i++) {
            if (!attachments.get(i).isJsonObject()) continue;
            JsonObject attachment = attachments.get(i).getAsJsonObject();
            if (!attachment.has("path_id")) continue;

            String pathId = attachment.get("path_id").getAsString();
            if (rawPath.equals("/user_uploads/" + pathId)) {
                return pathId;
            }
        }

        return null;
    }

    private String fetchTemporaryUploadUrl(HttpClient httpClient, String pathId) throws IOException, InterruptedException {
        int slash = pathId.indexOf('/');
        if (slash <= 0 || slash == pathId.length() - 1) {
            throw new IOException("invalid upload path id: " + pathId);
        }

        String realmId = pathId.substring(0, slash);
        String filename = pathId.substring(slash + 1);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.zulipBaseUrl() + "/api/v1/user_uploads/" + realmId + "/" + filename))
                .header("Authorization", buildAuthHeader())
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("temporary upload URL HTTP " + response.statusCode());
        }

        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!"success".equals(body.get("result").getAsString()) || !body.has("url")) {
            throw new IOException("temporary upload URL lookup failed: " + summarizeBody(response.body()));
        }

        return resolveImageUrl(body.get("url").getAsString());
    }

    private String buildMessageHash(JsonObject message, String sender, String rawHtml) {
        long messageId = extractMessageId(message);
        if (messageId > 0) {
            return Long.toUnsignedString(messageId, 16);
        }
        return Integer.toHexString((sender + '\n' + rawHtml).hashCode());
    }

    private String resolveImageHash(String imageUrl) {
        return ImageCache.hashUrl(imageUrl);
    }

    private String resolveImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) return "";
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) return imageUrl;
        if (imageUrl.startsWith("/")) return config.zulipBaseUrl() + imageUrl;
        return config.zulipBaseUrl() + "/" + imageUrl;
    }

    private static boolean looksLikeImageFile(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase();
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".gif")
                || lower.endsWith(".webp")
                || lower.endsWith(".bmp");
    }

    private String replaceMarkdownImageLinks(String content, String messageHash, Set<String> inlineUrls) {
        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(content);
        StringBuilder result = new StringBuilder();
        int last = 0;

        while (matcher.find()) {
            String resolvedUrl = resolveMarkdownImageUrl(matcher.group(1), matcher.group(2));
            if (resolvedUrl == null) {
                continue;
            }

            inlineUrls.add(resolvedUrl);
            result.append(content, last, matcher.start());
            result.append(imageMarker(resolveImageHash(resolvedUrl)));
            scheduleImageLoad(messageHash, resolvedUrl);
            last = matcher.end();
        }

        if (last == 0) {
            return content;
        }

        result.append(content.substring(last));
        return result.toString();
    }

    private static boolean looksLikeImageReference(String label, String url) {
        if (looksLikeImageFile(label) || looksLikeImageFile(url)) {
            return true;
        }

        String lowerUrl = url == null ? "" : url.toLowerCase();
        return lowerUrl.contains("/user_uploads/") && !lowerUrl.endsWith(".pdf");
    }

    private String resolveMarkdownImageUrl(String label, String url) {
        String normalizedUrl = normalizeMarkdownLinkUrl(url);
        if (!looksLikeImageReference(label, normalizedUrl)) return null;
        return resolveImageUrl(normalizedUrl);
    }

    private String resolveMarkdownLinkUrl(String url) {
        String normalizedUrl = normalizeMarkdownLinkUrl(url);
        if (normalizedUrl.isBlank()) return "";
        return resolveImageUrl(normalizedUrl);
    }

    private static String normalizeMarkdownLinkUrl(String url) {
        if (url == null) return "";

        String normalized = url.trim();
        int titleSeparator = normalized.indexOf(" \"");
        if (titleSeparator >= 0) {
            normalized = normalized.substring(0, titleSeparator).trim();
        }

        if (normalized.startsWith("<") && normalized.endsWith(">") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }

        return normalized;
    }

    private static String imageMarker(String imageHash) {
        return IMAGE_MARKER_PREFIX + imageHash + IMAGE_MARKER_SUFFIX;
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

                long sentMessageId = body.has("id") ? body.get("id").getAsLong() : -1;
                if (sentMessageId > 0) {
                    String source = cfg.messageTarget() == dev.remgr.zulipbridge.config.ZulipBridgeConfigModel.MessageTarget.DIRECT_MESSAGE
                            ? "DM"
                            : "#" + cfg.streamName() + " > " + cfg.topicName();
                    addOwnMessageId(sentMessageId, source);
                    if (cfg.messageTarget() == dev.remgr.zulipbridge.config.ZulipBridgeConfigModel.MessageTarget.DIRECT_MESSAGE) {
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
