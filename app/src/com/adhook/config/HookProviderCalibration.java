package com.adhook.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Per-process Provider budget, independent of remote snapshot freshness and wall-clock changes. */
final class HookProviderCalibration {
    static final int MAX_PACKAGES = 8;
    static final long INTERVAL_MS = 30000L;
    static final long MAX_FAILURE_BACKOFF_MS = 300000L;
    private long nextAllowedAt;
    private int failures;
    private int cursor;

    synchronized String take(long now, Collection<String> packages) {
        if (now < nextAllowedAt || packages.isEmpty()) return null;
        List<String> ordered = new ArrayList<>(packages);
        Collections.sort(ordered);
        if (ordered.size() > MAX_PACKAGES) ordered = ordered.subList(0, MAX_PACKAGES);
        String pkg = ordered.get(cursor % ordered.size());
        cursor = (cursor + 1) % ordered.size();
        nextAllowedAt = now + INTERVAL_MS; // Reserve before IPC; one in-flight attempt consumes its slot.
        return pkg;
    }

    synchronized void completed(long now, boolean success) {
        if (success) {
            failures = 0;
            nextAllowedAt = Math.max(nextAllowedAt, now + INTERVAL_MS);
        } else {
            failures = Math.min(4, failures + 1);
            long delay = Math.min(MAX_FAILURE_BACKOFF_MS, INTERVAL_MS * (1L << failures));
            nextAllowedAt = Math.max(nextAllowedAt, now + delay);
        }
    }
}
