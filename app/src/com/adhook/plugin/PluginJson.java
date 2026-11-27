package com.adhook.plugin;

/** Minimal JSON lexical guards shared by the manifest and market parsers. */
final class PluginJson {

    private PluginJson() {}

    /**
     * True when the top-level object repeats a key (escapes decoded before comparison) or a top
     * level string is unterminated. org.json would keep the last duplicate silently, so the raw
     * bytes and the parsed view could disagree.
     */
    static boolean hasDuplicateTopLevelKeys(String text) {
        if (text == null) return false;
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        int depth = 0;
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '"') {
                int end = endOfString(text, index);
                if (end < 0) return false; // malformed JSON is rejected by the JSON parser
                if (depth == 1) {
                    int probe = end;
                    while (probe < text.length() && Character.isWhitespace(text.charAt(probe))) probe++;
                    if (probe < text.length() && text.charAt(probe) == ':') {
                        String key = unescape(text.substring(index + 1, end - 1));
                        if (!keys.add(key)) return true;
                        index = probe + 1;
                        continue;
                    }
                }
                index = end;
                continue;
            }
            if (current == '{' || current == '[') depth++;
            else if (current == '}' || current == ']') depth--;
            index++;
        }
        return false;
    }

    static boolean hasUtf8Bom(byte[] bytes) {
        return bytes != null && bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF
                && (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF;
    }

    private static int endOfString(String text, int start) {
        boolean escaped = false;
        for (int index = start + 1; index < text.length(); index++) {
            char current = text.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                return index + 1;
            }
        }
        return -1;
    }

    private static String unescape(String raw) {
        StringBuilder builder = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (current != '\\' || index + 1 >= raw.length()) {
                builder.append(current);
                continue;
            }
            char next = raw.charAt(++index);
            switch (next) {
                case '"': builder.append('"'); break;
                case '\\': builder.append('\\'); break;
                case '/': builder.append('/'); break;
                case 'b': builder.append('\b'); break;
                case 'f': builder.append('\f'); break;
                case 'n': builder.append('\n'); break;
                case 'r': builder.append('\r'); break;
                case 't': builder.append('\t'); break;
                case 'u':
                    if (index + 4 < raw.length()) {
                        try {
                            builder.append((char) Integer.parseInt(raw.substring(index + 1, index + 5), 16));
                            index += 4;
                        } catch (NumberFormatException malformed) {
                            builder.append(next);
                        }
                    } else {
                        builder.append(next);
                    }
                    break;
                default: builder.append(next);
            }
        }
        return builder.toString();
    }
}
