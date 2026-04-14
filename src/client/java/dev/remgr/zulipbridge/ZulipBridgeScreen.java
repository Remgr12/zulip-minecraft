package dev.remgr.zulipbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.remgr.zulipbridge.config.ZulipBridgeConfig;
import dev.remgr.zulipbridge.image.ImageCache;
import dev.remgr.zulipbridge.image.ImageRenderer;
import dev.remgr.zulipbridge.image.PreviewHud;
import dev.remgr.zulipbridge.text.CustomEmojiRegistry;
import dev.remgr.zulipbridge.text.EmojiShortcodes;
import dev.remgr.zulipbridge.text.InlineEmoji;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-game browser for Zulip streams and recent messages.
 */
public class ZulipBridgeScreen extends Screen {

    private static final int TOP_MARGIN = 8;
    private static final int RIGHT_MARGIN = 8;
    private static final int LEFT_MARGIN = 8;
    private static final int BOTTOM_MARGIN = 8;
    private static final int HEADER_HEIGHT = 20;
    private static final int TAB_HEIGHT = 24;
    private static final int STATUS_HEIGHT = 12;
    private static final int CONTENT_GAP = 6;
    private static final int PANEL_HEADER_HEIGHT = 14;
    private static final int INPUT_HEIGHT = 20;
    private static final int PANEL_GAP = 8;
    private static final int CHANNEL_ROW_HEIGHT = 12;
    private static final int MESSAGE_LINE_HEIGHT = 10;
    private static final int GUI_IMAGE_MAX_SIZE = 72;
    private static final int GUI_IMAGE_ROW_SPAN = 8;
    private static final int MAX_HISTORY_MESSAGES = 100;
    private static final int MAX_MENTION_SUGGESTIONS = 6;
    private static final int MENTION_SUGGESTION_ROW_HEIGHT = 14;
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\(((?:[^()]|\\([^)]*\\))+?)\\)");
    private static final Pattern SHORTCODE_PATTERN = Pattern.compile(":([a-z0-9_+\\-]+):", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://\\S+)");
    private static final Pattern USER_UPLOAD_PATH_PATTERN = Pattern.compile("^/user_uploads/([^/]+)/(.*)$");
    private static final Set<String> PERSISTED_POLLED_CHANNELS = new HashSet<>();
    private static boolean PERSISTED_POLL_ALL_CONVERSATIONS = false;

    private final ZulipBridgeConfig config;
    private final HttpClient httpClient;

    private final List<ChannelEntry> channels = new ArrayList<>();
    private final List<ChannelListRow> channelRows = new ArrayList<>();
    private final List<UserEntry> users = new ArrayList<>();
    private final List<ChannelListRow> userRows = new ArrayList<>();
    private final List<ZulipMessage> messages = new ArrayList<>();
    private final List<DisplayLine> renderedMessageLines = new ArrayList<>();
    private final List<PreviewableImage> previewableImages = new ArrayList<>();

    private TextFieldWidget topicField;
    private TextFieldWidget messageField;
    private TextFieldWidget newRecipientField;
    private ButtonWidget sendButton;
    private ButtonWidget refreshButton;
    private ButtonWidget channelsTabButton;
    private ButtonWidget directMessagesTabButton;
    private ButtonWidget composeNewDmButton;

    private String selectedChannel;
    private String selectedUserId;
    private String selectedUserName;
    private boolean showingDirectMessages = false;
    private boolean composingNewDm = false;
    private String statusMessage = "";
    private long lastPollTime = 0;
    private long lastDmPollTime = 0;
    private long reactingToMessageId = 0;
    private ReactionButton visibleReactionButton = null;
    private static final long POLL_INTERVAL_MS = 3000;
    private static final long DM_POLL_INTERVAL_MS = 10000;
    private final Map<String, Long> latestDmMessageIds = new HashMap<>();
    private final Set<String> unreadDms = new HashSet<>();
    private final Map<String, Long> latestChannelMessageIds = new HashMap<>();
    private final Set<String> unreadChannels = new HashSet<>();
    private final Set<String> polledChannels = new HashSet<>();
    private boolean pollAllConversations = false;
    private boolean showingPollSettings = false;
    private boolean loadingChannels = false;
    private boolean loadingMessages = false;
    private boolean sendingMessage = false;
    private boolean mentionUserLoadRequested = false;
    private int channelScroll = 0;
    private int messageScroll = 0;
    private final List<AutocompleteSuggestion> mentionSuggestions = new ArrayList<>();
    private int mentionTokenStart = -1;
    private int mentionTokenEnd = -1;
    private int mentionSuggestionsX = 0;
    private int mentionSuggestionsY = 0;
    private int mentionSuggestionsWidth = 0;

    private int channelsX;
    private int channelsY;
    private int channelsWidth;
    private int channelsHeight;
    private int messagesX;
    private int messagesY;
    private int messagesWidth;
    private int messagesHeight;

    public ZulipBridgeScreen() {
        super(Text.literal("Zulip Bridge"));
        this.config = ZulipBridgeClient.CONFIG;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        synchronized (ZulipBridgeScreen.class) {
            PERSISTED_POLL_ALL_CONVERSATIONS = false;
            this.pollAllConversations = false;
            this.polledChannels.addAll(PERSISTED_POLLED_CHANNELS);
        }
    }

    @Override
    protected void init() {
        CustomEmojiRegistry.refreshAsync(this.config, () ->
                net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
                    if (!this.messages.isEmpty()) this.rebuildMessageLines();
                }));
        String previousTopic = this.topicField != null ? this.topicField.getText() : "";
        String previousMessage = this.messageField != null ? this.messageField.getText() : "";

        this.clearChildren();
        this.recalculateLayout();

        int composeY = this.height - BOTTOM_MARGIN - INPUT_HEIGHT;
        int sendWidth = 64;
        int spacing = 6;
        
        int topicWidth;
        int messageWidth;

        topicWidth = Math.min(220, Math.max(90, this.messagesWidth / 3));
        messageWidth = this.messagesWidth - topicWidth - sendWidth - (spacing * 2);
        if (messageWidth < 80) {
            messageWidth = 80;
            topicWidth = Math.max(80, this.messagesWidth - messageWidth - sendWidth - (spacing * 2));
        }

        this.topicField = new TextFieldWidget(
                this.textRenderer,
                this.messagesX,
                composeY,
                topicWidth,
                INPUT_HEIGHT,
                Text.literal("Topic")
        );
        this.topicField.setMaxLength(120);
        this.topicField.setText(previousTopic.isBlank() ? defaultTopic() : previousTopic);
        this.topicField.setPlaceholder(Text.literal("Topic"));
        this.addDrawableChild(this.topicField);

        int messageX = this.messagesX + topicWidth + spacing;
        this.messageField = new TextFieldWidget(
                this.textRenderer,
                messageX,
                composeY,
                messageWidth,
                INPUT_HEIGHT,
                Text.literal("Message")
        );
        this.messageField.setMaxLength(4000);
        this.messageField.setText(previousMessage);
        this.messageField.setPlaceholder(Text.literal("Message"));
        this.addDrawableChild(this.messageField);

        int sendX = messageX + messageWidth + spacing;
        this.sendButton = this.addDrawableChild(
                ButtonWidget.builder(Text.literal("Send"), button -> this.sendCurrentMessage())
                        .dimensions(sendX, composeY, sendWidth, INPUT_HEIGHT)
                        .build()
        );

        if (this.showingDirectMessages) {
            int recipientY = composeY;
            int newDmButtonWidth = Math.min(106, Math.max(84, this.channelsWidth / 2));
            this.composeNewDmButton = this.addDrawableChild(
                    ButtonWidget.builder(Text.literal("Add Recipient"), button -> this.toggleNewDmMode())
                            .dimensions(this.channelsX, recipientY, newDmButtonWidth, INPUT_HEIGHT)
                            .build()
            );

            String previousRecipient = this.newRecipientField != null ? this.newRecipientField.getText() : "";
            int recipientWidth = Math.max(64, this.channelsWidth - newDmButtonWidth - spacing);
            this.newRecipientField = new TextFieldWidget(
                    this.textRenderer,
                    this.channelsX + newDmButtonWidth + spacing,
                    recipientY,
                    recipientWidth,
                    INPUT_HEIGHT,
                    Text.literal("Recipient")
            );
            this.newRecipientField.setMaxLength(200);
            this.newRecipientField.setText(previousRecipient);
            this.newRecipientField.setPlaceholder(Text.literal("Email or name..."));
            this.addDrawableChild(this.newRecipientField);
        }

        this.refreshButton = this.addDrawableChild(
                ButtonWidget.builder(Text.literal("Refresh"), button -> this.refreshCurrentView())
                        .dimensions(this.width - RIGHT_MARGIN - 74, TOP_MARGIN, 74, INPUT_HEIGHT)
                        .build()
        );

        int tabWidth = 80;
        int tabY = TOP_MARGIN + 20;
        this.channelsTabButton = this.addDrawableChild(
                ButtonWidget.builder(Text.literal("Channels"), button -> this.showChannelsTab())
                        .dimensions(LEFT_MARGIN, tabY, tabWidth, INPUT_HEIGHT)
                        .build()
        );
        this.directMessagesTabButton = this.addDrawableChild(
                ButtonWidget.builder(Text.literal("Direct Messages"), button -> this.showDirectMessagesTab())
                        .dimensions(LEFT_MARGIN + tabWidth + 4, tabY, tabWidth + 40, INPUT_HEIGHT)
                        .build()
        );

        this.addDrawableChild(
                ButtonWidget.builder(Text.literal("Channel Filter"), button -> this.showPollSettings())
                        .dimensions(this.width - RIGHT_MARGIN - 104, tabY, 104, INPUT_HEIGHT)
                        .build()
        );

        this.setInitialFocus(this.messageField);
        this.setFocused(this.messageField);
        this.updateButtons();

        if (this.showingDirectMessages) {
            if (this.users.isEmpty() && !this.loadingChannels) {
                this.loadUsers();
            }
        } else if (this.channels.isEmpty() && !this.loadingChannels) {
            this.loadChannels();
        } else if (this.selectedChannel != null && this.messages.isEmpty() && !this.loadingMessages) {
            this.loadMessages(this.selectedChannel);
        }
    }

    @Override
    public void tick() {
        this.updateButtons();
        this.updateMentionSuggestions();
        
        long now = System.currentTimeMillis();
        if (now - this.lastPollTime >= POLL_INTERVAL_MS && !this.loadingMessages && !this.sendingMessage) {
            this.lastPollTime = now;

            if (this.showingDirectMessages && this.selectedUserId != null && !this.composingNewDm) {
                this.pollDirectMessages(this.selectedUserId);
            } else if (!this.showingDirectMessages && this.selectedChannel != null) {
                this.pollMessages(this.selectedChannel);
            }
        }


        
        if (this.pollAllConversations) {
            if (now - this.lastDmPollTime >= DM_POLL_INTERVAL_MS && !this.loadingChannels && this.showingDirectMessages) {
                this.lastDmPollTime = now;
                this.pollAllDms();
            }
            
            if (now - this.lastChannelPollTime >= CHANNEL_POLL_INTERVAL_MS && !this.loadingChannels && !this.showingDirectMessages) {
                this.lastChannelPollTime = now;
                this.pollAllConversationsAsync();
            }
        }
    }
    
    private long lastChannelPollTime = 0;
    private static final long CHANNEL_POLL_INTERVAL_MS = 15000;
    
    private void pollAllDms() {
        Thread.ofVirtual().name("ZulipBridgeGuiPollAllDMs").start(() -> {
            try {
                String baseUrl = normalizeBaseUrl(this.config.zulipBaseUrl());
                String auth = buildAuthHeader(this.config);
                
                HttpRequest meReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/users/me"))
                        .header("Authorization", auth)
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<String> meResp = this.httpClient.send(meReq, HttpResponse.BodyHandlers.ofString());
                if (meResp.statusCode() != 200) return;
                
                int myId = JsonParser.parseString(meResp.body()).getAsJsonObject().get("user_id").getAsInt();
                
                HttpRequest privateReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/messages?anchor=newest&num_before=1&num_after=0&apply_markdown=false&client_gravatar=false"))
                        .header("Authorization", auth)
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<String> privateResp = this.httpClient.send(privateReq, HttpResponse.BodyHandlers.ofString());
                if (privateResp.statusCode() != 200) return;
                
                JsonObject body = JsonParser.parseString(privateResp.body()).getAsJsonObject();
                if (!"success".equals(body.get("result").getAsString())) return;
                
                JsonArray messages = body.getAsJsonArray("messages");
                Map<String, Long> newLatestIds = new HashMap<>();
                Set<String> newUnread = new HashSet<>();
                
                for (int i = 0; i < Math.min(messages.size(), 20); i++) {
                    JsonObject msg = messages.get(i).getAsJsonObject();
                    if (!msg.has("flags")) continue;
                    
                    JsonArray flags = msg.getAsJsonArray("flags");
                    boolean isUnread = false;
                    for (int f = 0; f < flags.size(); f++) {
                        if ("read".equals(flags.get(f).getAsString())) {
                            isUnread = true;
                            break;
                        }
                    }
                    if (!isUnread) continue;
                    
                    if (!msg.has("recipient_id")) continue;
                    int recipientId = msg.get("recipient_id").getAsInt();
                    
                    int otherUserId = (recipientId == myId) ? msg.getAsJsonObject("sender_id").getAsInt() : recipientId;
                    String userKey = String.valueOf(otherUserId);
                    
                    long msgId = msg.get("id").getAsLong();
                    Long previousLatest = this.latestDmMessageIds.get(userKey);
                    if (previousLatest == null || msgId > previousLatest) {
                        newUnread.add(userKey);
                    }
                    newLatestIds.put(userKey, msgId);
                }
                
                if (!newUnread.isEmpty()) {
                    final Set<String> unreadToNotify = new HashSet<>(newUnread);
                    this.runOnClient(() -> {
                        this.unreadDms.addAll(unreadToNotify);
                        this.updateButtons();
                    });
                }
                
                this.latestDmMessageIds.clear();
                this.latestDmMessageIds.putAll(newLatestIds);
                
            } catch (Exception ignored) {}
        });
    }
    
    private void pollMessages(String streamName) {
        Thread.ofVirtual().name("ZulipBridgeGuiPoll").start(() -> {
            try {
                String narrow = "[{\"operator\":\"stream\",\"operand\":\"" + escapeJson(streamName) + "\"}]";
                String query = "anchor=newest"
                        + "&num_before=1"
                        + "&num_after=0"
                        + "&narrow=" + URLEncoder.encode(narrow, StandardCharsets.UTF_8)
                        + "&apply_markdown=false";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(normalizeBaseUrl(this.config.zulipBaseUrl()) + "/api/v1/messages?" + query))
                        .header("Authorization", buildAuthHeader(this.config))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) return;

                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!"success".equals(body.get("result").getAsString())) return;

                JsonArray messages = body.getAsJsonArray("messages");
                if (messages.isEmpty()) return;

                JsonObject latestMsg = messages.get(0).getAsJsonObject();
                long latestId = latestMsg.get("id").getAsLong();

                boolean hasNew = this.messages.isEmpty() || latestId > this.messages.getLast().id();

                if (hasNew) {
                    this.runOnClient(() -> {
                        this.loadMessages(streamName);
                    });
                }
            } catch (Exception ignored) {}
        });
    }
    
    /**
     * Called from the polling thread (via {@code client.execute()}) when a
     * reaction add/remove event arrives for a message currently displayed.
     * Updates the reaction count in-place and redraws the message lines.
     */
    public void onReactionEvent(long messageId, String emojiName, String op) {
        for (int i = 0; i < this.messages.size(); i++) {
            ZulipMessage m = this.messages.get(i);
            if (m.id() != messageId) continue;

            Map<String, Integer> updated = new java.util.LinkedHashMap<>(m.reactions());
            if ("add".equals(op)) {
                updated.merge(emojiName, 1, Integer::sum);
            } else if ("remove".equals(op)) {
                updated.computeIfPresent(emojiName, (k, v) -> v <= 1 ? null : v - 1);
            }
            this.messages.set(i, new ZulipMessage(m.id(), m.sender(), m.topic(), m.content(), m.imageHashes(), updated));
            this.rebuildMessageLines();
            return;
        }
    }

    private void pollDirectMessages(String userId) {
        final String targetUserId = userId;
        Thread.ofVirtual().name("ZulipBridgeGuiPollDM").start(() -> {
            try {
                String baseUrl = normalizeBaseUrl(this.config.zulipBaseUrl());
                String auth = buildAuthHeader(this.config);
                
                HttpRequest meReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/users/me"))
                        .header("Authorization", auth)
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<String> meResp = this.httpClient.send(meReq, HttpResponse.BodyHandlers.ofString());
                if (meResp.statusCode() != 200) return;
                
                int myId = JsonParser.parseString(meResp.body()).getAsJsonObject().get("user_id").getAsInt();
                String narrow = "[{\"operator\":\"dm\",\"operand\":[" + myId + "," + targetUserId + "]}]";
                String query = "anchor=newest"
                        + "&num_before=1"
                        + "&num_after=0"
                        + "&narrow=" + URLEncoder.encode(narrow, StandardCharsets.UTF_8)
                        + "&apply_markdown=false";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/messages?" + query))
                        .header("Authorization", auth)
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) return;

                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!"success".equals(body.get("result").getAsString())) return;

                JsonArray messages = body.getAsJsonArray("messages");
                if (messages.isEmpty()) return;

                long latestId = messages.get(0).getAsJsonObject().get("id").getAsLong();

                boolean hasNew = this.messages.isEmpty() || latestId > this.messages.getLast().id();

                if (hasNew) {
                    this.runOnClient(() -> {
                        this.loadDirectMessages(targetUserId);
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Avoid Screen#renderBackground blur pass; some screen/render hooks trigger
        // "Can only blur once per frame" when multiple screens apply blur.
        context.fill(0, 0, this.width, this.height, 0xB0101018);

        context.drawTextWithShadow(this.textRenderer, this.title, LEFT_MARGIN, TOP_MARGIN + 6, 0xFFFFFF);

        String status = this.statusMessage == null ? "" : this.statusMessage;
        String trimmedStatus = this.textRenderer.trimToWidth(status, Math.max(40, this.width - 96));
        context.drawTextWithShadow(
                this.textRenderer,
                trimmedStatus,
                LEFT_MARGIN,
                TOP_MARGIN + HEADER_HEIGHT + 1,
                0xA8A8A8
        );

        this.drawChannelsPanel(context, mouseX, mouseY);
        this.drawMessagesPanel(context, mouseX, mouseY);
        
        if (this.showingPollSettings) {
            this.drawPollSettingsPanel(context, mouseX, mouseY);
        }

        this.drawMentionSuggestions(context, mouseX, mouseY);

        super.render(context, mouseX, mouseY, delta);
    }
    
    private void drawPollSettingsPanel(DrawContext context, int mouseX, int mouseY) {
        int panelWidth = 300;
        int panelHeight = 400;
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;
        
        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xEE1C1C1C);
        drawBorder(context, panelX, panelY, panelWidth, panelHeight, 0xFF4F4F4F);
        
        context.drawTextWithShadow(this.textRenderer, "Channel Filter", panelX + 10, panelY + 10, 0xFFFFFFFF);
        
        context.drawTextWithShadow(this.textRenderer, "Select channels for incoming chat:", panelX + 10, panelY + 30, 0xFFAAAAAA);
        
        int listTop = panelY + 50;
        int rowHeight = 20;
        int visibleRows = Math.min(this.channels.size(), (panelHeight - 60) / rowHeight);
        
        int y = listTop;
        for (int i = 0; i < visibleRows && i < this.channels.size(); i++) {
            ChannelEntry channel = this.channels.get(i);
            boolean isPolled = this.polledChannels.contains(channel.name());
            boolean hovered = mouseX >= panelX + 5 && mouseX < panelX + panelWidth - 5
                    && mouseY >= y && mouseY < y + rowHeight;
            
            if (hovered) {
                context.fill(panelX + 5, y, panelX + panelWidth - 5, y + rowHeight, 0x443A3A49);
            }
            
            String checkbox = isPolled ? "[x]" : "[ ]";
            int textColor = isPolled ? 0xFF67D0FF : 0xFF888888;
            context.drawTextWithShadow(this.textRenderer, checkbox + " " + channel.name(), panelX + 10, y + 4, textColor);
            
            y += rowHeight;
        }
        
        int buttonY = panelY + panelHeight - 30;
        context.drawTextWithShadow(this.textRenderer, "Click on channels to toggle", panelX + 10, buttonY, 0xFF888888);
    }

    private void updateMentionSuggestions() {
        this.mentionSuggestions.clear();
        this.mentionTokenStart = -1;
        this.mentionTokenEnd = -1;

        if (this.messageField == null || !this.messageField.isFocused()) return;

        String text = this.messageField.getText();
        if (text == null || text.isBlank()) return;

        int atIndex = findAutocompleteStart(text, '@');
        int colonIndex = findAutocompleteStart(text, ':');
        if (atIndex < 0 && colonIndex < 0) return;

        if (colonIndex > atIndex) {
            populateEmojiSuggestions(text, colonIndex);
        } else {
            populateUserSuggestions(text, atIndex);
        }
    }

    private void drawMentionSuggestions(DrawContext context, int mouseX, int mouseY) {
        if (this.mentionSuggestions.isEmpty() || this.messageField == null || !this.messageField.isFocused()) return;

        int panelWidth = Math.min(280, Math.max(170, this.messageField.getWidth()));
        int panelHeight = this.mentionSuggestions.size() * MENTION_SUGGESTION_ROW_HEIGHT + 8;
        int panelX = this.messageField.getX();
        int panelY = this.messageField.getY() - panelHeight - 4;
        if (panelY < TOP_MARGIN + HEADER_HEIGHT + STATUS_HEIGHT) {
            panelY = this.messageField.getY() + this.messageField.getHeight() + 4;
        }

        this.mentionSuggestionsX = panelX;
        this.mentionSuggestionsY = panelY;
        this.mentionSuggestionsWidth = panelWidth;

        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xEE1A1A22);
        drawBorder(context, panelX, panelY, panelWidth, panelHeight, 0xFF4F4F62);

        int rowY = panelY + 4;
        for (int i = 0; i < this.mentionSuggestions.size(); i++) {
            AutocompleteSuggestion suggestion = this.mentionSuggestions.get(i);
            boolean hovered = mouseX >= panelX + 1 && mouseX < panelX + panelWidth - 1
                    && mouseY >= rowY && mouseY < rowY + MENTION_SUGGESTION_ROW_HEIGHT;
            if (hovered) {
                context.fill(panelX + 1, rowY, panelX + panelWidth - 1, rowY + MENTION_SUGGESTION_ROW_HEIGHT, 0x553A3A49);
            }

            String trimmedLabel = this.textRenderer.trimToWidth(suggestion.label(), panelWidth - 8);
            context.drawTextWithShadow(this.textRenderer, trimmedLabel, panelX + 4, rowY + 2, 0xFFE8E8E8);
            rowY += MENTION_SUGGESTION_ROW_HEIGHT;
        }
    }

    private boolean handleMentionSuggestionClick(int mouseX, int mouseY) {
        if (this.mentionSuggestions.isEmpty()) return false;

        int panelHeight = this.mentionSuggestions.size() * MENTION_SUGGESTION_ROW_HEIGHT + 8;
        if (mouseX < this.mentionSuggestionsX
                || mouseX >= this.mentionSuggestionsX + this.mentionSuggestionsWidth
                || mouseY < this.mentionSuggestionsY
                || mouseY >= this.mentionSuggestionsY + panelHeight) {
            return false;
        }

        int rowIndex = (mouseY - this.mentionSuggestionsY - 4) / MENTION_SUGGESTION_ROW_HEIGHT;
        if (rowIndex < 0 || rowIndex >= this.mentionSuggestions.size()) return false;

        if (this.reactingToMessageId != 0) {
            String shortcode = this.mentionSuggestions.get(rowIndex).insertText();
            this.mentionSuggestions.clear();
            this.mentionTokenStart = -1;
            this.mentionTokenEnd = -1;
            this.sendReaction(this.reactingToMessageId, shortcode);
        } else {
            this.applyMentionSuggestion(this.mentionSuggestions.get(rowIndex));
        }
        return true;
    }

    private void applyMentionSuggestion(AutocompleteSuggestion suggestion) {
        if (this.messageField == null || this.mentionTokenStart < 0 || this.mentionTokenEnd < this.mentionTokenStart) return;

        String text = this.messageField.getText();
        if (text == null) return;

        int safeEnd = Math.min(this.mentionTokenEnd, text.length());
        String prefix = text.substring(0, this.mentionTokenStart);
        String suffix = text.substring(safeEnd);
        String mention = suggestion.insertText();

        if (suffix.isEmpty()) {
            suffix = " ";
        } else if (!Character.isWhitespace(suffix.charAt(0))) {
            suffix = " " + suffix;
        }

        this.messageField.setText(prefix + mention + suffix);
        this.mentionSuggestions.clear();
        this.mentionTokenStart = -1;
        this.mentionTokenEnd = -1;
    }

    private static boolean isMentionTokenChar(char character) {
        return Character.isLetterOrDigit(character)
                || character == '_'
                || character == '-'
                || character == '.';
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        if (PreviewHud.isActive()) {
            if (button == 0) {
                PreviewHud.handleMousePressed(mouseX, mouseY);
            }
            return true;
        }

        if (super.mouseClicked(click, doubleClick)) return true;

        if (button == 0 && this.handleMentionSuggestionClick((int) mouseX, (int) mouseY)) {
            return true;
        }

        if (button == 0 && this.visibleReactionButton != null
                && this.visibleReactionButton.contains((int) mouseX, (int) mouseY)) {
            long msgId = this.visibleReactionButton.messageId();
            if (this.reactingToMessageId == msgId) {
                this.cancelReactionMode();
            } else {
                this.enterReactionMode(msgId);
            }
            return true;
        }

        if (button == 0) {
            String imageHash = findPreviewableImageAt((int) mouseX, (int) mouseY);
            if (imageHash != null) {
                PreviewHud.show(imageHash);
                return true;
            }
        }

        if (button == 0 && isInsideChannels(mouseX, mouseY)) {
            int rowTop = this.channelsY + PANEL_HEADER_HEIGHT + 2;
            int relativeY = (int) mouseY - rowTop;
            if (relativeY < 0) return true;

            int rowIndex = relativeY / CHANNEL_ROW_HEIGHT;
            List<ChannelListRow> currentRows = this.showingDirectMessages ? this.userRows : this.channelRows;
            int listIndex = this.channelScroll + rowIndex;
            if (listIndex >= 0 && listIndex < currentRows.size()) {
                ChannelListRow clickedRow = currentRows.get(listIndex);
                if (!clickedRow.folderHeader() && clickedRow.channelName() != null) {
                    if (this.showingDirectMessages) {
                        String userId = clickedRow.channelName();
                        String userName = clickedRow.label().replaceFirst("^@", "");
                        if (this.composingNewDm) {
                            this.selectRecipientForNewDm(userId, userName);
                        } else {
                            this.selectUser(userId, userName);
                        }
                    } else {
                        this.selectChannel(clickedRow.channelName());
                    }
                }
            }
            return true;
        }

        if (this.showingPollSettings && isInsidePollSettingsPanel((int) mouseX, (int) mouseY)) {
            int rowHeight = 20;
            int listTop = (this.height - 400) / 2 + 50;
            int relativeY = (int) mouseY - listTop;
            
            if (relativeY >= 0 && relativeY < this.channels.size() * rowHeight) {
                int clickedIndex = relativeY / rowHeight;
                if (clickedIndex >= 0 && clickedIndex < this.channels.size()) {
                    String channelName = this.channels.get(clickedIndex).name();
                    this.toggleChannelPolling(channelName);
                }
            }
            return true;
        }

        // Shift+click on a link in the message panel → open in browser
        long windowHandle = MinecraftClient.getInstance().getWindow().getHandle();
        boolean shiftHeld = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (button == 0 && shiftHeld && isInsideMessages(mouseX, mouseY)) {
            String linkUrl = findLinkAtMessagePosition((int) mouseX, (int) mouseY);
            if (linkUrl != null) {
                try {
                    Util.getOperatingSystem().open(new URI(linkUrl));
                } catch (Exception e) {
                    ZulipBridgeClient.LOGGER.warn("Failed to open link: {}", linkUrl);
                }
                return true;
            }
        }

        return false;
    }

    private String findLinkAtMessagePosition(int mouseX, int mouseY) {
        int panelTop = this.messagesY + PANEL_HEADER_HEIGHT + 2;
        int panelBottom = this.messagesY + this.messagesHeight - 8;
        if (mouseY < panelTop || mouseY >= panelBottom) return null;

        int lineTop = panelTop;
        int visibleLines = this.visibleMessageRows();
        int lineStartX = this.messagesX + 4;

        for (int i = 0; i < visibleLines; i++) {
            int lineIndex = this.messageScroll + i;
            if (lineIndex >= this.renderedMessageLines.size()) break;

            DisplayLine line = this.renderedMessageLines.get(lineIndex);
            int lineY = lineTop + i * MESSAGE_LINE_HEIGHT;
            if (mouseY < lineY || mouseY >= lineY + MESSAGE_LINE_HEIGHT) continue;
            if (line.links().isEmpty()) continue;

            for (LinkSpan span : line.links()) {
                int pixelX1 = lineStartX + this.textRenderer.getWidth(line.text().substring(0, span.start()));
                int pixelX2 = lineStartX + this.textRenderer.getWidth(line.text().substring(0, span.end()));
                if (mouseX >= pixelX1 && mouseX < pixelX2) {
                    return span.url();
                }
            }
            break;
        }
        return null;
    }

    private boolean isInsidePollSettingsPanel(int mouseX, int mouseY) {
        int panelWidth = 300;
        int panelHeight = 400;
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;
        return mouseX >= panelX && mouseX < panelX + panelWidth
                && mouseY >= panelY && mouseY < panelY + panelHeight;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (PreviewHud.isActive()) {
            PreviewHud.handleScroll(mouseX, mouseY, verticalAmount);
            return true;
        }
        if (verticalAmount == 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);

        int direction = verticalAmount > 0 ? -1 : 1;
        if (isInsideChannels(mouseX, mouseY)) {
            this.channelScroll = clamp(this.channelScroll + direction, 0, this.maxChannelScroll());
            return true;
        }

        if (isInsideMessages(mouseX, mouseY)) {
            this.messageScroll = clamp(this.messageScroll + (direction * 2), 0, this.maxMessageScroll());
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        if (PreviewHud.isActive()) {
            int keyCode = keyInput.key();
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                PreviewHud.close();
                return true;
            }
            return true;
        }

        int keyCode = keyInput.key();

        if (keyCode == GLFW.GLFW_KEY_ESCAPE && this.reactingToMessageId != 0) {
            this.cancelReactionMode();
            return true;
        }

        boolean acceptingSuggestion = this.messageField != null
                && this.messageField.isFocused()
                && !this.mentionSuggestions.isEmpty();

        if (keyCode == GLFW.GLFW_KEY_TAB && acceptingSuggestion) {
            if (this.reactingToMessageId != 0) {
                String shortcode = this.mentionSuggestions.getFirst().insertText();
                this.mentionSuggestions.clear();
                this.mentionTokenStart = -1;
                this.mentionTokenEnd = -1;
                this.sendReaction(this.reactingToMessageId, shortcode);
            } else {
                this.applyMentionSuggestion(this.mentionSuggestions.getFirst());
            }
            return true;
        }

        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && acceptingSuggestion) {
            if (this.reactingToMessageId != 0) {
                String shortcode = this.mentionSuggestions.getFirst().insertText();
                this.mentionSuggestions.clear();
                this.mentionTokenStart = -1;
                this.mentionTokenEnd = -1;
                this.sendReaction(this.reactingToMessageId, shortcode);
            } else {
                this.applyMentionSuggestion(this.mentionSuggestions.getFirst());
            }
            return true;
        }

        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && (this.messageField != null && this.messageField.isFocused()
                || this.topicField != null && this.topicField.isFocused())) {
            this.sendCurrentMessage();
            return true;
        }

        return super.keyPressed(keyInput);
    }

    private void populateUserSuggestions(String text, int atIndex) {
        if (atIndex < 0) return;

        int tokenEnd = atIndex + 1;
        while (tokenEnd < text.length() && isMentionTokenChar(text.charAt(tokenEnd))) {
            tokenEnd++;
        }

        if (tokenEnd != text.length()) return;

        String query = text.substring(atIndex + 1, tokenEnd).trim();
        if (query.startsWith("**")) return;

        if (this.users.isEmpty()) {
            if (!this.loadingChannels && !this.mentionUserLoadRequested) {
                this.mentionUserLoadRequested = true;
                this.loadUsers();
            }
            return;
        }

        this.mentionTokenStart = atIndex;
        this.mentionTokenEnd = tokenEnd;
        String normalizedQuery = query.toLowerCase(Locale.ROOT);

        for (UserEntry user : this.users) {
            String userName = user.name() == null ? "" : user.name();
            String userEmail = user.email() == null ? "" : user.email();
            String lowerName = userName.toLowerCase(Locale.ROOT);
            String lowerEmail = userEmail.toLowerCase(Locale.ROOT);

            if (normalizedQuery.isEmpty()
                    || lowerName.startsWith(normalizedQuery)
                    || lowerName.contains(normalizedQuery)
                    || (!userEmail.isBlank() && lowerEmail.startsWith(normalizedQuery))
                    || (!userEmail.isBlank() && lowerEmail.contains(normalizedQuery))) {
                String label = "@" + user.name();
                if (user.email() != null && !user.email().isBlank()) {
                    label += " <" + user.email() + ">";
                }
                this.mentionSuggestions.add(new AutocompleteSuggestion(label, "@**" + user.name() + "**"));
                if (this.mentionSuggestions.size() >= MAX_MENTION_SUGGESTIONS) {
                    break;
                }
            }
        }
    }

    private void populateEmojiSuggestions(String text, int colonIndex) {
        if (colonIndex < 0) return;
        if (colonIndex < text.length() - 1 && text.endsWith(":")) return;

        int tokenEnd = colonIndex + 1;
        while (tokenEnd < text.length() && isEmojiTokenChar(text.charAt(tokenEnd))) {
            tokenEnd++;
        }

        if (tokenEnd != text.length()) return;

        String query = text.substring(colonIndex + 1, tokenEnd).trim().toLowerCase(Locale.ROOT);
        this.mentionTokenStart = colonIndex;
        this.mentionTokenEnd = tokenEnd;

        for (String shortcode : EmojiShortcodes.suggest(query, MAX_MENTION_SUGGESTIONS)) {
            String emoji = EmojiShortcodes.get(shortcode);
            String label = (emoji == null || emoji.isBlank() ? "" : emoji + " ") + ":" + shortcode + ":";
            this.mentionSuggestions.add(new AutocompleteSuggestion(label, ":" + shortcode + ":"));
        }
    }

    private static int findAutocompleteStart(String text, char marker) {
        int markerIndex = text.lastIndexOf(marker);
        if (markerIndex < 0) return -1;
        if (markerIndex > 0) {
            char previous = text.charAt(markerIndex - 1);
            if (!Character.isWhitespace(previous) && previous != '(' && previous != '[') {
                return -1;
            }
        }
        return markerIndex;
    }

    private static boolean isEmojiTokenChar(char character) {
        return Character.isLetterOrDigit(character)
                || character == '_'
                || character == '-'
                || character == '+';
    }

    private void recalculateLayout() {
        int contentTop = TOP_MARGIN + HEADER_HEIGHT + STATUS_HEIGHT + CONTENT_GAP;
        int composeY = this.height - BOTTOM_MARGIN - INPUT_HEIGHT;
        int contentBottom = composeY - 8;
        int contentHeight = Math.max(80, contentBottom - contentTop);

        this.channelsX = LEFT_MARGIN;
        this.channelsY = contentTop;
        this.channelsWidth = Math.min(220, Math.max(150, this.width / 4));
        this.channelsHeight = contentHeight;

        this.messagesX = this.channelsX + this.channelsWidth + PANEL_GAP;
        this.messagesY = contentTop;
        this.messagesWidth = Math.max(120, this.width - this.messagesX - RIGHT_MARGIN);
        this.messagesHeight = contentHeight;
    }

    private void drawChannelsPanel(DrawContext context, int mouseX, int mouseY) {
        int x2 = this.channelsX + this.channelsWidth;
        int y2 = this.channelsY + this.channelsHeight;

        context.fill(this.channelsX, this.channelsY, x2, y2, 0xAA101015);
        drawBorder(context, this.channelsX, this.channelsY, this.channelsWidth, this.channelsHeight, 0xFF2F2F3F);

        String panelTitle = this.showingDirectMessages ? "Direct Messages" : "Channels";
        context.drawTextWithShadow(this.textRenderer, panelTitle, this.channelsX + 4, this.channelsY + 3, 0xFFE0E0E0);

        int listTop = this.channelsY + PANEL_HEADER_HEIGHT + 2;
        int visibleRows = this.visibleChannelRows();

        if (this.loadingChannels) {
            context.drawTextWithShadow(this.textRenderer, "Loading...", this.channelsX + 4, listTop, 0xFFC8C8C8);
            return;
        }

        List<ChannelListRow> currentRows = this.showingDirectMessages ? this.userRows : this.channelRows;
        if (currentRows.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, this.showingDirectMessages ? "No users" : "No channels", this.channelsX + 4, listTop, 0xFF9F9F9F);
            return;
        }

        for (int i = 0; i < visibleRows; i++) {
            int rowIndex = this.channelScroll + i;
            if (rowIndex >= currentRows.size()) break;

            int rowY = listTop + (i * CHANNEL_ROW_HEIGHT);
            ChannelListRow row = currentRows.get(rowIndex);
            String itemId = row.channelName();
            boolean folderHeader = row.folderHeader();
            boolean selected;
            if (this.showingDirectMessages) {
                selected = itemId != null && itemId.equals(this.selectedUserId);
            } else {
                selected = itemId != null && itemId.equals(this.selectedChannel);
            }
            boolean hovered = mouseX >= this.channelsX + 1 && mouseX < x2 - 1
                    && mouseY >= rowY && mouseY < rowY + CHANNEL_ROW_HEIGHT;

            if (folderHeader) {
                context.fill(this.channelsX + 1, rowY, x2 - 1, rowY + CHANNEL_ROW_HEIGHT, 0x6630303F);
            } else if (selected) {
                context.fill(this.channelsX + 1, rowY, x2 - 1, rowY + CHANNEL_ROW_HEIGHT, 0xAA35557A);
            } else if (hovered) {
                context.fill(this.channelsX + 1, rowY, x2 - 1, rowY + CHANNEL_ROW_HEIGHT, 0x443A3A49);
            }

            String label = this.textRenderer.trimToWidth(row.label(), this.channelsWidth - 20);
            int textColor = folderHeader ? 0xFFBFC3CF : (selected ? 0xFFFFFFFF : 0xFFD7D7D7);
            context.drawTextWithShadow(
                    this.textRenderer,
                    label,
                    this.channelsX + 4,
                    rowY + 2,
                    textColor
            );

            if (this.showingDirectMessages && itemId != null && this.unreadDms.contains(itemId)) {
                context.fill(this.channelsX + this.channelsWidth - 10, rowY + 2, this.channelsX + this.channelsWidth - 4, rowY + CHANNEL_ROW_HEIGHT - 2, 0xFFFF4444);
            }
            
            if (!this.showingDirectMessages && itemId != null && !folderHeader && this.unreadChannels.contains(itemId)) {
                context.fill(this.channelsX + this.channelsWidth - 10, rowY + 2, this.channelsX + this.channelsWidth - 4, rowY + CHANNEL_ROW_HEIGHT - 2, 0xFFFF4444);
            }
        }
    }

    private void drawMessagesPanel(DrawContext context, int mouseX, int mouseY) {
        int x2 = this.messagesX + this.messagesWidth;
        int y2 = this.messagesY + this.messagesHeight;
        this.previewableImages.clear();
        this.visibleReactionButton = null;

        context.fill(this.messagesX, this.messagesY, x2, y2, 0xAA101015);
        drawBorder(context, this.messagesX, this.messagesY, this.messagesWidth, this.messagesHeight, 0xFF2F2F3F);

        String header;
        if (this.showingDirectMessages) {
            header = this.selectedUserName == null ? "Messages" : "Messages - @" + this.selectedUserName;
        } else {
            header = this.selectedChannel == null ? "Messages" : "Messages - #" + this.selectedChannel;
        }
        String trimmedHeader = this.textRenderer.trimToWidth(header, this.messagesWidth - 8);
        context.drawTextWithShadow(this.textRenderer, trimmedHeader, this.messagesX + 4, this.messagesY + 3, 0xFFE0E0E0);

        int lineTop = this.messagesY + PANEL_HEADER_HEIGHT + 2;
        int visibleLines = this.visibleMessageRows();

        if (this.renderedMessageLines.isEmpty()) {
            return;
        }

        this.messageScroll = clamp(this.messageScroll, 0, this.maxMessageScroll());
        int panelTop = this.messagesY + PANEL_HEADER_HEIGHT + 2;
        int panelBottom = this.messagesY + this.messagesHeight - 8;

        // Pre-pass: find which message (if any) the mouse is currently hovering over.
        long hoveredMsgId = 0;
        boolean mouseInPanel = mouseX >= this.messagesX && mouseX < this.messagesX + this.messagesWidth
                && mouseY >= panelTop && mouseY < panelBottom;
        if (mouseInPanel) {
            for (int i = 0; i < visibleLines; i++) {
                int lineIndex = this.messageScroll + i;
                if (lineIndex >= this.renderedMessageLines.size()) break;
                DisplayLine line = this.renderedMessageLines.get(lineIndex);
                int y = lineTop + i * MESSAGE_LINE_HEIGHT;
                if (mouseY >= y && mouseY < y + MESSAGE_LINE_HEIGHT && line.messageId() != 0) {
                    hoveredMsgId = line.messageId();
                    break;
                }
            }
        }

        context.enableScissor(this.messagesX, panelTop, this.messagesX + this.messagesWidth, panelBottom);
        for (int i = 0; i < visibleLines; i++) {
            int lineIndex = this.messageScroll + i;
            if (lineIndex >= this.renderedMessageLines.size()) break;

            DisplayLine line = this.renderedMessageLines.get(lineIndex);
            int y = lineTop + (i * MESSAGE_LINE_HEIGHT);
            if (y + MESSAGE_LINE_HEIGHT < panelTop || y >= panelBottom) continue;

            if (line.imageHash() != null) {
                if (line.imageSpacer()) continue;

                ImageCache.CachedImage image = ImageCache.lookup(line.imageHash());
                int drawX = this.messagesX + 12;
                if (image == null) {
                    context.drawTextWithShadow(this.textRenderer, "[loading image]", drawX, y + 2, 0xFFAAAAAA);
                    continue;
                }

                int maxWidth = Math.max(40, this.messagesWidth - 20);
                ImageRenderer.Size size = ImageRenderer.fit(image.width(), image.height(), maxWidth, GUI_IMAGE_MAX_SIZE);
                ImageRenderer.draw(context, image, drawX, y + 1, size);
                this.previewableImages.add(new PreviewableImage(line.imageHash(), drawX, y + 1, size.width(), size.height()));
                continue;
            }

            drawInlineEmojiText(context, line, this.messagesX + 4, y);

            // Draw the [+] reaction button on the header line of the hovered message.
            if (line.isHeader() && line.messageId() == hoveredMsgId && hoveredMsgId != 0) {
                int btnW = this.textRenderer.getWidth("[+]") + 4;
                int btnX = this.messagesX + this.messagesWidth - btnW - 2;
                int btnY = y;
                boolean active = this.reactingToMessageId == hoveredMsgId;
                int btnColor = active ? 0xFF88FF88 : 0xFFAABBFF;
                context.fill(btnX - 1, btnY, btnX + btnW + 1, btnY + MESSAGE_LINE_HEIGHT, 0x60000000);
                context.drawTextWithShadow(this.textRenderer, "[+]", btnX + 2, btnY + 1, btnColor);
                this.visibleReactionButton = new ReactionButton(hoveredMsgId, btnX - 1, btnY, btnW + 2, MESSAGE_LINE_HEIGHT);
            }
        }
        context.disableScissor();
    }

    private void drawInlineEmojiText(DrawContext context, DisplayLine line, int x, int y) {
        context.drawTextWithShadow(this.textRenderer, line.text(), x, y, line.color());
        if (line.inlineEmojiHashes().isEmpty()) return;

        int emojiIndex = 0;
        for (int i = 0; i < line.text().length() && emojiIndex < line.inlineEmojiHashes().size(); i++) {
            if (line.text().charAt(i) != InlineEmoji.PLACEHOLDER) continue;

            String imageHash = line.inlineEmojiHashes().get(emojiIndex++);
            ImageCache.CachedImage image = ImageCache.lookup(imageHash);
            if (image == null) continue;

            int drawX = x + this.textRenderer.getWidth(line.text().substring(0, i));
            int drawY = y - 1;
            int size = MESSAGE_LINE_HEIGHT + 2;
            ImageRenderer.draw(context, image, drawX, drawY, new ImageRenderer.Size(size, size));
            this.previewableImages.add(new PreviewableImage(imageHash, drawX, drawY, size, size));
        }
    }

    private void refreshCurrentView() {
        if (this.showingDirectMessages) {
            this.loadUsers();
        } else {
            this.loadChannels();
        }
    }

    private void showChannelsTab() {
        this.showingDirectMessages = false;
        this.composingNewDm = false;
        this.selectedUserId = null;
        this.selectedUserName = null;
        this.channelRows.clear();
        this.init();
        if (!this.loadingChannels) {
            this.loadChannels();
        }
        this.updateButtons();
    }

    private void showDirectMessagesTab() {
        this.showingDirectMessages = true;
        this.composingNewDm = false;
        this.selectedChannel = null;
        this.messages.clear();
        this.rebuildMessageLines();
        this.userRows.clear();
        this.init();
        if (!this.loadingChannels) {
            this.loadUsers();
        }
        this.updateButtons();
    }

    private void toggleNewDmMode() {
        this.composingNewDm = !this.composingNewDm;
        if (this.composingNewDm) {
            this.selectedUserId = null;
            this.selectedUserName = null;
            this.messages.clear();
            this.rebuildMessageLines();
            this.statusMessage = "Select a recipient from the member list or type an email.";
            if (this.users.isEmpty() && !this.loadingChannels) {
                this.loadUsers();
            }
        }
        this.init();
        this.updateButtons();
    }

    private void showPollSettings() {
        this.showingPollSettings = !this.showingPollSettings;
        this.updateButtons();
    }

    private void toggleChannelPolling(String channelName) {
        if (this.polledChannels.contains(channelName)) {
            this.polledChannels.remove(channelName);
        } else {
            this.polledChannels.add(channelName);
        }
        this.persistPollSettings();
        this.updateButtons();
    }

    private void toggleAllPolling() {
        this.pollAllConversations = !this.pollAllConversations;
        if (this.pollAllConversations) {
            this.statusMessage = "All polling enabled";
            this.polledChannels.clear();
            for (ChannelEntry channel : this.channels) {
                this.polledChannels.add(channel.name());
            }
            this.pollAllConversationsAsync();
            this.lastPollTime = 0;
            this.lastDmPollTime = 0;
            this.lastChannelPollTime = 0;
        } else {
            this.statusMessage = "All polling disabled";
            this.polledChannels.clear();
            this.unreadChannels.clear();
            this.unreadDms.clear();
        }
        this.persistPollSettings();
        this.updateButtons();
    }

    private void syncPollSettingsWithLoadedChannels() {
        if (this.channels.isEmpty()) return;

        if (this.pollAllConversations) {
            this.polledChannels.clear();
            for (ChannelEntry channel : this.channels) {
                this.polledChannels.add(channel.name());
            }
        } else {
            Set<String> availableChannels = new HashSet<>();
            for (ChannelEntry channel : this.channels) {
                availableChannels.add(channel.name());
            }
            this.polledChannels.removeIf(channelName -> !availableChannels.contains(channelName));
        }

        this.persistPollSettings();
    }

    private void persistPollSettings() {
        synchronized (ZulipBridgeScreen.class) {
            PERSISTED_POLL_ALL_CONVERSATIONS = false;
            PERSISTED_POLLED_CHANNELS.clear();
            PERSISTED_POLLED_CHANNELS.addAll(this.polledChannels);
        }
    }

    public static boolean shouldReceiveIncomingStream(String streamName) {
        synchronized (ZulipBridgeScreen.class) {
            if (PERSISTED_POLLED_CHANNELS.isEmpty()) return true;
            for (String configured : PERSISTED_POLLED_CHANNELS) {
                if (configured.equalsIgnoreCase(streamName)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void pollAllConversationsAsync() {
        if (!this.pollAllConversations) return;
        
        Thread.ofVirtual().name("ZulipBridgeGuiPollAllChannels").start(() -> {
            try {
                String baseUrl = normalizeBaseUrl(this.config.zulipBaseUrl());
                String auth = buildAuthHeader(this.config);
                
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/users/me/subscriptions"))
                        .header("Authorization", auth)
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> resp = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) return;
                
                JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonArray subscriptions = body.getAsJsonArray("subscriptions");
                
                Map<String, Long> newLatestIds = new HashMap<>();
                Set<String> newUnread = new HashSet<>();
                List<NewMessageInfo> newMessages = new ArrayList<>();
                
                for (int i = 0; i < subscriptions.size(); i++) {
                    JsonObject sub = subscriptions.get(i).getAsJsonObject();
                    String streamName = sub.get("name").getAsString();
                    
                    if (!this.polledChannels.contains(streamName)) continue;
                    
                    String narrow = "[{\"operator\":\"stream\",\"operand\":\"" + escapeJson(streamName) + "\"}]";
                    String query = "anchor=newest&num_before=5&num_after=0&narrow=" 
                            + URLEncoder.encode(narrow, StandardCharsets.UTF_8) + "&apply_markdown=false";
                    
                    HttpRequest msgReq = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/v1/messages?" + query))
                            .header("Authorization", auth)
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build();
                    
                    HttpResponse<String> msgResp = this.httpClient.send(msgReq, HttpResponse.BodyHandlers.ofString());
                    if (msgResp.statusCode() != 200) continue;
                    
                    JsonObject msgBody = JsonParser.parseString(msgResp.body()).getAsJsonObject();
                    if (!"success".equals(msgBody.get("result").getAsString())) continue;
                    
                    JsonArray msgs = msgBody.getAsJsonArray("messages");
                    if (msgs.isEmpty()) continue;
                    
                    long msgId = msgs.get(0).getAsJsonObject().get("id").getAsLong();
                    Long prevLatest = this.latestChannelMessageIds.get(streamName);
                    
                    if (prevLatest == null || msgId > prevLatest) {
                        if (!streamName.equals(this.selectedChannel)) {
                            newUnread.add(streamName);
                        }
                        
                        for (int m = 0; m < msgs.size(); m++) {
                            JsonObject msgObj = msgs.get(m).getAsJsonObject();
                            long mId = msgObj.get("id").getAsLong();
                            if (prevLatest != null && mId <= prevLatest) break;
                            
                            String sender = msgObj.has("sender_full_name") 
                                    ? msgObj.get("sender_full_name").getAsString() : "Unknown";
                            String content = msgObj.has("content") 
                                    ? msgObj.get("content").getAsString() : "";
                            
                            if (!content.isBlank()) {
                                newMessages.add(new NewMessageInfo(streamName, sender, content));
                            }
                        }
                    }
                    newLatestIds.put(streamName, msgId);
                }
                
                if (!newMessages.isEmpty()) {
                    final List<NewMessageInfo> messagesToChat = newMessages;
                    this.runOnClient(() -> {
                        this.displayMessagesInChat(messagesToChat);
                    });
                }
                
                if (!newUnread.isEmpty()) {
                    final Set<String> unreadToNotify = new HashSet<>(newUnread);
                    this.runOnClient(() -> {
                        this.unreadChannels.addAll(unreadToNotify);
                        this.updateButtons();
                    });
                }
                
                this.latestChannelMessageIds.clear();
                this.latestChannelMessageIds.putAll(newLatestIds);
                
            } catch (Exception ignored) {}
        });
    }

    private void selectChannel(String channel) {
        if (channel == null || channel.isBlank()) return;
        if (channel.equals(this.selectedChannel) && !this.messages.isEmpty()) return;

        this.selectedChannel = channel;
        this.messages.clear();
        if (this.reactingToMessageId != 0) this.cancelReactionMode();
        this.rebuildMessageLines();
        this.loadMessages(channel);
        
        this.unreadChannels.remove(channel);
        this.updateButtons();
    }

    private void loadChannels() {
        String validationError = validateAccountConfig(this.config);
        if (validationError != null) {
            this.statusMessage = validationError;
            this.channels.clear();
            this.channelRows.clear();
            this.selectedChannel = null;
            this.messages.clear();
            this.rebuildMessageLines();
            this.updateButtons();
            return;
        }

        this.loadingChannels = true;
        this.statusMessage = "Loading channels...";
        this.updateButtons();

        Thread.ofVirtual().name("ZulipBridgeGuiChannels").start(() -> {
            try {
                Map<Integer, FolderInfo> folderInfoById = new HashMap<>();
                String folderMetadataWarning = null;
                try {
                    folderInfoById = this.fetchFolderInfoMap();
                } catch (Exception e) {
                    folderMetadataWarning = e.getMessage();
                }

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(normalizeBaseUrl(this.config.zulipBaseUrl()) + "/api/v1/users/me/subscriptions"))
                        .header("Authorization", buildAuthHeader(this.config))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }

                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!"success".equals(body.get("result").getAsString())) {
                    throw new IllegalStateException(body.get("msg").getAsString());
                }

                JsonArray subscriptions = body.getAsJsonArray("subscriptions");
                List<ChannelEntry> loadedChannels = new ArrayList<>();
                for (int i = 0; i < subscriptions.size(); i++) {
                    JsonObject subscription = subscriptions.get(i).getAsJsonObject();
                    String name = subscription.get("name").getAsString();
                    Integer folderId = readNullableInt(subscription, "folder_id");
                    FolderInfo folderInfo = folderId != null ? folderInfoById.get(folderId) : null;
                    String folderName = folderInfo != null ? folderInfo.name() : "Ungrouped";
                    int folderOrder = folderInfo != null ? folderInfo.order() : Integer.MAX_VALUE;
                    boolean pinned = readBoolean(subscription, "pin_to_top");
                    boolean muted = readBoolean(subscription, "is_muted");

                    loadedChannels.add(new ChannelEntry(name, folderName, folderOrder, pinned, muted));
                }
                loadedChannels.sort(channelComparator());
                final String folderWarningForUi = folderMetadataWarning;

                this.runOnClient(() -> {
                    this.loadingChannels = false;
                    this.channels.clear();
                    this.channels.addAll(loadedChannels);
                    this.rebuildChannelRows();
                    this.syncPollSettingsWithLoadedChannels();
                    this.channelScroll = clamp(this.channelScroll, 0, this.maxChannelScroll());

                    if (this.channels.isEmpty()) {
                        this.selectedChannel = null;
                        this.messages.clear();
                        this.rebuildMessageLines();
                        this.statusMessage = "No channels found for this account.";
                        this.updateButtons();
                        return;
                    }

                    if (this.selectedChannel == null || !containsChannelName(this.selectedChannel)) {
                        this.selectedChannel = this.channels.getFirst().name();
                    }

                    int folderCount = countDistinctFolders(this.channels);
                    this.statusMessage = "Loaded " + this.channels.size() + " channels in " + folderCount + " folders."
                            + (folderWarningForUi == null ? "" : " (folder metadata unavailable)");
                    this.loadMessages(this.selectedChannel);
                    this.updateButtons();
                });
            } catch (Exception e) {
                this.runOnClient(() -> {
                    this.loadingMessages = false;
                    this.rebuildMessageLines();
                    this.statusMessage = "Failed to load messages: " + e.getMessage();
                    this.updateButtons();
                });
            }
        });
    }

    private void loadMessages(String streamName) {
        if (streamName == null || streamName.isBlank()) return;

        String validationError = validateAccountConfig(this.config);
        if (validationError != null) {
            this.statusMessage = validationError;
            return;
        }

        this.loadingMessages = true;
        this.statusMessage = "Loading messages for #" + streamName + "...";
        this.rebuildMessageLines();
        this.updateButtons();

        Thread.ofVirtual().name("ZulipBridgeGuiMessages").start(() -> {
            try {
                String narrow = "[{\"operator\":\"stream\",\"operand\":\"" + escapeJson(streamName) + "\"}]";
                String query = "anchor=newest"
                        + "&num_before=" + MAX_HISTORY_MESSAGES
                        + "&num_after=0"
                        + "&narrow=" + URLEncoder.encode(narrow, StandardCharsets.UTF_8)
                        + "&apply_markdown=false"
                        + "&client_gravatar=false";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(normalizeBaseUrl(this.config.zulipBaseUrl()) + "/api/v1/messages?" + query))
                        .header("Authorization", buildAuthHeader(this.config))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }

                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!"success".equals(body.get("result").getAsString())) {
                    throw new IllegalStateException(body.get("msg").getAsString());
                }

                JsonArray jsonMessages = body.getAsJsonArray("messages");
                List<ZulipMessage> loadedMessages = new ArrayList<>();
                for (int i = 0; i < jsonMessages.size(); i++) {
                    JsonObject message = jsonMessages.get(i).getAsJsonObject();
                    long id = message.get("id").getAsLong();
                    String sender = message.has("sender_full_name")
                            ? message.get("sender_full_name").getAsString()
                            : "Unknown";
                    String topic = message.has("topic")
                            ? message.get("topic").getAsString()
                            : (message.has("subject") ? message.get("subject").getAsString() : "");
                    String content = message.has("content")
                            ? message.get("content").getAsString()
                            : "";

                    loadedMessages.add(new ZulipMessage(id, sender, topic, EmojiShortcodes.replace(stripMarkdownImageLinks(content)), collectMessageImageHashes(message, content), buildReactionsMap(message)));
                }
                loadedMessages.sort(Comparator.comparingLong(ZulipMessage::id));

                this.runOnClient(() -> {
                    this.loadingMessages = false;
                    this.messages.clear();
                    this.messages.addAll(loadedMessages);
                    this.rebuildMessageLines();
                    this.statusMessage = "Loaded " + loadedMessages.size() + " messages from #" + streamName + ".";

                    if (this.topicField != null && this.topicField.getText().isBlank()) {
                        this.topicField.setText(defaultTopic());
                    }
                    this.updateButtons();
                });
            } catch (Exception e) {
                this.runOnClient(() -> {
                    this.loadingMessages = false;
                    this.rebuildMessageLines();
                    this.statusMessage = "Failed to load messages: " + e.getMessage();
                    this.updateButtons();
                });
            }
        });
    }

    private void loadUsers() {
        String validationError = validateAccountConfig(this.config);
        if (validationError != null) {
            this.mentionUserLoadRequested = false;
            this.statusMessage = validationError;
            this.users.clear();
            this.userRows.clear();
            this.updateButtons();
            return;
        }

        this.loadingChannels = true;
        this.statusMessage = "Loading users...";
        this.updateButtons();

        Thread.ofVirtual().name("ZulipBridgeGuiUsers").start(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(normalizeBaseUrl(this.config.zulipBaseUrl()) + "/api/v1/users"))
                        .header("Authorization", buildAuthHeader(this.config))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }

                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!"success".equals(body.get("result").getAsString())) {
                    throw new IllegalStateException(body.get("msg").getAsString());
                }

                JsonArray usersArray = body.getAsJsonArray("members");
                List<UserEntry> loadedUsers = new ArrayList<>();
                for (int i = 0; i < usersArray.size(); i++) {
                    JsonObject user = usersArray.get(i).getAsJsonObject();
                    String userId = user.get("user_id").getAsString();
                    String name = user.has("full_name") ? user.get("full_name").getAsString() : "Unknown";
                    String email = user.has("email") ? user.get("email").getAsString() : "";

                    if (!email.equals(this.config.botEmail())) {
                        loadedUsers.add(new UserEntry(userId, name, email));
                    }
                }
                loadedUsers.sort(Comparator.comparing(u -> u.name().toLowerCase(Locale.ROOT)));

                this.runOnClient(() -> {
                    this.loadingChannels = false;
                    this.mentionUserLoadRequested = false;
                    this.users.clear();
                    this.users.addAll(loadedUsers);
                    this.rebuildUserRows();
                    this.statusMessage = "Loaded " + loadedUsers.size() + " users.";
                    this.updateButtons();
                });
            } catch (Exception e) {
                this.runOnClient(() -> {
                    this.loadingChannels = false;
                    this.mentionUserLoadRequested = false;
                    this.users.clear();
                    this.userRows.clear();
                    this.statusMessage = "Failed to load users: " + e.getMessage();
                    this.updateButtons();
                });
            }
        });
    }

    private void rebuildUserRows() {
        this.userRows.clear();
        for (UserEntry user : this.users) {
            String label = "@" + user.name();
            this.userRows.add(new ChannelListRow(label, user.id(), false));
        }
    }

    private void selectUser(String userId, String userName) {
        if (userId == null || userId.isBlank()) return;
        if (userId.equals(this.selectedUserId) && !this.messages.isEmpty()) return;

        this.selectedUserId = userId;
        this.selectedUserName = userName;
        this.messages.clear();
        if (this.reactingToMessageId != 0) this.cancelReactionMode();
        this.rebuildMessageLines();
        this.loadDirectMessages(userId);
        
        this.unreadDms.remove(userId);
        this.updateButtons();
    }

    private void selectRecipientForNewDm(String userId, String userName) {
        UserEntry selectedUser = findUserById(userId);
        if (selectedUser == null) {
            this.selectUser(userId, userName);
            return;
        }

        if (this.newRecipientField != null) {
            String recipientValue = selectedUser.email() != null && !selectedUser.email().isBlank()
                    ? selectedUser.email()
                    : selectedUser.name();
            this.newRecipientField.setText(recipientValue);
        }

        this.statusMessage = "Recipient selected: @" + userName;
        this.updateButtons();
    }

    private UserEntry findUserById(String userId) {
        for (UserEntry user : this.users) {
            if (user.id().equals(userId)) return user;
        }
        return null;
    }

    private void loadDirectMessages(String userId) {
        if (userId == null || userId.isBlank()) return;

        String validationError = validateAccountConfig(this.config);
        if (validationError != null) {
            this.statusMessage = validationError;
            return;
        }

        this.loadingMessages = true;
        this.statusMessage = "Loading direct messages...";
        this.rebuildMessageLines();
        this.updateButtons();

        final String targetUserId = userId;
        final String botEmail = this.config.botEmail();
        final String baseUrl = normalizeBaseUrl(this.config.zulipBaseUrl());
        final String auth = buildAuthHeader(this.config);
        
        Thread.ofVirtual().name("ZulipBridgeGuiDMMessages").start(() -> {
            try {
                String narrow;
                try {
                    HttpRequest meReq = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/v1/users/me"))
                            .header("Authorization", auth)
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();
                    HttpResponse<String> meResp = this.httpClient.send(meReq, HttpResponse.BodyHandlers.ofString());
                    JsonObject meBody = JsonParser.parseString(meResp.body()).getAsJsonObject();
                    int myId = meBody.get("user_id").getAsInt();
                    narrow = "[{\"operator\":\"dm\",\"operand\":[" + myId + "," + targetUserId + "]}]";
                } catch (Exception e) {
                    narrow = "[{\"operator\":\"dm\",\"operand\":" + targetUserId + "}]";
                }

                String query = "anchor=newest"
                        + "&num_before=" + MAX_HISTORY_MESSAGES
                        + "&num_after=0"
                        + "&narrow=" + URLEncoder.encode(narrow, StandardCharsets.UTF_8)
                        + "&apply_markdown=false"
                        + "&client_gravatar=false";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/messages?" + query))
                        .header("Authorization", auth)
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
                }

                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!"success".equals(body.get("result").getAsString())) {
                    throw new IllegalStateException(body.get("msg").getAsString());
                }

                JsonArray jsonMessages = body.getAsJsonArray("messages");
                this.statusMessage = "Found " + jsonMessages.size() + " messages";

                List<ZulipMessage> loadedMessages = new ArrayList<>();
                for (int i = 0; i < jsonMessages.size(); i++) {
                    JsonObject message = jsonMessages.get(i).getAsJsonObject();
                    long id = message.get("id").getAsLong();
                    String sender = message.has("sender_full_name")
                            ? message.get("sender_full_name").getAsString()
                            : "Unknown";
                    String content = message.has("content")
                            ? message.get("content").getAsString()
                            : "";

                    loadedMessages.add(new ZulipMessage(id, sender, "", EmojiShortcodes.replace(stripMarkdownImageLinks(content)), collectMessageImageHashes(message, content), buildReactionsMap(message)));
                }
                loadedMessages.sort(Comparator.comparingLong(ZulipMessage::id));

                final int msgCount = loadedMessages.size();
                this.runOnClient(() -> {
                    this.loadingMessages = false;
                    this.messages.clear();
                    this.messages.addAll(loadedMessages);
                    this.rebuildMessageLines();
                    this.statusMessage = "Loaded " + msgCount + " direct messages.";
                    this.updateButtons();
                });
            } catch (Exception e) {
                this.runOnClient(() -> {
                    this.loadingMessages = false;
                    this.rebuildMessageLines();
                    this.statusMessage = "Failed to load messages: " + e.getMessage();
                    this.updateButtons();
                });
            }
        });
    }

    private void sendCurrentMessage() {
        if (this.sendingMessage) return;

        if (this.reactingToMessageId != 0) {
            String input = this.messageField != null ? this.messageField.getText() : "";
            sendReaction(this.reactingToMessageId, input);
            return;
        }

        String validationError = validateAccountConfig(this.config);
        if (validationError != null) {
            this.statusMessage = validationError;
            this.updateButtons();
            return;
        }

        if (this.showingDirectMessages) {
            if (this.composingNewDm) {
                if (this.newRecipientField == null || this.newRecipientField.getText().isBlank()) {
                    this.statusMessage = "Enter a recipient email or name.";
                    this.updateButtons();
                    return;
                }
            } else if (this.selectedUserId == null || this.selectedUserId.isBlank()) {
                this.statusMessage = "Select a user first.";
                this.updateButtons();
                return;
            }
        } else {
            if (this.selectedChannel == null || this.selectedChannel.isBlank()) {
                this.statusMessage = "Select a channel first.";
                this.updateButtons();
                return;
            }

            if (this.topicField == null || this.topicField.getText().isBlank()) {
                this.statusMessage = "Topic cannot be empty.";
                this.updateButtons();
                return;
            }
        }

        if (this.messageField == null || this.messageField.getText().isBlank()) {
            this.statusMessage = "Message cannot be empty.";
            this.updateButtons();
            return;
        }

        String channel = this.selectedChannel;
        String topic = this.topicField.getText().trim();
        String content = this.messageField.getText().trim();

        this.sendingMessage = true;
        this.statusMessage = "Sending message...";
        this.updateButtons();

        if (this.showingDirectMessages && this.composingNewDm) {
            String recipientInput = this.newRecipientField.getText().trim();
            this.sendNewDm(recipientInput, content);
            return;
        }

        final String targetUserId = this.selectedUserId;
        final String targetUserName = this.selectedUserName;
        Thread.ofVirtual().name("ZulipBridgeGuiSend").start(() -> {
            try {
                String form;
                if (this.showingDirectMessages) {
                    String recipientsJson = "[" + targetUserId + "]";
                    form = "type=direct"
                            + "&to=" + URLEncoder.encode(recipientsJson, StandardCharsets.UTF_8)
                            + "&content=" + URLEncoder.encode(content, StandardCharsets.UTF_8);
                } else {
                    form = "type=stream"
                            + "&to=" + URLEncoder.encode(channel, StandardCharsets.UTF_8)
                            + "&topic=" + URLEncoder.encode(topic, StandardCharsets.UTF_8)
                            + "&content=" + URLEncoder.encode(content, StandardCharsets.UTF_8);
                }

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(normalizeBaseUrl(this.config.zulipBaseUrl()) + "/api/v1/messages"))
                        .header("Authorization", buildAuthHeader(this.config))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();

                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + ": " + extractApiMessage(response.body()));
                }

                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!"success".equals(body.get("result").getAsString())) {
                    throw new IllegalStateException(extractApiMessage(response.body()));
                }

                if (body.has("id")) {
                    String source = this.showingDirectMessages
                            ? "DM @" + targetUserName
                            : "#" + channel + " > " + topic;
                    ZulipPollingThread.addOwnMessageId(body.get("id").getAsLong(), source);
                }

                this.runOnClient(() -> {
                    this.sendingMessage = false;
                    if (this.messageField != null) {
                        this.messageField.setText("");
                    }
                    if (this.showingDirectMessages) {
                        this.statusMessage = "Sent direct message to @" + targetUserName + ".";
                        this.loadDirectMessages(targetUserId);
                    } else {
                        this.statusMessage = "Sent to #" + channel + " > " + topic + ".";
                        this.loadMessages(channel);
                    }
                    this.updateButtons();
                });
            } catch (Exception e) {
                this.runOnClient(() -> {
                    this.sendingMessage = false;
                    this.statusMessage = "Send failed: " + e.getMessage();
                    this.updateButtons();
                });
            }
        });
    }

    private void sendNewDm(String recipientInput, String content) {
        final String baseUrl = normalizeBaseUrl(this.config.zulipBaseUrl());
        final String auth = buildAuthHeader(this.config);
        
        Thread.ofVirtual().name("ZulipBridgeGuiNewDm").start(() -> {
            try {
                String recipientId;
                
                if (recipientInput.contains("@")) {
                    String email = recipientInput.trim();
                    HttpRequest userByEmailReq = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/v1/users/" + URLEncoder.encode(email, StandardCharsets.UTF_8)))
                            .header("Authorization", auth)
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();
                    HttpResponse<String> userByEmailResp = this.httpClient.send(userByEmailReq, HttpResponse.BodyHandlers.ofString());
                    if (userByEmailResp.statusCode() != 200) {
                        throw new IllegalStateException("User not found: " + email);
                    }
                    JsonObject userByEmailBody = JsonParser.parseString(userByEmailResp.body()).getAsJsonObject();
                    recipientId = String.valueOf(userByEmailBody.get("user_id").getAsInt());
                } else {
                    String searchQuery = recipientInput.trim();
                    HttpRequest searchReq = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/v1/users?q=" + URLEncoder.encode(searchQuery, StandardCharsets.UTF_8)))
                            .header("Authorization", auth)
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();
                    HttpResponse<String> searchResp = this.httpClient.send(searchReq, HttpResponse.BodyHandlers.ofString());
                    if (searchResp.statusCode() != 200) {
                        throw new IllegalStateException("Search failed");
                    }
                    JsonObject searchBody = JsonParser.parseString(searchResp.body()).getAsJsonObject();
                    JsonArray results = searchBody.getAsJsonArray("users");
                    if (results.isEmpty()) {
                        throw new IllegalStateException("No user found: " + searchQuery);
                    }
                    recipientId = results.get(0).getAsJsonObject().get("user_id").getAsString();
                }

                String recipientsJson = "[" + recipientId + "]";
                String form = "type=direct"
                        + "&to=" + URLEncoder.encode(recipientsJson, StandardCharsets.UTF_8)
                        + "&content=" + URLEncoder.encode(content, StandardCharsets.UTF_8);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/messages"))
                        .header("Authorization", auth)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();

                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + ": " + extractApiMessage(response.body()));
                }

                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!"success".equals(body.get("result").getAsString())) {
                    throw new IllegalStateException(extractApiMessage(response.body()));
                }

                if (body.has("id")) {
                    ZulipPollingThread.addOwnMessageId(body.get("id").getAsLong(), "DM @" + recipientInput.trim());
                }

                this.runOnClient(() -> {
                    this.sendingMessage = false;
                    if (this.messageField != null) {
                        this.messageField.setText("");
                    }
                    this.statusMessage = "Sent new DM!";
                    this.composingNewDm = false;
                    this.updateButtons();
                });
            } catch (Exception e) {
                this.runOnClient(() -> {
                    this.sendingMessage = false;
                    this.statusMessage = "Send failed: " + e.getMessage();
                    this.updateButtons();
                });
            }
        });
    }

    private void enterReactionMode(long messageId) {
        this.reactingToMessageId = messageId;
        if (this.messageField != null) {
            this.messageField.setText(":");
            this.messageField.setPlaceholder(Text.literal("Emoji name..."));
            this.setFocused(this.messageField);
            // Move cursor to end so autocomplete triggers immediately.
            this.messageField.setCursorToEnd(false);
        }
        this.updateButtons();
    }

    private void cancelReactionMode() {
        this.reactingToMessageId = 0;
        if (this.messageField != null) {
            this.messageField.setText("");
            this.messageField.setPlaceholder(Text.literal("Message"));
        }
        this.updateButtons();
    }

    private void sendReaction(long messageId, String emojiInput) {
        ZulipBridgeClient.LOGGER.info("sendReaction called: messageId={}, input='{}'", messageId, emojiInput);

        String validationError = validateAccountConfig(this.config);
        if (validationError != null) {
            this.statusMessage = validationError;
            this.updateButtons();
            return;
        }

        // Parse the shortcode: strip surrounding colons and whitespace.
        String name = emojiInput.trim()
                .replaceAll("^:+", "")
                .replaceAll(":+\\s*$", "")
                .trim();

        ZulipBridgeClient.LOGGER.info("sendReaction: parsed name='{}'", name);

        if (name.isBlank()) {
            this.statusMessage = "Enter an emoji name (e.g. :thumbs_up:).";
            this.updateButtons();
            return;
        }

        // Determine emoji_code and reaction_type.
        String unicode = EmojiShortcodes.get(name);
        final String emojiCode;
        final String reactionType;
        if (unicode != null) {
            // Build code from Unicode codepoints, e.g. "1f44d" or "1f1fa-1f1f8".
            int[] codePoints = unicode.codePoints().toArray();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < codePoints.length; i++) {
                if (i > 0) sb.append('-');
                sb.append(Integer.toHexString(codePoints[i]));
            }
            emojiCode = sb.toString();
            reactionType = "unicode_emoji";
        } else {
            // Fall back: treat as a Zulip extra emoji (name == code).
            emojiCode = name;
            reactionType = "zulip_extra_emoji";
        }

        final String finalName = name;
        final long finalMessageId = messageId;

        this.sendingMessage = true;
        this.statusMessage = "Sending reaction...";
        this.updateButtons();

        Thread.ofVirtual().name("ZulipBridgeGuiReact").start(() -> {
            try {
                String form = "emoji_name=" + URLEncoder.encode(finalName, StandardCharsets.UTF_8)
                        + "&emoji_code=" + URLEncoder.encode(emojiCode, StandardCharsets.UTF_8)
                        + "&reaction_type=" + URLEncoder.encode(reactionType, StandardCharsets.UTF_8);

                String url = normalizeBaseUrl(this.config.zulipBaseUrl())
                        + "/api/v1/messages/" + finalMessageId + "/reactions";
                ZulipBridgeClient.LOGGER.info("sendReaction POST {} body={}", url, form);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", buildAuthHeader(this.config))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();

                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                ZulipBridgeClient.LOGGER.info("sendReaction response: {} {}", response.statusCode(), response.body());

                this.runOnClient(() -> {
                    this.sendingMessage = false;
                    this.reactingToMessageId = 0;
                    if (this.messageField != null) {
                        this.messageField.setText("");
                        this.messageField.setPlaceholder(Text.literal("Message"));
                    }
                    if (response.statusCode() != 200) {
                        this.statusMessage = "Reaction failed: HTTP " + response.statusCode();
                    } else {
                        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (!"success".equals(body.get("result").getAsString())) {
                            this.statusMessage = "Reaction failed: " + extractApiMessage(response.body());
                        } else {
                            this.statusMessage = "Reacted :" + finalName + ":";
                        }
                    }
                    this.updateButtons();
                });
            } catch (Exception e) {
                this.runOnClient(() -> {
                    this.sendingMessage = false;
                    this.reactingToMessageId = 0;
                    if (this.messageField != null) {
                        this.messageField.setText("");
                        this.messageField.setPlaceholder(Text.literal("Message"));
                    }
                    this.statusMessage = "Reaction failed: " + e.getMessage();
                    this.updateButtons();
                });
            }
        });
    }

    private void updateButtons() {
        if (this.refreshButton != null) {
            this.refreshButton.active = !this.loadingChannels;
        }

        if (this.sendButton != null) {
            boolean hasMessage = this.messageField != null && !this.messageField.getText().isBlank();
            boolean canSend;
            if (this.reactingToMessageId != 0) {
                this.sendButton.setMessage(Text.literal("React"));
                canSend = hasMessage;
            } else {
                this.sendButton.setMessage(Text.literal("Send"));
                if (this.showingDirectMessages) {
                    if (this.composingNewDm) {
                        canSend = this.newRecipientField != null && !this.newRecipientField.getText().isBlank() && hasMessage;
                    } else {
                        canSend = this.selectedUserId != null && !this.selectedUserId.isBlank() && hasMessage;
                    }
                } else {
                    boolean hasChannel = this.selectedChannel != null && !this.selectedChannel.isBlank();
                    boolean hasTopic = this.topicField != null && !this.topicField.getText().isBlank();
                    canSend = hasChannel && hasTopic && hasMessage;
                }
            }
            this.sendButton.active = !this.sendingMessage && canSend;
        }
    }

    private static class FormattedMessagePart {
        String text;
        boolean bold;
        boolean italic;
        boolean code;
        int color;
        
        FormattedMessagePart(String text) {
            this.text = text;
            this.bold = false;
            this.italic = false;
            this.code = false;
            this.color = 0xFFFFFFFF;
        }
    }
    
    private List<FormattedMessagePart> parseMarkdown(String content) {
        List<FormattedMessagePart> parts = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            parts.add(new FormattedMessagePart(""));
            return parts;
        }
        
        int i = 0;
        while (i < content.length()) {
            if (content.startsWith("**", i)) {
                int end = content.indexOf("**", i + 2);
                if (end != -1) {
                    FormattedMessagePart part = new FormattedMessagePart(content.substring(i + 2, end));
                    part.bold = true;
                    parts.add(part);
                    i = end + 2;
                    continue;
                }
            }
            if (content.startsWith("`", i)) {
                int end = content.indexOf("`", i + 1);
                if (end != -1) {
                    FormattedMessagePart part = new FormattedMessagePart(content.substring(i + 1, end));
                    part.code = true;
                    parts.add(part);
                    i = end + 1;
                    continue;
                }
            }
            if (content.startsWith("*", i) || content.startsWith("_", i)) {
                char c = content.charAt(i);
                int end = -1;
                if (c == '*') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '*') {
                        i += 2;
                        continue;
                    }
                    end = content.indexOf("*", i + 1);
                } else {
                    end = content.indexOf("_", i + 1);
                }
                if (end != -1) {
                    FormattedMessagePart part = new FormattedMessagePart(content.substring(i + 1, end));
                    part.italic = true;
                    parts.add(part);
                    i = end + 1;
                    continue;
                }
            }
            
            int nextSpecial = -1;
            int[] candidates = {
                content.indexOf("**", i),
                content.indexOf("*", i),
                content.indexOf("_", i),
                content.indexOf("`", i)
            };
            for (int c : candidates) {
                if (c != -1 && (nextSpecial == -1 || c < nextSpecial)) {
                    nextSpecial = c;
                }
            }
            
            if (nextSpecial == -1) {
                parts.add(new FormattedMessagePart(content.substring(i)));
                break;
            } else {
                parts.add(new FormattedMessagePart(content.substring(i, nextSpecial)));
                i = nextSpecial;
            }
        }
        
        return parts;
    }

    private void rebuildMessageLines() {
        this.renderedMessageLines.clear();
        int maxLineWidth = Math.max(40, this.messagesWidth - 10);

        if (this.loadingMessages) {
            this.renderedMessageLines.add(new DisplayLine("Loading messages...", 0xFFC8C8C8));
            this.messageScroll = 0;
            return;
        }

        if (this.showingDirectMessages) {
            if (this.composingNewDm) {
                this.renderedMessageLines.add(new DisplayLine("Enter recipient and message below to start a new DM.", 0xFF9F9F9F));
                this.messageScroll = 0;
                return;
            }
            if (this.selectedUserId == null || this.selectedUserId.isBlank()) {
                this.renderedMessageLines.add(new DisplayLine("Select a user from the left.", 0xFF9F9F9F));
                this.messageScroll = 0;
                return;
            }
        } else {
            if (this.selectedChannel == null || this.selectedChannel.isBlank()) {
                this.renderedMessageLines.add(new DisplayLine("Select a channel from the left.", 0xFF9F9F9F));
                this.messageScroll = 0;
                return;
            }
        }

        if (this.messages.isEmpty()) {
            if (this.showingDirectMessages) {
                this.renderedMessageLines.add(new DisplayLine("No messages yet.", 0xFF9F9F9F));
            } else {
                this.renderedMessageLines.add(new DisplayLine("No messages in #" + this.selectedChannel + " yet.", 0xFF9F9F9F));
            }
            this.messageScroll = 0;
            return;
        }

        for (ZulipMessage message : this.messages) {
            long msgId = message.id();
            String topic = (message.topic() == null || message.topic().isBlank()) ? "(no topic)" : message.topic();
            String sender = (message.sender() == null || message.sender().isBlank()) ? "Unknown" : message.sender();
            String header = "[" + topic + "] " + sender;

            List<String> wrappedHeaders = wrapText(header, maxLineWidth);
            for (int hi = 0; hi < wrappedHeaders.size(); hi++) {
                if (hi == 0) {
                    this.renderedMessageLines.add(DisplayLine.header(wrappedHeaders.get(hi), 0xFF67D0FF, msgId));
                } else {
                    this.renderedMessageLines.add(DisplayLine.content(wrappedHeaders.get(hi), 0xFF67D0FF, List.of(), msgId));
                }
            }

            FormattedContent formattedWithLinks = applyFormattingCodesWithLinks(message.content());
            String formatted = formattedWithLinks.text();
            if (formatted.isBlank() && message.imageHashes().isEmpty()) {
                formatted = "(no content)";
                formattedWithLinks = new FormattedContent(formatted, List.of());
            }

            if (!formatted.isBlank()) {
                InlineEmojiContent inlineEmojiContent = replaceCustomEmojiWithPlaceholders(formatted);
                List<LinkSpan> adjustedLinks = adjustSpansForEmojiReplacements(formatted, formattedWithLinks.links());
                List<WrappedSegment> wrappedSegments = wrapTextTracked(inlineEmojiContent.text(), Math.max(20, maxLineWidth - 10), adjustedLinks);
                int inlineEmojiIndex = 0;
                for (WrappedSegment seg : wrappedSegments) {
                    String wrappedLine = seg.text();
                    List<String> lineInlineEmojis = InlineEmoji.slice(inlineEmojiContent.imageHashes(), inlineEmojiIndex, InlineEmoji.countPlaceholders(wrappedLine));
                    inlineEmojiIndex += lineInlineEmojis.size();
                    // Adjust link spans for the "  " indent prefix added below
                    List<LinkSpan> prefixedLinks = new ArrayList<>(seg.links().size());
                    for (LinkSpan s : seg.links()) prefixedLinks.add(new LinkSpan(s.start() + 2, s.end() + 2, s.url()));
                    this.renderedMessageLines.add(DisplayLine.content("  " + wrappedLine, 0xFFFFFFFF, lineInlineEmojis, msgId, prefixedLinks));
                }
            }

            for (String imageHash : message.imageHashes()) {
                this.renderedMessageLines.add(DisplayLine.image(imageHash, msgId));
                for (int i = 1; i < GUI_IMAGE_ROW_SPAN; i++) {
                    this.renderedMessageLines.add(DisplayLine.imageSpacer(imageHash, msgId));
                }
            }

            String reactionsText = formatReactions(message.reactions());
            if (!reactionsText.isBlank()) {
                for (String wrappedReaction : wrapText(reactionsText, maxLineWidth)) {
                    this.renderedMessageLines.add(DisplayLine.content("  " + wrappedReaction, 0xFFD4C050, List.of(), msgId));
                }
            }

            this.renderedMessageLines.add(new DisplayLine("", 0xFFFFFFFF));
        }

        this.messageScroll = this.maxMessageScroll();
    }

    private String applyFormattingCodes(String content) {
        return applyFormattingCodesWithLinks(content).text();
    }

    private FormattedContent applyFormattingCodesWithLinks(String content) {
        if (content == null || content.isEmpty()) return new FormattedContent("", List.of());

        StringBuilder sb = new StringBuilder();
        List<LinkSpan> allLinks = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        boolean inCodeBlock = false;

        for (int li = 0; li < lines.length; li++) {
            if (li > 0) sb.append('\n');
            String line = lines[li];

            if (line.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                if (li > 0) {
                    int len = sb.length();
                    if (len > 0 && sb.charAt(len - 1) == '\n') sb.deleteCharAt(len - 1);
                }
                continue;
            }

            if (inCodeBlock) {
                sb.append("\u00A77").append(line).append("\u00A7r");
                continue;
            }

            // Headings: # … through ######
            int hashes = 0;
            while (hashes < line.length() && line.charAt(hashes) == '#') hashes++;
            if (hashes > 0 && hashes <= 6 && hashes < line.length() && line.charAt(hashes) == ' ') {
                String headingText = line.substring(hashes + 1);
                sb.append("\u00A7l\u00A7b");
                int base = sb.length();
                FormattedContent inner = applyInlineFormattingCodesWithLinks(headingText);
                sb.append(inner.text()).append("\u00A7r");
                for (LinkSpan s : inner.links()) allLinks.add(new LinkSpan(base + s.start(), base + s.end(), s.url()));
                continue;
            }

            // Block quote: > text
            if (line.startsWith("> ") || line.equals(">")) {
                String quoteText = line.length() > 2 ? line.substring(2) : "";
                sb.append("\u00A77| \u00A7o");
                int base = sb.length();
                FormattedContent inner = applyInlineFormattingCodesWithLinks(quoteText);
                sb.append(inner.text()).append("\u00A7r");
                for (LinkSpan s : inner.links()) allLinks.add(new LinkSpan(base + s.start(), base + s.end(), s.url()));
                continue;
            }

            // List items: - / * / + / 1.
            java.util.regex.Matcher listM = java.util.regex.Pattern.compile("^(\\s*)([-*+]\\s+|\\d+\\.\\s+)(.*)$").matcher(line);
            if (listM.matches()) {
                String indent = listM.group(1);
                sb.append(indent).append("\u00A79\u2022 \u00A7r");
                int base = sb.length();
                FormattedContent inner = applyInlineFormattingCodesWithLinks(listM.group(3));
                sb.append(inner.text());
                for (LinkSpan s : inner.links()) allLinks.add(new LinkSpan(base + s.start(), base + s.end(), s.url()));
                continue;
            }

            int base = sb.length();
            FormattedContent inner = applyInlineFormattingCodesWithLinks(line);
            sb.append(inner.text());
            for (LinkSpan s : inner.links()) allLinks.add(new LinkSpan(base + s.start(), base + s.end(), s.url()));
        }

        return new FormattedContent(sb.toString(), allLinks);
    }

    private String applyInlineFormattingCodes(String text) {
        return applyInlineFormattingCodesWithLinks(text).text();
    }

    private FormattedContent applyInlineFormattingCodesWithLinks(String text) {
        if (text == null || text.isEmpty()) return new FormattedContent("", List.of());
        StringBuilder result = new StringBuilder();
        List<LinkSpan> spans = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);

            // [label](url) markdown link
            if (c == '[') {
                Matcher m = MARKDOWN_LINK_PATTERN.matcher(text).region(i, text.length());
                if (m.lookingAt()) {
                    String label = m.group(1);
                    String rawUrl = normalizeMarkdownLinkUrl(m.group(2));
                    String resolved = resolveImageUrl(rawUrl);
                    if (!resolved.isBlank() && !looksLikeImageReference(label, rawUrl)) {
                        result.append("\u00A7n\u00A7b");
                        int spanStart = result.length();
                        result.append(label);
                        int spanEnd = result.length();
                        result.append("\u00A7r");
                        spans.add(new LinkSpan(spanStart, spanEnd, resolved));
                        i = m.end();
                        continue;
                    }
                }
            }

            // Bare https?:// URL
            if (c == 'h' && text.regionMatches(i, "http", 0, 4)) {
                Matcher m = URL_PATTERN.matcher(text).region(i, text.length());
                if (m.lookingAt()) {
                    String url = m.group(1);
                    // Strip trailing punctuation that is not part of the URL
                    while (url.length() > 0) {
                        char last = url.charAt(url.length() - 1);
                        if (last == '.' || last == ',' || last == ')' || last == ']' || last == '\'' || last == '"') {
                            url = url.substring(0, url.length() - 1);
                        } else {
                            break;
                        }
                    }
                    if (!url.isBlank()) {
                        result.append("\u00A7n\u00A7b");
                        int spanStart = result.length();
                        result.append(url);
                        int spanEnd = result.length();
                        result.append("\u00A7r");
                        spans.add(new LinkSpan(spanStart, spanEnd, url));
                        i += url.length();
                        // Skip any punctuation we stripped
                        while (i < text.length()) {
                            char next = text.charAt(i);
                            if (next == '.' || next == ',' || next == ')' || next == ']' || next == '\'' || next == '"') {
                                result.append(next);
                                i++;
                            } else {
                                break;
                            }
                        }
                        continue;
                    }
                }
            }

            // **bold**
            if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("**", i + 2);
                if (end > i + 1) {
                    result.append("\u00A7l");
                    int innerBase = result.length();
                    FormattedContent inner = applyInlineFormattingCodesWithLinks(text.substring(i + 2, end));
                    result.append(inner.text()).append("\u00A7r");
                    for (LinkSpan s : inner.links()) spans.add(new LinkSpan(innerBase + s.start(), innerBase + s.end(), s.url()));
                    i = end + 2;
                    continue;
                }
            }

            // ~~strikethrough~~
            if (c == '~' && i + 1 < text.length() && text.charAt(i + 1) == '~') {
                int end = text.indexOf("~~", i + 2);
                if (end > i + 1) {
                    result.append("\u00A7m");
                    int innerBase = result.length();
                    FormattedContent inner = applyInlineFormattingCodesWithLinks(text.substring(i + 2, end));
                    result.append(inner.text()).append("\u00A7r");
                    for (LinkSpan s : inner.links()) spans.add(new LinkSpan(innerBase + s.start(), innerBase + s.end(), s.url()));
                    i = end + 2;
                    continue;
                }
            }

            // *italic* (single asterisk, not part of **)
            if (c == '*' && (i + 1 >= text.length() || text.charAt(i + 1) != '*')) {
                int end = i + 1;
                while (end < text.length() && text.charAt(end) != '*') end++;
                if (end < text.length() && end > i + 1) {
                    result.append("\u00A7o");
                    int innerBase = result.length();
                    FormattedContent inner = applyInlineFormattingCodesWithLinks(text.substring(i + 1, end));
                    result.append(inner.text()).append("\u00A7r");
                    for (LinkSpan s : inner.links()) spans.add(new LinkSpan(innerBase + s.start(), innerBase + s.end(), s.url()));
                    i = end + 1;
                    continue;
                }
            }

            // `inline code`
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i) {
                    result.append("\u00A77").append(text, i + 1, end).append("\u00A7r");
                    i = end + 1;
                    continue;
                }
            }

            result.append(c);
            i++;
        }
        return new FormattedContent(result.toString(), spans);
    }

    private List<String> wrapText(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            lines.add("");
            return lines;
        }

        String normalized = text.replace('\t', ' ');
        String[] paragraphs = normalized.split("\\R", -1);
        for (String paragraph : paragraphs) {
            if (paragraph.isBlank()) {
                lines.add("");
                continue;
            }

            String remaining = paragraph.trim();
            while (!remaining.isEmpty()) {
                String line = this.textRenderer.trimToWidth(remaining, maxWidth);
                if (line.isEmpty()) {
                    lines.add(remaining.substring(0, 1));
                    remaining = remaining.substring(1);
                    continue;
                }

                if (line.length() < remaining.length() && remaining.charAt(line.length()) != ' ') {
                    int lastSpace = line.lastIndexOf(' ');
                    if (lastSpace > 0) {
                        line = line.substring(0, lastSpace);
                    }
                }

                lines.add(line);
                remaining = remaining.substring(line.length()).trim();
            }
        }

        return lines;
    }

    /**
     * Wraps {@code text} at {@code maxWidth} pixels while tracking the starting character offset
     * of each wrapped line in {@code text}.  Link spans (positions in {@code text}) are filtered
     * and rebased so they are relative to each wrapped line's start.
     */
    private List<WrappedSegment> wrapTextTracked(String text, int maxWidth, List<LinkSpan> allSpans) {
        List<WrappedSegment> result = new ArrayList<>();
        if (text == null) {
            result.add(new WrappedSegment("", List.of()));
            return result;
        }

        String normalized = text.replace('\t', ' ');
        int pos = 0;

        while (pos <= normalized.length()) {
            // Find next paragraph boundary (\n, \r\n, or end of string)
            int lineEnd = normalized.length();
            int afterBreak = normalized.length() + 1; // sentinel: "we're done"
            for (int j = pos; j < normalized.length(); j++) {
                char ch = normalized.charAt(j);
                if (ch == '\n') { lineEnd = j; afterBreak = j + 1; break; }
                if (ch == '\r') {
                    lineEnd = j;
                    afterBreak = (j + 1 < normalized.length() && normalized.charAt(j + 1) == '\n') ? j + 2 : j + 1;
                    break;
                }
            }

            String paragraph = normalized.substring(pos, lineEnd);

            if (paragraph.isBlank()) {
                result.add(new WrappedSegment("", List.of()));
                if (afterBreak > normalized.length()) break;
                pos = afterBreak;
                continue;
            }

            // Trim leading/trailing spaces, tracking offsets
            int trimLeft = 0;
            while (trimLeft < paragraph.length() && paragraph.charAt(trimLeft) == ' ') trimLeft++;
            int trimRight = paragraph.length();
            while (trimRight > trimLeft && paragraph.charAt(trimRight - 1) == ' ') trimRight--;

            String remaining = paragraph.substring(trimLeft, trimRight);
            int lineOffset = pos + trimLeft;

            while (!remaining.isEmpty()) {
                String line = this.textRenderer.trimToWidth(remaining, maxWidth);
                if (line.isEmpty()) {
                    line = remaining.substring(0, 1);
                } else if (line.length() < remaining.length() && remaining.charAt(line.length()) != ' ') {
                    int lastSpace = line.lastIndexOf(' ');
                    if (lastSpace > 0) line = line.substring(0, lastSpace);
                }

                int segEnd = lineOffset + line.length();
                List<LinkSpan> segSpans = new ArrayList<>();
                for (LinkSpan span : allSpans) {
                    if (span.start() < segEnd && span.end() > lineOffset) {
                        segSpans.add(new LinkSpan(
                            Math.max(0, span.start() - lineOffset),
                            Math.min(line.length(), span.end() - lineOffset),
                            span.url()
                        ));
                    }
                }
                result.add(new WrappedSegment(line, segSpans));

                lineOffset += line.length();
                String remainder = remaining.substring(line.length());
                int spaces = 0;
                while (spaces < remainder.length() && remainder.charAt(spaces) == ' ') spaces++;
                remaining = remainder.substring(spaces);
                lineOffset += spaces;
            }

            if (afterBreak > normalized.length()) break;
            pos = afterBreak;
        }

        return result;
    }

    /**
     * Adjusts link span character positions to account for emoji shortcode replacements.
     * Each replaced `:shortcode:` shrinks the string by {@code shortcode.length() + 2 - 1} chars.
     */
    private List<LinkSpan> adjustSpansForEmojiReplacements(String originalText, List<LinkSpan> spans) {
        if (spans.isEmpty()) return spans;

        // Collect (start, originalLen, replacementLen) for each emoji match
        List<int[]> reps = new ArrayList<>();
        Matcher m = SHORTCODE_PATTERN.matcher(originalText);
        while (m.find()) {
            if (CustomEmojiRegistry.get(m.group(1)) != null) {
                reps.add(new int[]{m.start(), m.end() - m.start(), InlineEmoji.PLACEHOLDER_TEXT.length()});
            }
        }
        if (reps.isEmpty()) return spans;

        List<LinkSpan> adjusted = new ArrayList<>(spans.size());
        for (LinkSpan span : spans) {
            int newStart = shiftPosition(span.start(), reps);
            int newEnd = shiftPosition(span.end(), reps);
            if (newStart < newEnd) adjusted.add(new LinkSpan(newStart, newEnd, span.url()));
        }
        return adjusted;
    }

    private static int shiftPosition(int pos, List<int[]> replacements) {
        int shift = 0;
        for (int[] rep : replacements) {
            int repOrigEnd = rep[0] + rep[1];
            if (rep[0] >= pos) break; // replacement is at or after pos, no effect
            if (pos < repOrigEnd) {
                // pos is inside the replaced region — snap to after replacement
                return rep[0] + rep[2] + shift;
            }
            shift += rep[2] - rep[1];
        }
        return pos + shift;
    }

    private InlineEmojiContent replaceCustomEmojiWithPlaceholders(String content) {
        if (content == null || content.isEmpty()) return new InlineEmojiContent("", List.of());

        Matcher matcher = SHORTCODE_PATTERN.matcher(content);
        StringBuilder result = new StringBuilder();
        List<String> imageHashes = new ArrayList<>();
        while (matcher.find()) {
            CustomEmojiRegistry.CustomEmoji customEmoji = CustomEmojiRegistry.get(matcher.group(1));
            if (customEmoji == null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }

            String imageHash = ImageCache.hashUrl(customEmoji.url());
            imageHashes.add(imageHash);
            scheduleScreenImageLoad(imageHash, customEmoji.url());
            matcher.appendReplacement(result, Matcher.quoteReplacement(InlineEmoji.PLACEHOLDER_TEXT));
        }
        matcher.appendTail(result);
        return new InlineEmojiContent(result.toString(), imageHashes);
    }

    private void rebuildChannelRows() {
        this.channelRows.clear();
        String currentFolder = null;

        for (ChannelEntry channel : this.channels) {
            if (currentFolder == null || !currentFolder.equals(channel.folderName())) {
                currentFolder = channel.folderName();
                this.channelRows.add(new ChannelListRow("v " + currentFolder, null, true));
            }

            String label = "#" + channel.name();
            if (channel.pinned()) label += " [pinned]";
            if (channel.muted()) label += " [muted]";
            this.channelRows.add(new ChannelListRow(label, channel.name(), false));
        }
    }

    private boolean containsChannelName(String name) {
        for (ChannelEntry channel : this.channels) {
            if (channel.name().equals(name)) return true;
        }
        return false;
    }

    private static int countDistinctFolders(List<ChannelEntry> channels) {
        int count = 0;
        String lastFolder = null;
        for (ChannelEntry channel : channels) {
            if (lastFolder == null || !lastFolder.equals(channel.folderName())) {
                count++;
                lastFolder = channel.folderName();
            }
        }
        return count;
    }

    private static Comparator<ChannelEntry> channelComparator() {
        return Comparator
                .comparingInt(ChannelEntry::folderOrder)
                .thenComparing(channel -> channel.folderName().toLowerCase(Locale.ROOT))
                .thenComparing((ChannelEntry channel) -> !channel.pinned())
                .thenComparing(ChannelEntry::muted)
                .thenComparing(channel -> channel.name().toLowerCase(Locale.ROOT));
    }

    private Map<Integer, FolderInfo> fetchFolderInfoMap() throws Exception {
        HttpRequest folderRequest = HttpRequest.newBuilder()
                .uri(URI.create(normalizeBaseUrl(this.config.zulipBaseUrl()) + "/api/v1/channel_folders?include_archived=false"))
                .header("Authorization", buildAuthHeader(this.config))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> folderResponse = this.httpClient.send(folderRequest, HttpResponse.BodyHandlers.ofString());
        if (folderResponse.statusCode() == 404) {
            return Map.of();
        }
        if (folderResponse.statusCode() != 200) {
            throw new IllegalStateException("channel_folders HTTP " + folderResponse.statusCode());
        }

        JsonObject folderBody = JsonParser.parseString(folderResponse.body()).getAsJsonObject();
        if (!"success".equals(folderBody.get("result").getAsString())) {
            throw new IllegalStateException(folderBody.get("msg").getAsString());
        }

        Map<Integer, FolderInfo> folders = new HashMap<>();
        JsonArray folderArray = folderBody.getAsJsonArray("channel_folders");
        for (int i = 0; i < folderArray.size(); i++) {
            JsonObject folder = folderArray.get(i).getAsJsonObject();
            if (readBoolean(folder, "is_archived")) continue;

            int id = folder.get("id").getAsInt();
            String name = folder.has("name") ? folder.get("name").getAsString() : ("Folder " + id);
            int order = folder.has("order") ? folder.get("order").getAsInt() : id;
            folders.put(id, new FolderInfo(name, order));
        }

        return folders;
    }

    private static boolean readBoolean(JsonObject jsonObject, String field) {
        return jsonObject.has(field) && !jsonObject.get(field).isJsonNull() && jsonObject.get(field).getAsBoolean();
    }

    private static Integer readNullableInt(JsonObject jsonObject, String field) {
        if (!jsonObject.has(field) || jsonObject.get(field).isJsonNull()) return null;
        return jsonObject.get(field).getAsInt();
    }

    private boolean isInsideChannels(double mouseX, double mouseY) {
        return mouseX >= this.channelsX
                && mouseX < this.channelsX + this.channelsWidth
                && mouseY >= this.channelsY
                && mouseY < this.channelsY + this.channelsHeight;
    }

    private boolean isInsideMessages(double mouseX, double mouseY) {
        return mouseX >= this.messagesX
                && mouseX < this.messagesX + this.messagesWidth
                && mouseY >= this.messagesY
                && mouseY < this.messagesY + this.messagesHeight;
    }

    private int visibleChannelRows() {
        return Math.max(1, (this.channelsHeight - PANEL_HEADER_HEIGHT - 4) / CHANNEL_ROW_HEIGHT);
    }

    private int maxChannelScroll() {
        return Math.max(0, this.channelRows.size() - this.visibleChannelRows());
    }

    private int visibleMessageRows() {
        return Math.max(1, (this.messagesHeight - PANEL_HEADER_HEIGHT - 4) / MESSAGE_LINE_HEIGHT);
    }

    private int maxMessageScroll() {
        return Math.max(0, this.renderedMessageLines.size() - this.visibleMessageRows());
    }

    private void runOnClient(Runnable action) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        client.execute(() -> {
            if (client.currentScreen == this) {
                action.run();
            }
        });
    }

    private static String defaultTopic() {
        String topic = ZulipBridgeClient.CONFIG.topicName();
        return topic == null || topic.isBlank() ? "minecraft" : topic;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        return Math.min(value, max);
    }

    private static void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
        int x2 = x + width;
        int y2 = y + height;
        context.fill(x, y, x2, y + 1, color);
        context.fill(x, y2 - 1, x2, y2, color);
        context.fill(x, y + 1, x + 1, y2 - 1, color);
        context.fill(x2 - 1, y + 1, x2, y2 - 1, color);
    }

    private static String normalizeBaseUrl(String rawBaseUrl) {
        if (rawBaseUrl == null) return "";
        String trimmed = rawBaseUrl.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String validateAccountConfig(ZulipBridgeConfig cfg) {
        if (cfg.zulipBaseUrl() == null || cfg.zulipBaseUrl().isBlank()) return "Missing Zulip Base URL.";
        if (cfg.botEmail() == null || cfg.botEmail().isBlank()) return "Missing Account Email.";
        if (cfg.botApiKey() == null || cfg.botApiKey().isBlank()) return "Missing Account API Key.";
        return null;
    }

    private static String buildAuthHeader(ZulipBridgeConfig cfg) {
        String creds = cfg.botEmail() + ":" + cfg.botApiKey();
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    /** Parses the {@code reactions} array from a message JSON object into an emoji→count map. */
    private static Map<String, Integer> buildReactionsMap(JsonObject message) {
        if (!message.has("reactions") || !message.get("reactions").isJsonArray()) return new java.util.LinkedHashMap<>();
        JsonArray reactions = message.getAsJsonArray("reactions");
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < reactions.size(); i++) {
            if (!reactions.get(i).isJsonObject()) continue;
            JsonObject reaction = reactions.get(i).getAsJsonObject();
            String emojiName = reaction.has("emoji_name") ? reaction.get("emoji_name").getAsString() : "";
            if (!emojiName.isBlank()) {
                counts.merge(emojiName, 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Formats an emoji→count map into a display string like {@code 👍 3  ❤️ 2}. */
    private static String formatReactions(Map<String, Integer> reactions) {
        if (reactions == null || reactions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : reactions.entrySet()) {
            String unicode = EmojiShortcodes.get(entry.getKey());
            String display = unicode != null ? unicode : ":" + entry.getKey() + ":";
            if (!sb.isEmpty()) sb.append("  ");
            sb.append(display).append(" ").append(entry.getValue());
        }
        return sb.toString();
    }

    private List<String> collectMessageImageHashes(JsonObject message, String content) {
        LinkedHashSet<String> imageUrls = new LinkedHashSet<>();

        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(content == null ? "" : content);
        while (matcher.find()) {
            String resolvedUrl = resolveMarkdownImageUrl(matcher.group(1), matcher.group(2));
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

        List<String> imageHashes = new ArrayList<>(imageUrls.size());
        for (String imageUrl : imageUrls) {
            String imageHash = ImageCache.hashUrl(imageUrl);
            imageHashes.add(imageHash);
            scheduleScreenImageLoad(imageHash, imageUrl);
        }
        return imageHashes;
    }

    private void scheduleScreenImageLoad(String imageHash, String imageUrl) {
        if (ImageCache.lookup(imageHash) != null) return;

        Thread.ofVirtual().name("ZulipBridgeGuiImage-" + imageHash).start(() -> {
            try {
                String downloadUrl = resolveImageDownloadUrl(imageUrl);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(downloadUrl))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<byte[]> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return;
                }

                byte[] bytes = response.body();
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                boolean gif = contentType.toLowerCase(Locale.ROOT).endsWith("/gif") || imageUrl.toLowerCase(Locale.ROOT).endsWith(".gif");
                MinecraftClient client = MinecraftClient.getInstance();
                if (client == null) return;
                client.execute(() -> ImageCache.getOrLoad(imageHash, bytes, imageUrl, gif));
            } catch (Exception ignored) {
            }
        });
    }

    private String resolveImageDownloadUrl(String imageUrl) throws Exception {
        URI uri = URI.create(imageUrl);
        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.isBlank()) return imageUrl;

        Matcher matcher = USER_UPLOAD_PATH_PATTERN.matcher(rawPath);
        if (!matcher.matches()) return imageUrl;

        String realmId = matcher.group(1);
        String filename = matcher.group(2);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizeBaseUrl(this.config.zulipBaseUrl()) + "/api/v1/user_uploads/" + realmId + "/" + filename))
                .header("Authorization", buildAuthHeader(this.config))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return imageUrl;
        }

        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!"success".equals(body.get("result").getAsString()) || !body.has("url")) {
            return imageUrl;
        }
        return resolveImageUrl(body.get("url").getAsString());
    }

    private String resolveMarkdownImageUrl(String label, String url) {
        String normalizedUrl = normalizeMarkdownLinkUrl(url);
        if (!looksLikeImageReference(label, normalizedUrl)) return null;
        return resolveImageUrl(normalizedUrl);
    }

    private String resolveImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) return "";
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) return imageUrl;
        String baseUrl = normalizeBaseUrl(this.config.zulipBaseUrl());
        if (imageUrl.startsWith("/")) return baseUrl + imageUrl;
        return baseUrl + "/" + imageUrl;
    }

    private static boolean looksLikeImageReference(String label, String url) {
        if (looksLikeImageFile(label) || looksLikeImageFile(url)) {
            return true;
        }

        String lowerUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return lowerUrl.contains("/user_uploads/") && !lowerUrl.endsWith(".pdf");
    }

    private static boolean looksLikeImageFile(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".gif")
                || lower.endsWith(".webp")
                || lower.endsWith(".bmp");
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

    private static String escapeJson(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String simplifyMarkdown(String content) {
        return content == null
                ? ""
                : content
                .replaceAll("\\*\\*(.*?)\\*\\*", "$1")
                .replaceAll("\\*(.*?)\\*", "$1")
                .replaceAll("`(.*?)`", "$1")
                .replace("\n", " | ")
                .trim();
    }

    private String stripMarkdownImageLinks(String content) {
        if (content == null || content.isEmpty()) return "";

        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(content);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String resolvedUrl = resolveMarkdownImageUrl(matcher.group(1), matcher.group(2));
            if (resolvedUrl == null) {
                continue;
            }

            result.append(content, last, matcher.start());
            last = matcher.end();
        }

        if (last == 0) {
            return content;
        }

        result.append(content.substring(last));
        return result.toString()
                .replaceAll("(?m)[ \t]+$", "")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private static String extractApiMessage(String body) {
        if (body == null || body.isBlank()) return "Unknown API response";
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("msg")) {
                return json.get("msg").getAsString();
            }
        } catch (Exception ignored) {
        }
        return body.length() > 160 ? body.substring(0, 157) + "..." : body;
    }

    private String findPreviewableImageAt(int mouseX, int mouseY) {
        for (int i = this.previewableImages.size() - 1; i >= 0; i--) {
            PreviewableImage image = this.previewableImages.get(i);
            if (image.contains(mouseX, mouseY)) {
                return image.imageHash();
            }
        }
        return null;
    }

    private record ChannelEntry(String name, String folderName, int folderOrder, boolean pinned, boolean muted) {
    }

    private record ChannelListRow(String label, String channelName, boolean folderHeader) {
    }

    private record FolderInfo(String name, int order) {
    }

    private record ZulipMessage(long id, String sender, String topic, String content, List<String> imageHashes, Map<String, Integer> reactions) {
    }

    private record DisplayLine(String text, int color, String imageHash, boolean imageSpacer, List<String> inlineEmojiHashes, long messageId, boolean isHeader, List<LinkSpan> links) {
        /** Non-message line (placeholder, separator, etc.). */
        DisplayLine(String text, int color) {
            this(text, color, null, false, List.of(), 0, false, List.of());
        }

        static DisplayLine header(String text, int color, long messageId) {
            return new DisplayLine(text, color, null, false, List.of(), messageId, true, List.of());
        }

        static DisplayLine content(String text, int color, List<String> inlineEmojiHashes, long messageId) {
            return new DisplayLine(text, color, null, false, inlineEmojiHashes, messageId, false, List.of());
        }

        static DisplayLine content(String text, int color, List<String> inlineEmojiHashes, long messageId, List<LinkSpan> links) {
            return new DisplayLine(text, color, null, false, inlineEmojiHashes, messageId, false, links);
        }

        static DisplayLine image(String imageHash) {
            return new DisplayLine("", 0xFFFFFFFF, imageHash, false, List.of(), 0, false, List.of());
        }

        static DisplayLine image(String imageHash, long messageId) {
            return new DisplayLine("", 0xFFFFFFFF, imageHash, false, List.of(), messageId, false, List.of());
        }

        static DisplayLine imageSpacer(String imageHash) {
            return new DisplayLine("", 0xFFFFFFFF, imageHash, true, List.of(), 0, false, List.of());
        }

        static DisplayLine imageSpacer(String imageHash, long messageId) {
            return new DisplayLine("", 0xFFFFFFFF, imageHash, true, List.of(), messageId, false, List.of());
        }
    }

    private record LinkSpan(int start, int end, String url) {}

    private record FormattedContent(String text, List<LinkSpan> links) {}

    private record WrappedSegment(String text, List<LinkSpan> links) {}

    /** Bounds of the [+] reaction button currently visible on screen, null if none. */
    private record ReactionButton(long messageId, int x, int y, int w, int h) {
        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record InlineEmojiContent(String text, List<String> imageHashes) {
    }

    private record UserEntry(String id, String name, String email) {
    }

    private record NewMessageInfo(String source, String sender, String content) {
    }

    private record PreviewableImage(String imageHash, int x, int y, int width, int height) {
        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private record AutocompleteSuggestion(String label, String insertText) {
    }

    private void displayMessagesInChat(List<NewMessageInfo> messages) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud == null) return;

        for (NewMessageInfo info : messages) {
            String prefix = "[" + info.source() + "]";
            Text chatMessage = Text.literal(prefix + " ")
                    .setStyle(Style.EMPTY.withColor(Formatting.AQUA))
                    .append(Text.literal(info.sender() + ": ")
                            .setStyle(Style.EMPTY.withColor(0xFF67D0FF)))
                    .append(Text.literal(info.content().length() > 100 
                            ? info.content().substring(0, 100) + "..." 
                            : info.content())
                            .setStyle(Style.EMPTY.withColor(0xFFFFFFFF)));

            client.inGameHud.getChatHud().addMessage(chatMessage);
        }
    }
}
