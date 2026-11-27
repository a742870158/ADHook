package com.adhook.core;

import android.view.View;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Per-View/key layout work, coalesced to one callback per animation frame and released on detach. */
public final class ViewListenerRegistry {
    @FunctionalInterface
    public interface LayoutAction {
        void run(View view);
    }

    // Both sides are weak: even a caller accidentally capturing its View cannot keep an Activity alive.
    private static final Map<View, Map<String, WeakReference<Registration>>> REGISTRATIONS = new WeakHashMap<>();

    private ViewListenerRegistry() {}

    /** Call on the View/UI thread. Repeated resumes request one scan, not another listener. */
    public static synchronized boolean register(View view, String key, LayoutAction action) {
        if (view == null || key == null || key.isEmpty() || action == null) return false;
        Map<String, WeakReference<Registration>> entries = REGISTRATIONS.computeIfAbsent(view, ignored -> new HashMap<>());
        WeakReference<Registration> existing = entries.get(key);
        Registration registration = existing == null ? null : existing.get();
        if (registration != null) {
            registration.request();
            return false;
        }
        registration = new Registration(view, key, action);
        entries.put(key, new WeakReference<>(registration));
        view.addOnLayoutChangeListener(registration);
        view.addOnAttachStateChangeListener(registration);
        registration.request();
        return true;
    }

    /** Call on the View/UI thread. Also cancels a scan already posted for the next frame. */
    public static synchronized void unregister(View view, String key) {
        Map<String, WeakReference<Registration>> entries = REGISTRATIONS.get(view);
        if (entries == null) return;
        WeakReference<Registration> reference = entries.remove(key);
        Registration registration = reference == null ? null : reference.get();
        if (registration != null) registration.dispose(view);
        if (entries.isEmpty()) REGISTRATIONS.remove(view);
    }

    private static final class Registration implements View.OnLayoutChangeListener,
            View.OnAttachStateChangeListener, Runnable {
        private final WeakReference<View> target;
        private final String key;
        private LayoutAction action;
        private boolean queued;
        private boolean running;

        Registration(View view, String key, LayoutAction action) {
            this.target = new WeakReference<>(view);
            this.key = key;
            this.action = action;
        }

        void request() {
            View view = target.get();
            if (action == null || view == null || queued || running || !view.isAttachedToWindow()) return;
            queued = true;
            view.postOnAnimation(this);
        }

        @Override public void run() {
            queued = false;
            View view = target.get();
            if (action == null || view == null || !view.isAttachedToWindow() || running) return;
            running = true;
            try {
                action.run(view);
            } catch (Throwable failure) {
                AdHookLog.w("[ViewListenerRegistry] Layout action failed: " + key);
            } finally {
                running = false;
            }
        }

        @Override public void onLayoutChange(View view, int l, int t, int r, int b,
                                              int oldL, int oldT, int oldR, int oldB) {
            request();
        }

        @Override public void onViewAttachedToWindow(View view) { request(); }
        @Override public void onViewDetachedFromWindow(View view) { unregister(view, key); }

        void dispose(View view) {
            action = null;
            queued = false;
            view.removeCallbacks(this);
            view.removeOnLayoutChangeListener(this);
            view.removeOnAttachStateChangeListener(this);
            target.clear();
        }
    }
}
