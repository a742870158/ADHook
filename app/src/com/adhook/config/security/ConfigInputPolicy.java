package com.adhook.config.security;

/** Android-free validation of exported IPC identifiers. */
public final class ConfigInputPolicy {
    private ConfigInputPolicy() {}

    public static boolean isPackageName(String value) {
        if (value == null || value.isEmpty() || value.length() > 256) return false;
        boolean dot = false;
        boolean first = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                if (first) return false;
                dot = true;
                first = true;
            } else {
                if (!(isLetter(c) || c == '_' || (!first && c >= '0' && c <= '9'))) return false;
                first = false;
            }
        }
        return dot && !first;
    }

    public static boolean isFeatureId(String value) {
        if (value == null || value.isEmpty() || value.length() > 128) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isLetter(c) && !(c >= '0' && c <= '9') && c != '-' && c != '_' && c != '.') return false;
        }
        return true;
    }

    public static boolean isRuleId(String value) {
        if (value == null || value.isEmpty() || value.indexOf('\u0000') >= 0) return false;
        if (!value.startsWith("adhook:v2:")) return value.length() <= 128;
        if (value.length() > 4096) return false;
        String[] parts = value.split(":", -1);
        if (parts.length != 6 || !("72656d6f7465".equals(parts[2]) || "676b64".equals(parts[2]))) return false;
        for (int part = 2; part < parts.length; part++) {
            if (parts[part].isEmpty() || parts[part].length() % 2 != 0) return false;
            for (int i = 0; i < parts[part].length(); i++) {
                char c = parts[part].charAt(i);
                if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) return false;
            }
        }
        return true;
    }

    private static boolean isLetter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }
}
