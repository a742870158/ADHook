package com.adhook.core;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookHelpers;

/** Observes lifecycle early; only gated UI catch-up runs, never host lifecycle/initialization replay. */
public final class ActivityUiCoordinator {
    private static volatile Registry registry;
    private static final java.util.Set<String> installedMethods = new java.util.HashSet<>();
    private static boolean snapshotScheduled;
    private static int installationAttempts;

    private ActivityUiCoordinator() {}

    /** Observe before configuration is available; preferences never stop lifecycle discovery. */
    public static synchronized void install(String packageName) {
        if (registry == null) registry = new Registry(packageName);
        Registry target = registry;
        if (installationAttempts >= 3) return;
        installationAttempts++;
        for (String method : new String[]{"onResume", "onPause", "onDestroy"}) {
            if (installedMethods.contains(method)) continue;
            try {
                HookHelpers.findAndHookMethod(Activity.class, method, new HookCallback() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Activity activity = (Activity) param.thisObject;
                            if ("onResume".equals(method)) {
                                if (!param.hasThrowable()) target.resumed(activity);
                            } else {
                                target.inactive(activity);
                            }
                        } catch (Throwable error) {
                            AdHookLog.e("[ActivityUiCoordinator] lifecycle observation failed", error);
                        }
                    }
                });
                installedMethods.add(method);
            } catch (Throwable error) {
                AdHookLog.e("[ActivityUiCoordinator] cannot observe " + method, error);
            }
        }
        if (installedMethods.size() == 3) {
            // Do not run tasks until pause/destroy cancellation is observed as well.
            if (!snapshotScheduled) {
                snapshotScheduled = true;
                target.main.post(() -> {
                    target.activate();
                    captureAlreadyResumed(target);
                });
            }
        } else if (installationAttempts < 3) {
            // Retry only failed lifecycle methods, never replay SDK/host registration.
            target.main.postDelayed(() -> install(packageName), 500L);
        } else {
            AdHookLog.w("[ActivityUiCoordinator] incomplete lifecycle observation; UI tasks disabled");
        }
    }

    public static void register(String owner, Class<?> activityClass, BiConsumer<Activity, View> action) {
        Registry target = registry;
        if (target != null) target.register(owner, activityClass, action);
    }

    private static void captureAlreadyResumed(Registry target) {
        try {
            Class<?> threadClass = HookHelpers.findClass("android.app.ActivityThread", null);
            Object thread = HookHelpers.callStaticMethod(threadClass, "currentActivityThread");
            Object records = HookHelpers.getObjectField(thread, "mActivities");
            if (!(records instanceof Map)) return;
            int checked = 0;
            for (Object record : ((Map<?, ?>) records).values()) {
                if (++checked > 32) break;
                try {
                    if (HookHelpers.getBooleanField(record, "paused")) continue;
                    Object activity = HookHelpers.getObjectField(record, "activity");
                    if (activity instanceof Activity) target.resumed((Activity) activity);
                } catch (Throwable ignored) {
                    // Unknown framework record layout: wait for the next normal lifecycle event.
                }
            }
        } catch (Throwable ignored) {
            // Reflection is a one-shot optimization, not a prerequisite for normal observation.
        }
    }

    /** Main-thread state; weak keys and queued weak references never retain an Activity. */
    static final class Registry {
        private static final long[] DELAYS_MS = {0L, 500L, 1200L, 2500L};
        private final String packageName;
        final Handler main = new Handler(Looper.getMainLooper());
        private final Map<Activity, Long> resumed = new WeakHashMap<>();
        private final Map<String, Task> tasks = new LinkedHashMap<>();
        private long epoch;
        private boolean ready;

        Registry(String packageName) { this.packageName = packageName; }

        void activate() { ready = true; }

        void register(String owner, Class<?> activityClass, BiConsumer<Activity, View> action) {
            if (owner == null || activityClass == null || action == null) return;
            Task task = new Task(activityClass, action, FeatureGuard.currentRegistrationFeature());
            main.post(() -> {
                if (tasks.containsKey(owner) || tasks.size() >= 64) return;
                tasks.put(owner, task);
                for (Map.Entry<Activity, Long> entry : new ArrayList<>(resumed.entrySet())) {
                    schedule(owner, task, entry.getKey(), entry.getValue());
                }
            });
        }

        void resumed(Activity activity) {
            if (!ready || !packageName.equals(activity.getPackageName()) || activity.isFinishing()
                    || activity.isDestroyed() || resumed.containsKey(activity)) return;
            long generation = ++epoch;
            resumed.put(activity, generation);
            for (Map.Entry<String, Task> entry : tasks.entrySet()) {
                schedule(entry.getKey(), entry.getValue(), activity, generation);
            }
        }

        void inactive(Activity activity) { resumed.remove(activity); }

        private void schedule(String owner, Task task, Activity activity, long generation) {
            if (activity == null || !task.activityClass.isInstance(activity)) return;
            WeakReference<Activity> reference = new WeakReference<>(activity);
            for (long delay : DELAYS_MS) {
                main.postDelayed(() -> {
                    Activity current = reference.get();
                    if (current == null || current.isFinishing() || current.isDestroyed()
                            || !Long.valueOf(generation).equals(resumed.get(current))) return;
                    if (!com.adhook.config.ConfigClient.isHookEnabledCached(packageName)
                            || (task.feature != null && !FeatureGuard.isEnabled(packageName, task.feature))) return;
                    if (current.getWindow() == null) return;
                    View decor = current.getWindow().getDecorView();
                    if (!decor.isAttachedToWindow()) return;
                    try { task.action.accept(current, decor); }
                    catch (Throwable error) {
                        AdHookLog.e("[ActivityUiCoordinator] UI task failed: " + owner, error);
                    }
                }, delay);
            }
        }
    }

    private static final class Task {
        final Class<?> activityClass;
        final BiConsumer<Activity, View> action;
        final String feature;
        Task(Class<?> activityClass, BiConsumer<Activity, View> action, String feature) {
            this.activityClass = activityClass;
            this.action = action;
            this.feature = feature;
        }
    }
}
