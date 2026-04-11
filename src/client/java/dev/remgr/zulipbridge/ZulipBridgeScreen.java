package dev.remgr.zulipbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.remgr.zulipbridge.config.ZulipBridgeConfig;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private static final int MAX_HISTORY_MESSAGES = 100;

    private final ZulipBridgeConfig config;
    private final HttpClient httpClient;

    private final List<ChannelEntry> channels = new ArrayList<>();
    private final List<ChannelListRow> channelRows = new ArrayList<>();
    private final List<UserEntry> users = new ArrayList<>();
    private final List<ChannelListRow> userRows = new ArrayList<>();
    private final List<ZulipMessage> messages = new ArrayList<>();
    private final List<DisplayLine> renderedMessageLines = new ArrayList<>();

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
    private int channelScroll = 0;
    private int messageScroll = 0;

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
    }

    @Override
    protected void init() {
        String previousTopic = this.topicField != null ? this.topicField.getText() : "";
        String previousMessage = this.messageField != null ? this.messageField.getText() : "";

        this.recalculateLayout();

        int composeY = this.height - BOTTOM_MARGIN - INPUT_HEIGHT;
        int sendWidth = 64;
        int spacing = 6;
        
        int topicWidth;
        int messageWidth;
        
        if (this.showingDirectMessages) {
            topicWidth = 0;
            messageWidth = this.messagesWidth - sendWidth - spacing;
        } else {
            topicWidth = Math.min(220, Math.max(90, this.messagesWidth / 3));
            messageWidth = this.messagesWidth - topicWidth - sendWidth - (spacing * 2);
            if (messageWidth < 80) {
                messageWidth = 80;
                topicWidth = Math.max(80, this.messagesWidth - messageWidth - sendWidth - (spacing * 2));
            }
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
            int newDmButtonWidth = 70;
            this.composeNewDmButton = this.addDrawableChild(
                    ButtonWidget.builder(Text.literal("New DM"), button -> this.toggleNewDmMode())
                            .dimensions(this.messagesX, composeY - INPUT_HEIGHT - spacing, newDmButtonWidth, INPUT_HEIGHT)
                            .build()
            );

            if (this.composingNewDm || this.selectedUserId == null) {
                String previousRecipient = this.newRecipientField != null ? this.newRecipientField.getText() : "";
                int recipientWidth = Math.min(180, this.messagesWidth - newDmButtonWidth - spacing * 2);
                this.newRecipientField = new TextFieldWidget(
                        this.textRenderer,
                        this.messagesX + newDmButtonWidth + spacing,
                        composeY - INPUT_HEIGHT - spacing,
                        recipientWidth,
                        INPUT_HEIGHT,
                        Text.literal("Recipient")
                );
                this.newRecipientField.setMaxLength(200);
                this.newRecipientField.setText(previousRecipient);
                this.newRecipientField.setPlaceholder(Text.literal("Email or name..."));
                this.addDrawableChild(this.newRecipientField);
            }
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
                ButtonWidget.builder(Text.literal("Poll Settings"), button -> this.showPollSettings())
                        .dimensions(this.width - RIGHT_MARGIN - 90, tabY, 90, INPUT_HEIGHT)
                        .build()
        );

        this.setInitialFocus(this.messageField);
        this.updateButtons();

        if (this.channels.isEmpty() && !this.loadingChannels) {
            this.loadChannels();
        } else if (this.selectedChannel != null && this.messages.isEmpty() && !this.loadingMessages) {
            this.loadMessages(this.selectedChannel);
        }
    }

    @Override
    public void tick() {
        this.updateButtons();
        
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
        this.drawMessagesPanel(context);
        
        if (this.showingPollSettings) {
            this.drawPollSettingsPanel(context, mouseX, mouseY);
        }

        super.render(context, mouseX, mouseY, delta);
    }
    
    private void drawPollSettingsPanel(DrawContext context, int mouseX, int mouseY) {
        int panelWidth = 300;
        int panelHeight = 400;
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;
        
        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xEE1C1C1C);
        drawBorder(context, panelX, panelY, panelWidth, panelHeight, 0xFF4F4F4F);
        
        context.drawTextWithShadow(this.textRenderer, "Poll Settings", panelX + 10, panelY + 10, 0xFFFFFFFF);
        
        context.drawTextWithShadow(this.textRenderer, "Select channels to poll:", panelX + 10, panelY + 30, 0xFFAAAAAA);
        
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

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (super.mouseClicked(click, doubleClick)) return true;

        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

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
                        this.selectUser(userId, userName);
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

        return false;
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

    private boolean handleKeyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && (this.messageField != null && this.messageField.isFocused()
                || this.topicField != null && this.topicField.isFocused())) {
            this.sendCurrentMessage();
            return true;
        }

        return super.keyPressed(new KeyInput(keyCode, scanCode, modifiers));
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        return this.handleKeyPressed(keyInput.key(), keyInput.scancode(), keyInput.modifiers());
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

    private void drawMessagesPanel(DrawContext context) {
        int x2 = this.messagesX + this.messagesWidth;
        int y2 = this.messagesY + this.messagesHeight;

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
        for (int i = 0; i < visibleLines; i++) {
            int lineIndex = this.messageScroll + i;
            if (lineIndex >= this.renderedMessageLines.size()) break;

            DisplayLine line = this.renderedMessageLines.get(lineIndex);
            int y = lineTop + (i * MESSAGE_LINE_HEIGHT);
            context.drawTextWithShadow(this.textRenderer, line.text(), this.messagesX + 4, y, line.color());
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
        this.selectedUserId = null;
        this.selectedUserName = null;
        this.channelRows.clear();
        this.loadChannels();
        this.updateButtons();
    }

    private void showDirectMessagesTab() {
        this.showingDirectMessages = true;
        this.selectedChannel = null;
        this.messages.clear();
        this.rebuildMessageLines();
        this.userRows.clear();
        this.loadUsers();
        this.updateButtons();
    }

    private void toggleNewDmMode() {
        this.composingNewDm = !this.composingNewDm;
        if (this.composingNewDm) {
            this.selectedUserId = null;
            this.selectedUserName = null;
            this.messages.clear();
            this.rebuildMessageLines();
        }
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
        this.updateButtons();
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

                    loadedMessages.add(new ZulipMessage(id, sender, topic, content));
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
                    this.users.clear();
                    this.users.addAll(loadedUsers);
                    this.rebuildUserRows();
                    this.statusMessage = "Loaded " + loadedUsers.size() + " users.";
                    this.updateButtons();
                });
            } catch (Exception e) {
                this.runOnClient(() -> {
                    this.loadingChannels = false;
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
        this.rebuildMessageLines();
        this.loadDirectMessages(userId);
        
        this.unreadDms.remove(userId);
        this.updateButtons();
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

                    loadedMessages.add(new ZulipMessage(id, sender, "", content));
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

    private void updateButtons() {
        if (this.refreshButton != null) {
            this.refreshButton.active = !this.loadingChannels;
        }

        if (this.sendButton != null) {
            boolean hasMessage = this.messageField != null && !this.messageField.getText().isBlank();
            boolean canSend;
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
            String topic = (message.topic() == null || message.topic().isBlank()) ? "(no topic)" : message.topic();
            String sender = (message.sender() == null || message.sender().isBlank()) ? "Unknown" : message.sender();
            String header = "[" + topic + "] " + sender;

            for (String wrappedHeader : wrapText(header, maxLineWidth)) {
                this.renderedMessageLines.add(new DisplayLine(wrappedHeader, 0xFF67D0FF));
            }

            String formatted = applyFormattingCodes(message.content());
            if (formatted.isBlank()) formatted = "(no content)";

            List<String> wrappedContent = wrapText(formatted, Math.max(20, maxLineWidth - 10));
            for (String wrappedLine : wrappedContent) {
                this.renderedMessageLines.add(new DisplayLine("  " + wrappedLine, 0xFFFFFFFF));
            }
            this.renderedMessageLines.add(new DisplayLine("", 0xFFFFFFFF));
        }

        this.messageScroll = this.maxMessageScroll();
    }

    private String applyFormattingCodes(String content) {
        if (content == null || content.isEmpty()) return "";
        return content
                .replace("**", "\u00A7l\u00A7r")
                .replace("*", "\u00A7o\u00A7r")
                .replace("`", "\u00A77\u00A7r");
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

    private record ChannelEntry(String name, String folderName, int folderOrder, boolean pinned, boolean muted) {
    }

    private record ChannelListRow(String label, String channelName, boolean folderHeader) {
    }

    private record FolderInfo(String name, int order) {
    }

    private record ZulipMessage(long id, String sender, String topic, String content) {
    }

    private record DisplayLine(String text, int color, boolean bold, boolean italic, boolean code) {
        DisplayLine(String text, int color) {
            this(text, color, false, false, false);
        }
    }

    private record UserEntry(String id, String name, String email) {
    }

    private record NewMessageInfo(String source, String sender, String content) {
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
