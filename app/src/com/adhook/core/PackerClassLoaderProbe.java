package com.adhook.core;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;

import com.adhook.runtime.HookCallback;
import com.adhook.sdk.SdkHookRegistry;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import dalvik.system.BaseDexClassLoader;

/** Lifecycle/class-loader discovery stays raw; only successful installations and notifications deduplicate. */
public final class PackerClassLoaderProbe {
    public interface Callback { void onClassLoaderReady(ClassLoader classLoader) throws Throwable; }
    private record WatchTask(String markerClass, Callback callback) { }

    private static final SdkHookRegistry HOOKS = new SdkHookRegistry();
    private static final List<WatchTask> PENDING_TASKS = new CopyOnWriteArrayList<>();
    // Values never retain the weak callback key or a completed ClassLoader.
    private static final Map<Callback, Map<String, Notification>> NOTIFICATIONS = new WeakHashMap<>();
    private static boolean installingObservers;

    private static final class Notification {
        final Set<ClassLoader> completed = Collections.newSetFromMap(new WeakHashMap<>());
        final Set<ClassLoader> inFlight = new HashSet<>();
    }

    private PackerClassLoaderProbe() { }

    public static void watch(ClassLoader initialLoader, String markerClass, Callback callback) {
        if (markerClass == null || markerClass.isEmpty() || callback == null) return;
        if (checkAndNotify(initialLoader, markerClass, callback, "initialLoader")) return;
        WatchTask task = new WatchTask(markerClass, callback);
        if (!PENDING_TASKS.contains(task)) PENDING_TASKS.add(task);
        ensureGlobalHooksInstalled();
    }

    /** Retry failed members independently; a successful lifecycle hook never masks a failed sibling. */
    private static synchronized void ensureGlobalHooksInstalled() {
        if (installingObservers) return;
        installingObservers = true;
        try {
            try {
                HOOKS.hookObserverMethod(ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class),
                        new HookCallback() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                if (param.args[0] instanceof Context context) {
                                    dispatchPendingTasks(context.getClassLoader(), "attachBaseContext");
                                }
                            }
                        });
            } catch (Throwable failure) { logInstallFailure("attachBaseContext", failure); }
            try {
                HOOKS.hookObserverMethod(Application.class.getDeclaredMethod("onCreate"), new HookCallback() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof Application app) {
                            dispatchPendingTasks(app.getClassLoader(), "Application.onCreate");
                        }
                    }
                });
            } catch (Throwable failure) { logInstallFailure("Application.onCreate", failure); }
            try {
                HOOKS.hookObserverMethod(Activity.class.getDeclaredMethod("onCreate", Bundle.class), new HookCallback() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof Activity activity) {
                            dispatchPendingTasks(activity.getClassLoader(), "Activity.onCreate");
                        }
                    }
                });
            } catch (Throwable failure) { logInstallFailure("Activity.onCreate", failure); }
            try {
                for (Constructor<?> constructor : BaseDexClassLoader.class.getDeclaredConstructors()) {
                    try {
                        HOOKS.hookObserverMethod(constructor, new HookCallback() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                if (param.thisObject instanceof ClassLoader loader) {
                                    dispatchPendingTasks(loader, "BaseDexClassLoader.<init>");
                                }
                            }
                        });
                    } catch (Throwable failure) { logInstallFailure("BaseDexClassLoader.<init>", failure); }
                }
            } catch (Throwable failure) { logInstallFailure("BaseDexClassLoader constructors", failure); }
        } finally { installingObservers = false; }
    }

    private static void logInstallFailure(String member, Throwable failure) {
        AdHookLog.w("[PackerClassLoaderProbe] Observer unavailable; retry allowed: " + member + " ("
                + failure.getClass().getSimpleName() + ")");
    }

    private static void dispatchPendingTasks(ClassLoader loader, String source) {
        if (loader == null || PENDING_TASKS.isEmpty()) return;
        // Already working observers provide an opportunity to repair a failed observer.
        ensureGlobalHooksInstalled();
        for (WatchTask task : PENDING_TASKS) {
            if (checkAndNotify(loader, task.markerClass(), task.callback(), source)) PENDING_TASKS.remove(task);
        }
    }

    private static boolean checkAndNotify(ClassLoader loader, String markerClass, Callback callback, String source) {
        if (loader == null) return false;
        Notification state;
        synchronized (NOTIFICATIONS) {
            state = NOTIFICATIONS.computeIfAbsent(callback, ignored -> new HashMap<>())
                    .computeIfAbsent(markerClass, ignored -> new Notification());
        }
        synchronized (state) {
            if (state.completed.contains(loader)) return true;
            if (!state.inFlight.add(loader)) return false;
        }
        try {
            Class.forName(markerClass, false, loader);
            callback.onClassLoaderReady(loader);
            synchronized (state) { state.completed.add(loader); }
            AdHookLog.i("[PackerClassLoaderProbe] Marker " + markerClass + " delivered via " + source);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Throwable failure) {
            AdHookLog.e("[PackerClassLoaderProbe] Callback/discovery failed; retry allowed via " + source, failure);
            return false;
        } finally {
            synchronized (state) { state.inFlight.remove(loader); }
        }
    }
}
