package dev.remgr.zulipbridge.chat;

import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class ChatImageRegistry {
    private static final int MAX_LINE_MAPPINGS = 512;
    private static final int MAX_DISPLAY_MAPPINGS = 256;

    private static final Map<String, List<ChatImageThumbnail>> THUMBNAILS_BY_MESSAGE = new HashMap<>();
    private static final Map<Text, String> MESSAGE_BY_DISPLAY = new IdentityHashMap<>();
    private static final ArrayDeque<Text> DISPLAY_ORDER = new ArrayDeque<>();
    private static final Map<ChatHudLine.Visible, String> MESSAGE_BY_VISIBLE_LINE = new IdentityHashMap<>();
    private static final ArrayDeque<ChatHudLine.Visible> VISIBLE_LINE_ORDER = new ArrayDeque<>();
    private static final Map<String, ArrayDeque<String>> MESSAGE_HASHES_BY_LINE_TEXT = new HashMap<>();
    private static final ArrayDeque<String> LINE_TEXT_ORDER = new ArrayDeque<>();

    private ChatImageRegistry() {
    }

    public static synchronized void addThumbnail(String messageHash, ChatImageThumbnail thumbnail) {
        THUMBNAILS_BY_MESSAGE.computeIfAbsent(messageHash, ignored -> new ArrayList<>()).add(thumbnail);
    }

    public static synchronized void bindDisplayText(String messageHash, Text displayText) {
        if (displayText == null || messageHash == null || messageHash.isBlank()) return;

        MESSAGE_BY_DISPLAY.put(displayText, messageHash);
        DISPLAY_ORDER.addLast(displayText);
        trimDisplays();
    }

    public static synchronized String takeMessageHashForDisplay(Text displayText) {
        if (displayText == null) return null;
        return MESSAGE_BY_DISPLAY.remove(displayText);
    }

    public static synchronized void bindVisibleLine(ChatHudLine.Visible visibleLine, String messageHash) {
        if (visibleLine == null || messageHash == null || messageHash.isBlank()) return;

        MESSAGE_BY_VISIBLE_LINE.put(visibleLine, messageHash);
        VISIBLE_LINE_ORDER.addLast(visibleLine);
        trimVisibleLines();
    }

    public static synchronized void bindVisibleLineText(String lineText, String messageHash) {
        if (lineText == null || lineText.isBlank() || messageHash == null || messageHash.isBlank()) return;

        MESSAGE_HASHES_BY_LINE_TEXT.computeIfAbsent(lineText, ignored -> new ArrayDeque<>()).addFirst(messageHash);
        LINE_TEXT_ORDER.addLast(lineText);
        trimLineTexts();
    }

    public static synchronized String findMessageHashForVisibleLine(ChatHudLine.Visible visibleLine) {
        if (visibleLine == null) return null;
        return MESSAGE_BY_VISIBLE_LINE.get(visibleLine);
    }

    public static synchronized String findMessageHashForLineText(String lineText, int occurrenceIndex) {
        if (lineText == null || lineText.isBlank() || occurrenceIndex < 0) return null;

        ArrayDeque<String> messageHashes = MESSAGE_HASHES_BY_LINE_TEXT.get(lineText);
        if (messageHashes == null || messageHashes.isEmpty()) return null;

        int index = 0;
        for (String messageHash : messageHashes) {
            if (index == occurrenceIndex) return messageHash;
            index++;
        }
        return null;
    }

    public static synchronized List<ChatImageThumbnail> getForMessage(String messageHash) {
        if (messageHash == null || messageHash.isBlank()) return Collections.emptyList();
        List<ChatImageThumbnail> thumbnails = THUMBNAILS_BY_MESSAGE.get(messageHash);
        if (thumbnails == null || thumbnails.isEmpty()) return Collections.emptyList();
        return List.copyOf(thumbnails);
    }

    public static synchronized void clear() {
        THUMBNAILS_BY_MESSAGE.clear();
        MESSAGE_BY_DISPLAY.clear();
        DISPLAY_ORDER.clear();
        MESSAGE_BY_VISIBLE_LINE.clear();
        VISIBLE_LINE_ORDER.clear();
        MESSAGE_HASHES_BY_LINE_TEXT.clear();
        LINE_TEXT_ORDER.clear();
    }

    private static void trimDisplays() {
        while (DISPLAY_ORDER.size() > MAX_DISPLAY_MAPPINGS) {
            Text oldest = DISPLAY_ORDER.removeFirst();
            MESSAGE_BY_DISPLAY.remove(oldest);
        }
    }

    private static void trimVisibleLines() {
        while (VISIBLE_LINE_ORDER.size() > MAX_LINE_MAPPINGS) {
            ChatHudLine.Visible oldest = VISIBLE_LINE_ORDER.removeFirst();
            MESSAGE_BY_VISIBLE_LINE.remove(oldest);
        }
    }

    private static void trimLineTexts() {
        while (LINE_TEXT_ORDER.size() > MAX_LINE_MAPPINGS) {
            String oldestLineText = LINE_TEXT_ORDER.removeFirst();
            ArrayDeque<String> messageHashes = MESSAGE_HASHES_BY_LINE_TEXT.get(oldestLineText);
            if (messageHashes == null || messageHashes.isEmpty()) continue;

            messageHashes.removeLast();
            if (messageHashes.isEmpty()) {
                MESSAGE_HASHES_BY_LINE_TEXT.remove(oldestLineText);
            }
        }
    }
}
