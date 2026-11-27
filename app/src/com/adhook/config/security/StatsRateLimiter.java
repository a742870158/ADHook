package com.adhook.config.security;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Per-UID limit shared by all owned packages; elapsed time is supplied to make it testable. */
public final class StatsRateLimiter {
    public static final long MIN_INTERVAL_MS = 1000;
    public static final long WINDOW_MS = 60_000;
    public static final int MAX_REPORTS_PER_WINDOW = 30;
    private static final int MAX_TRACKED_UIDS = 2000;
    private final Map<Integer, Window> windows = new HashMap<>();

    public synchronized boolean tryAcquire(int uid, long elapsedMs) {
        if (uid < 0 || elapsedMs < 0) return false;
        Window window = windows.get(uid);
        if (window != null && elapsedMs >= window.start && elapsedMs - window.start < WINDOW_MS) {
            if (elapsedMs - window.last < MIN_INTERVAL_MS || window.count >= MAX_REPORTS_PER_WINDOW) return false;
            window.count++;
            window.last = elapsedMs;
            return true;
        }
        if (windows.size() >= MAX_TRACKED_UIDS && window == null) {
            Iterator<Window> values = windows.values().iterator();
            while (values.hasNext()) {
                Window value = values.next();
                if (elapsedMs < value.start || elapsedMs - value.start >= WINDOW_MS) values.remove();
            }
            if (windows.size() >= MAX_TRACKED_UIDS) return false;
        }
        windows.put(uid, new Window(elapsedMs));
        return true;
    }

    private static final class Window {
        final long start;
        long last;
        int count = 1;
        Window(long now) { start = last = now; }
    }
}
