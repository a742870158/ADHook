package com.adhook.engine;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import com.adhook.core.AdHookLog;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;

/** Coalesced main-thread slices; queued values cannot strongly retain their weak roots or Activities. */
final class RuleTreeTraversal {
    static final int MAX_FRAME_WORK = 64, MAX_NODES = 4096, MAX_DEPTH = 64;
    private static final Map<View, Work> pending = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Long> reuseVersions = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Activity, Boolean> inactive = Collections.synchronizedMap(new WeakHashMap<>());
    static LongSupplier clock = System::nanoTime;

    private RuleTreeTraversal() { }

    static void start(String pkg, View root, long generation) {
        Looper looper = Looper.getMainLooper();
        if (looper == null || root == null) return;
        Work work;
        synchronized (pending) {
            Work previous = pending.get(root);
            if (previous != null && previous.pkg.equals(pkg) && previous.generation == generation) return;
            if (previous != null) previous.cancel();
            if (pending.size() >= 32) { CustomRuleEngine.reportBudget(pkg, "Pending root"); return; }
            work = new Work(pkg, root, generation, new Handler(looper));
            pending.put(root, work);
        }
        if (Looper.myLooper() == looper) work.run();
        else if (!work.main.post(work)) work.cancel();
    }

    static int pendingCount() { return pending.size(); }

    static void cancelAll() {
        ArrayList<Work> works;
        synchronized (pending) { works = new ArrayList<>(pending.values()); pending.clear(); }
        for (Work work : works) work.cancel();
    }

    static void changed(View view) {
        if (view == null) return;
        synchronized (reuseVersions) { reuseVersions.put(view, version(view) + 1L); }
        Work work = pending.remove(view);
        if (work != null) work.cancel();
    }

    static void cancelForActivity(Activity activity) {
        if (activity == null) return;
        inactive.put(activity, Boolean.TRUE);
        ArrayList<Work> works;
        synchronized (pending) { works = new ArrayList<>(pending.values()); }
        for (Work work : works) {
            View root = work.root.get();
            if (root != null && CustomRuleEngine.findActivity(root.getContext()) == activity) work.cancel();
        }
    }

    static void resumed(Activity activity) { inactive.remove(activity); }

    private static long version(View view) { return reuseVersions.getOrDefault(view, 0L); }

    private static final class Cursor {
        final WeakReference<View> view;
        final Cursor parent;
        final long version;
        final int depth;
        boolean matched;
        int nextChild;
        Cursor(View view, Cursor parent) {
            this.view = new WeakReference<>(view); this.parent = parent;
            version = version(view); depth = parent == null ? 0 : parent.depth + 1;
        }
        boolean current(View value) {
            if (value == null || version(value) != version) return false;
            if (parent == null) return true;
            View expected = parent.view.get();
            return expected != null && value.getParent() == expected && parent.current(expected);
        }
    }

    private static final class Work implements Runnable {
        final String pkg;
        final WeakReference<View> root;
        final long generation;
        final Handler main;
        final ArrayDeque<Cursor> stack = new ArrayDeque<>();
        boolean started, requireAttached;
        volatile boolean cancelled;
        int nodes;
        Work(String pkg, View root, long generation, Handler main) {
            this.pkg = pkg; this.root = new WeakReference<>(root); this.generation = generation; this.main = main;
            stack.addLast(new Cursor(root, null));
        }
        void cancel() {
            cancelled = true; main.removeCallbacks(this);
            View current = root.get();
            boolean onMain = Looper.myLooper() == main.getLooper();
            if (current != null) {
                if (onMain) current.removeCallbacks(this);
                synchronized (pending) { if (pending.get(current) == this) pending.remove(current); }
            }
            if (onMain) stack.clear();
        }
        @Override public void run() {
            try { runSlice(); }
            catch (Throwable failure) {
                cancel();
                try { AdHookLog.w("[CustomRuleEngine] Traversal cancelled: " + failure.getClass().getSimpleName()); }
                catch (Throwable ignored) { }
            }
        }
        private void runSlice() {
            View current = root.get();
            if (cancelled || current == null || pending.get(current) != this
                    || !CustomRuleEngine.isTraversalCurrent(pkg, generation)) { cancel(); return; }
            if (!started) { started = true; requireAttached = current.isAttachedToWindow(); }
            Activity activity = CustomRuleEngine.findActivity(current.getContext());
            // Binding-before-first-draw legitimately precedes window visibility. Pause/destroy
            // observation, not a premature window-visible assumption, cancels background work.
            if ((requireAttached && !current.isAttachedToWindow()) || (activity != null
                    && (inactive.containsKey(activity) || activity.isFinishing() || activity.isDestroyed()))) { cancel(); return; }
            long began = clock.getAsLong();
            int work = 0;
            while (!stack.isEmpty() && work < MAX_FRAME_WORK) {
                if (work >= 8 && clock.getAsLong() - began >= 4_000_000L) break;
                if (cancelled || !CustomRuleEngine.isTraversalCurrent(pkg, generation)) { cancel(); return; }
                work++;
                Cursor cursor = stack.peekLast();
                View view = cursor.view.get();
                if (!cursor.current(view)) { stack.removeLast(); continue; }
                if (!cursor.matched) {
                    if (++nodes > MAX_NODES) { CustomRuleEngine.reportBudget(pkg, "Tree node"); cancel(); return; }
                    cursor.matched = true;
                    CustomRuleEngine.matchAndApply(pkg, view);
                } else if (view instanceof ViewGroup group && cursor.nextChild < group.getChildCount()) {
                    View child = group.getChildAt(cursor.nextChild++);
                    if (child != null) {
                        if (cursor.depth < MAX_DEPTH) stack.addLast(new Cursor(child, cursor));
                        else CustomRuleEngine.reportBudget(pkg, "Tree depth");
                    }
                } else stack.removeLast();
            }
            if (stack.isEmpty()) { cancel(); return; }
            if (current.isAttachedToWindow()) current.postOnAnimation(this);
            else if (!main.postDelayed(this, 16L)) cancel();
        }
    }
}
