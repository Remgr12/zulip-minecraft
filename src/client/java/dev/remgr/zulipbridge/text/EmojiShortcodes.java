package dev.remgr.zulipbridge.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmojiShortcodes {
    private static final String SHORTCODE_RESOURCE = "/assets/zulip-bridge/emoji/shortcodes.tsv";
    private static final Pattern SHORTCODE_PATTERN = Pattern.compile(":([a-z0-9_+\\-]+):", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> EMOJIS = loadEmojiMap();
    private static final List<String> SHORTCODE_NAMES = EMOJIS.keySet().stream().sorted(Comparator.naturalOrder()).toList();

    private EmojiShortcodes() {
    }

    public static String replace(String content) {
        if (content == null || content.isEmpty()) return "";

        Matcher matcher = SHORTCODE_PATTERN.matcher(content);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = resolve(matcher.group(1).toLowerCase());
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement == null ? matcher.group(0) : replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String get(String shortcode) {
        if (shortcode == null || shortcode.isBlank()) return null;
        return resolve(shortcode.toLowerCase());
    }

    /** Resolves a shortcode to its emoji string, including flag_XX country codes. */
    private static String resolve(String shortcode) {
        String emoji = EMOJIS.get(shortcode);
        if (emoji != null) return emoji;
        // :flag_XX: → regional indicator pair (e.g. :flag_us: → 🇺🇸)
        if (shortcode.startsWith("flag_") && shortcode.length() == 7) {
            return flagEmoji(shortcode.substring(5));
        }
        return null;
    }

    /**
     * Converts a two-letter ISO 3166-1 alpha-2 country code to its flag emoji.
     * Returns null if the code is not exactly two lowercase ASCII letters.
     */
    private static String flagEmoji(String code) {
        if (code.length() != 2) return null;
        char a = code.charAt(0), b = code.charAt(1);
        if (a < 'a' || a > 'z' || b < 'a' || b > 'z') return null;
        // Regional Indicator Symbol Letters: U+1F1E6 (A) … U+1F1FF (Z)
        return new String(new int[]{0x1F1E6 + (a - 'a'), 0x1F1E6 + (b - 'a')}, 0, 2);
    }

    public static CustomEmojiRegistry.CustomEmoji getCustom(String shortcode) {
        return CustomEmojiRegistry.get(shortcode);
    }

    public static List<String> suggest(String query, int limit) {
        if (limit <= 0) return List.of();

        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        Set<String> customNames = CustomEmojiRegistry.names();
        List<String> allNames = new ArrayList<>(SHORTCODE_NAMES.size() + customNames.size());
        allNames.addAll(SHORTCODE_NAMES);
        allNames.addAll(customNames);
        allNames.sort(Comparator.naturalOrder());

        if (allNames.isEmpty()) return List.of();

        List<String> startsWith = new ArrayList<>(limit);
        List<String> contains = new ArrayList<>(limit);
        for (String shortcode : allNames) {
            if (normalizedQuery.isEmpty() || shortcode.startsWith(normalizedQuery)) {
                if (!startsWith.contains(shortcode)) startsWith.add(shortcode);
            } else if (shortcode.contains(normalizedQuery)) {
                if (!contains.contains(shortcode)) contains.add(shortcode);
            }

            if (startsWith.size() >= limit) {
                return List.copyOf(startsWith);
            }
        }

        List<String> results = new ArrayList<>(Math.min(limit, startsWith.size() + contains.size()));
        results.addAll(startsWith);
        for (String shortcode : contains) {
            if (results.size() >= limit) break;
            results.add(shortcode);
        }
        return List.copyOf(results);
    }

    public static String replaceCustomWithLabels(String content) {
        if (content == null || content.isEmpty()) return "";

        Matcher matcher = SHORTCODE_PATTERN.matcher(content);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            CustomEmojiRegistry.CustomEmoji customEmoji = getCustom(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(customEmoji == null ? matcher.group(0) : "[" + customEmoji.name() + "]"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Map<String, String> loadEmojiMap() {
        Map<String, String> emojis = new HashMap<>();
        try (InputStream stream = EmojiShortcodes.class.getResourceAsStream(SHORTCODE_RESOURCE)) {
            if (stream == null) {
                return Map.of();
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;

                    int separator = line.indexOf('\t');
                    if (separator <= 0 || separator >= line.length() - 1) continue;

                    String shortcode = line.substring(0, separator).trim().toLowerCase();
                    String emoji = line.substring(separator + 1);
                    if (!shortcode.isEmpty() && !emoji.isEmpty()) {
                        emojis.putIfAbsent(shortcode, emoji);
                    }
                }
            }
        } catch (IOException ignored) {
            return Map.of();
        }
        return Map.copyOf(emojis);
    }
}
