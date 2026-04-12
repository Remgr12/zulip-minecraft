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
import dev.remgr.zulipbridge.ZulipBridgeScreen;
import dev.remgr.zulipbridge.ZulipBridgeCommandHandler;
import dev.remgr.zulipbridge.image.ImageCache;
import dev.remgr.zulipbridge.chat.ChatImageThumbnail;
import dev.remgr.zulipbridge.chat.ChatImageRegistry;
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
    private static final Pattern INLINE_IMAGE_PATTERN = Pattern.compile("<img\\s+[^>]*src=\"([^\"]+)\"[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\(((?:[^()]|\\([^)]*\\))+?)\\)");
    private static final Pattern URL_PATTERN = Pattern.compile("((https?|ftp)://\\S+)");
    private static final Pattern USER_UPLOAD_PATH_PATTERN = Pattern.compile("^/user_uploads/([^/]+)/(.*)$");
    private static final String IMAGE_MARKER_PREFIX = "[[ZULIP_IMG:";
    private static final String IMAGE_MARKER_SUFFIX = "]]";

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
        String rawContent = message.has("content") ? message.get("content").getAsString() : "";
        String messageHash = buildMessageHash(message, sender, rawContent);
        String contentWithImages = processImagesInContent(rawContent, rawContent, message, messageHash);
        String content = formatIncomingContent(contentWithImages);
        displayInChat(sourceLabel, sender, senderEmail, content, alertTitle, messageHash);
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

        var message = Text.empty().copy();
        if (showPrefix) {
            message.append(prefixText);
        }
        if (sourceLabel != null && !sourceLabel.isBlank()) {
            message.append(Text.literal("[" + sourceLabel + "] ").setStyle(Style.EMPTY.withColor(Formatting.GRAY)));
        }
        message.append(Text.literal(sender).setStyle(senderStyle))
                .append(Text.literal(": ").setStyle(Style.EMPTY.withColor(messageColor)))
                .append(buildClickableContentText(content, messageColor));

        MinecraftClient client = MinecraftClient.getInstance();
        // Must be dispatched on the render thread to avoid threading issues.
        client.execute(() -> {
            if (client.inGameHud != null) {
                ChatImageRegistry.bindDisplayText(messageHash, message);
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

    // Build a Text component with clickable URL segments
    private Text buildClickableContentText(String content, int color) {
        int index = 0;
        net.minecraft.text.MutableText result = Text.empty();
        while (index < content.length()) {
            int markerStart = content.indexOf(IMAGE_MARKER_PREFIX, index);
            Matcher markdownImageMatcher = MARKDOWN_LINK_PATTERN.matcher(content);
            boolean foundMarkdownImage = markdownImageMatcher.find(index);
            int markdownImageStart = foundMarkdownImage && looksLikeImageReference(markdownImageMatcher.group(1), markdownImageMatcher.group(2))
                    ? markdownImageMatcher.start()
                    : -1;
            Matcher urlMatcher = URL_PATTERN.matcher(content);
            boolean foundUrl = urlMatcher.find(index);
            int urlStart = foundUrl ? urlMatcher.start() : -1;

            int nextStart = -1;
            String tokenType = null;
            if (markerStart >= 0) {
                nextStart = markerStart;
                tokenType = "marker";
            }
            if (markdownImageStart >= 0 && (nextStart < 0 || markdownImageStart < nextStart)) {
                nextStart = markdownImageStart;
                tokenType = "markdownImage";
            }
            if (urlStart >= 0 && (nextStart < 0 || urlStart < nextStart)) {
                nextStart = urlStart;
                tokenType = "url";
            }

            if (nextStart < 0) {
                result.append(Text.literal(content.substring(index)).setStyle(Style.EMPTY.withColor(color)));
                break;
            }

            if (nextStart > index) {
                result.append(Text.literal(content.substring(index, nextStart)).setStyle(Style.EMPTY.withColor(color)));
            }

            if ("marker".equals(tokenType)) {
                int markerEnd = content.indexOf(IMAGE_MARKER_SUFFIX, markerStart + IMAGE_MARKER_PREFIX.length());
                if (markerEnd < 0) {
                    result.append(Text.literal(content.substring(markerStart)).setStyle(Style.EMPTY.withColor(color)));
                    break;
                }

                String imageHash = content.substring(markerStart + IMAGE_MARKER_PREFIX.length(), markerEnd);
                appendImagePreviewToken(result, imageHash);
                index = markerEnd + IMAGE_MARKER_SUFFIX.length();
                continue;
            }

            if ("markdownImage".equals(tokenType)) {
                String resolvedUrl = resolveMarkdownImageUrl(markdownImageMatcher.group(1), markdownImageMatcher.group(2));
                if (resolvedUrl != null) {
                    appendImagePreviewToken(result, resolveImageHash(resolvedUrl));
                    index = markdownImageMatcher.end();
                    continue;
                }
            }

            String url = urlMatcher.group(1);
            result.append(Text.literal(url).setStyle(Style.EMPTY.withColor(0x3366FF).withUnderline(true)
                    .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                    .withHoverEvent(new HoverEvent.ShowText(Text.literal("Open \"" + url + "\"")))));
            index = urlMatcher.end();
        }

        return result;
    }

    private static void appendImagePreviewToken(net.minecraft.text.MutableText result, String imageHash) {
        result.append(Text.literal(" "));
        result.append(Text.literal("img").setStyle(Style.EMPTY.withColor(0x3366FF).withUnderline(true)
                .withClickEvent(new ClickEvent.RunCommand("/zulip preview " + imageHash))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to preview")))));
    }

    private String processImagesInContent(String formattedContent, String rawHtml, JsonObject message, String messageHash) {
        LinkedHashSet<String> imageUrls = collectImageUrls(rawHtml, message);
        Matcher matcher = INLINE_IMAGE_PATTERN.matcher(formattedContent);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        LinkedHashSet<String> inlineUrls = new LinkedHashSet<>();

        while (matcher.find()) {
            String resolvedUrl = resolveImageUrl(matcher.group(1));
            inlineUrls.add(resolvedUrl);
            sb.append(formattedContent, last, matcher.start());
            sb.append(imageMarker(resolveImageHash(resolvedUrl)));
            scheduleImageLoad(messageHash, resolvedUrl);
            last = matcher.end();
        }

        sb.append(formattedContent.substring(last));
        String contentWithInlineImages = sb.toString();
        String contentWithMarkdownImages = replaceMarkdownImageLinks(contentWithInlineImages, messageHash, inlineUrls);

        for (String imageUrl : imageUrls) {
            if (inlineUrls.contains(imageUrl)) continue;
            contentWithMarkdownImages += ' ' + imageMarker(resolveImageHash(imageUrl));
            scheduleImageLoad(messageHash, imageUrl);
        }

        return contentWithMarkdownImages;
    }

    private LinkedHashSet<String> collectImageUrls(String rawHtml, JsonObject message) {
        LinkedHashSet<String> imageUrls = new LinkedHashSet<>();

        Matcher matcher = INLINE_IMAGE_PATTERN.matcher(rawHtml == null ? "" : rawHtml);
        while (matcher.find()) {
            imageUrls.add(resolveImageUrl(matcher.group(1)));
        }

        Matcher markdownMatcher = MARKDOWN_LINK_PATTERN.matcher(rawHtml == null ? "" : rawHtml);
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
        String imageHash = resolveImageHash(imageUrl);
        Thread.ofVirtual().name("ZulipImageDownload-" + messageHash + '-' + imageHash).start(() -> {
            try {
                HttpClient httpClient = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(REQUEST_TIMEOUT)
                        .build();
                String downloadUrl = resolveImageDownloadUrl(httpClient, imageUrl);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(downloadUrl))
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build();

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

                                ChatImageRegistry.addThumbnail(
                                        messageHash,
                                        new ChatImageThumbnail(imageHash)
                                );
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
