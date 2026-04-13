package dev.remgr.zulipbridge.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InlineEmoji {
    public static final char PLACEHOLDER = '\u2003';
    public static final String PLACEHOLDER_TEXT = String.valueOf(PLACEHOLDER);

    private InlineEmoji() {
    }

    public static int countPlaceholders(String text) {
        if (text == null || text.isEmpty()) return 0;

        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == PLACEHOLDER) {
                count++;
            }
        }
        return count;
    }

    public static List<String> slice(List<String> values, int start, int count) {
        if (values == null || values.isEmpty() || count <= 0 || start < 0 || start >= values.size()) {
            return Collections.emptyList();
        }

        int end = Math.min(values.size(), start + count);
        return new ArrayList<>(values.subList(start, end));
    }
}
