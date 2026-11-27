package com.adhook.core;

import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.adhook.runtime.HookBridge;

/**
 * Logcat remains available for diagnosis; framework bridge traffic is independently controlled.
 * Production defaults to deduplicated errors only. Enabling verbose logcat does not enable bridge IPC.
 */
public final class AdHookLog {
    public static final String TAG = "ADHook";
    public enum BridgeMode { OFF, ERRORS_ONLY, ALL }

    private static volatile boolean sVerbose;
    private static final BridgePolicy BRIDGE_POLICY = new BridgePolicy();
    private static final int MAX_BRIDGE_MESSAGE_LENGTH = 4096;

    private AdHookLog() {}

    public static void setVerbose(boolean verbose) { sVerbose = verbose; }
    public static boolean isVerbose() { return sVerbose; }
    public static void setBridgeMode(BridgeMode mode) {
        BRIDGE_POLICY.setMode(mode != null ? mode : BridgeMode.ERRORS_ONLY);
    }

    public static void i(String message) { emit(Log.INFO, TAG, message, null); }
    public static void i(String tag, String message) { emit(Log.INFO, tag, message, null); }
    public static void w(String message) { emit(Log.WARN, TAG, message, null); }
    public static void w(String tag, String message) { emit(Log.WARN, tag, message, null); }
    public static void e(String message, Throwable error) { emit(Log.ERROR, TAG, message, error); }
    public static void d(String message) {
        if (sVerbose) emit(Log.DEBUG, TAG, message, null);
    }
    public static void d(String tag, String message) {
        if (sVerbose) emit(Log.DEBUG, tag, message, null);
    }

    private static void emit(int priority, String tag, String message, Throwable error) {
        String safeTag = tag != null ? tag : TAG;
        String safeMessage = message != null ? message : "";
        // Logging must never throw into a host, even while the Android/framework backend is unavailable.
        try {
            if (error != null) Log.e(safeTag, safeMessage, error);
            else Log.println(priority, safeTag, safeMessage);
        } catch (Throwable ignored) {
        }
        String key = priority + ":" + safeTag + ":" + safeMessage;
        if (!BRIDGE_POLICY.acquire(priority, key, System.nanoTime() / 1_000_000L)) return;
        try {
            String bridgeMessage = safeTag + ": " + safeMessage;
            if (error != null) {
                StringWriter stack = new StringWriter();
                error.printStackTrace(new PrintWriter(stack));
                bridgeMessage += "\n" + stack;
            }
            if (bridgeMessage.length() > MAX_BRIDGE_MESSAGE_LENGTH) {
                bridgeMessage = bridgeMessage.substring(0, MAX_BRIDGE_MESSAGE_LENGTH) + " [truncated]";
            }
            HookBridge.log(bridgeMessage);
        } catch (Throwable ignored) {
        }
    }

    /** Pure policy kept separate from Android/Xposed backends so the production gate is unit-testable. */
    static final class BridgePolicy {
        static final int MAX_KEYS = 128;
        static final int MAX_PER_WINDOW = 20;
        static final long WINDOW_MS = 1_000L;
        static final long DUPLICATE_INTERVAL_MS = 10_000L;
        private BridgeMode mode = BridgeMode.ERRORS_ONLY;
        private final Map<String, Long> lastMessages = new LinkedHashMap<>(16, 0.75f, true);
        private long windowStart;
        private boolean windowStarted;
        private int windowCount;

        synchronized void setMode(BridgeMode newMode) {
            mode = newMode;
            lastMessages.clear();
            windowStarted = false;
            windowCount = 0;
        }

        synchronized boolean acquire(int priority, String message, long now) {
            if (mode == BridgeMode.OFF || (mode == BridgeMode.ERRORS_ONLY && priority < Log.ERROR)) {
                return false;
            }
            if (!windowStarted || now < windowStart || now - windowStart >= WINDOW_MS) {
                windowStarted = true;
                windowStart = now;
                windowCount = 0;
            }
            String key = message.length() <= 512 ? message : message.substring(0, 512);
            Long last = lastMessages.get(key);
            if (last != null && now >= last && now - last < DUPLICATE_INTERVAL_MS) return false;
            if (windowCount >= MAX_PER_WINDOW) return false;
            windowCount++;
            lastMessages.put(key, now);
            if (lastMessages.size() > MAX_KEYS) {
                Iterator<String> keys = lastMessages.keySet().iterator();
                keys.next();
                keys.remove();
            }
            return true;
        }

        synchronized int cachedMessageCount() { return lastMessages.size(); }
    }
}
