package com.adhook.core;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * One bounded latest-value slot with a trailing flush and acknowledged, bounded retry.
 * A snapshot is cumulative: an acknowledged resend must replace, never add to, the receiver's value.
 * Uses monotonic time supplied by the caller. No Android dependency, so race/timing paths are testable.
 */
public final class LatestStatsDelivery<T> {
    @FunctionalInterface public interface Scheduler { void schedule(Runnable task, long delayMs); }
    @FunctionalInterface public interface Sender<T> { boolean send(String packageName, T payload); }

    private final LongSupplier clock;
    private final Scheduler scheduler;
    private final Sender<T> sender;
    private final long intervalMs;
    private final int maxAttempts;
    private Supplier<T> latest;
    private String packageName;
    private long version;
    private long lastAttempt = -1L;
    private int failures;
    private boolean scheduled;
    private boolean running;

    public LatestStatsDelivery(LongSupplier clock, Scheduler scheduler, Sender<T> sender,
                               long intervalMs, int maxAttempts) {
        if (intervalMs < 1 || maxAttempts < 1) throw new IllegalArgumentException("positive limits required");
        this.clock = clock;
        this.scheduler = scheduler;
        this.sender = sender;
        this.intervalMs = intervalMs;
        this.maxAttempts = maxAttempts;
    }

    public synchronized void offer(String packageName, Supplier<T> snapshot) {
        if (packageName == null || snapshot == null) return;
        this.packageName = packageName;
        latest = snapshot;
        version++;
        if (!scheduled && !running) {
            failures = 0; // A new change can re-arm an exhausted delivery, never spin on its own.
            enqueue(remainingInterval());
        }
    }

    private long remainingInterval() {
        long now = clock.getAsLong();
        return lastAttempt < 0 || now < lastAttempt ? 0 : Math.max(0, intervalMs - (now - lastAttempt));
    }

    private void enqueue(long delay) {
        scheduled = true;
        try { scheduler.schedule(this::drain, delay); }
        catch (RuntimeException ignored) { scheduled = false; }
    }

    private void drain() {
        final Supplier<T> captured;
        final String pkg;
        final long sentVersion;
        synchronized (this) {
            scheduled = false;
            if (latest == null || running) return;
            running = true;
            captured = latest;
            pkg = packageName;
            sentVersion = version;
            lastAttempt = clock.getAsLong();
        }
        boolean accepted = false;
        try {
            T value = captured.get();
            if (value != null) accepted = sender.send(pkg, value);
        } catch (Throwable ignored) {
            // Telemetry must never escape into the host or terminate the worker.
        } finally {
            synchronized (this) {
                running = false;
                if (accepted) {
                    failures = 0;
                    if (version == sentVersion) latest = null;
                    else enqueue(remainingInterval());
                } else if (++failures < maxAttempts) {
                    long backoff = Math.min(60_000L, intervalMs * (1L << Math.min(failures - 1, 5)));
                    enqueue(Math.max(remainingInterval(), backoff));
                }
                // Exhaustion retains only the latest supplier; context/new events can re-arm later.
            }
        }
    }
}
