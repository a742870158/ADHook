package com.adhook.config.security;

/** Android-free validation for process-scoped telemetry; never a source of authorization. */
public final class RuntimeStatsMetadata {
    public static final int MAX_PROCESS_SUFFIX_LENGTH = 128;
    public static final int MAX_PROCESS_NAME_LENGTH = 256 + 1 + MAX_PROCESS_SUFFIX_LENGTH;
    public static final int SESSION_ID_LENGTH = 32;

    private RuntimeStatsMetadata() {}

    public static boolean isValid(String packageName, String processName, String sessionId) {
        return isProcessName(packageName, processName) && isSessionId(sessionId);
    }

    /** Accept only the owning package's main process or a bounded private ':suffix'. */
    public static boolean isProcessName(String packageName, String processName) {
        if (!ConfigInputPolicy.isPackageName(packageName) || processName == null
                || processName.length() > MAX_PROCESS_NAME_LENGTH) return false;
        if (packageName.equals(processName)) return true;
        String prefix = packageName + ":";
        if (!processName.startsWith(prefix)) return false;
        String suffix = processName.substring(prefix.length());
        if (suffix.isEmpty() || suffix.length() > MAX_PROCESS_SUFFIX_LENGTH) return false;
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (!isLetter(c) && !(c >= '0' && c <= '9') && c != '_' && c != '-' && c != '.') return false;
        }
        return true;
    }

    /** UUID.toString().replace("-", "") produces exactly these 32 hexadecimal characters. */
    public static boolean isSessionId(String sessionId) {
        if (sessionId == null || sessionId.length() != SESSION_ID_LENGTH) return false;
        for (int i = 0; i < sessionId.length(); i++) {
            char c = sessionId.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F')) return false;
        }
        return true;
    }

    private static boolean isLetter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }
}
