package com.adhook.engine;

import android.view.View;
import android.widget.AbsListView;

import com.adhook.core.AdHookLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.adhook.runtime.HookCallback;

/** Stable framework/library bind boundaries, not app-specific adapter or obfuscated symbols. */
final class ReusableViewHooks {
    private static final ThreadLocal<Integer> BIND_DEPTH = new ThreadLocal<Integer>() {
        @Override protected Integer initialValue() { return 0; }
    };
    private static final Map<View, Boolean> ITEMS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final com.adhook.core.ObserverHookRegistry OBSERVERS = new com.adhook.core.ObserverHookRegistry();

    private ReusableViewHooks() { }

    static void install(ClassLoader loader, String packageName) {
        List<Throwable> failures = new ArrayList<>();
        installRecycler(loader, packageName, "androidx.recyclerview.widget.RecyclerView", failures);
        installRecycler(loader, packageName, "android.support.v7.widget.RecyclerView", failures);
        installListView(packageName, failures);
        if (!failures.isEmpty()) {
            IllegalStateException incomplete = new IllegalStateException("Reusable view observers partially unavailable");
            for (Throwable failure : failures) incomplete.addSuppressed(failure);
            throw incomplete;
        }
    }

    private static void installRecycler(ClassLoader loader, String packageName, String recyclerName,
                                        List<Throwable> failures) {
        final Class<?> adapter;
        try { adapter = Class.forName(recyclerName + "$Adapter", false, loader); }
        catch (ClassNotFoundException absent) { return; } // Entire library generation is optional.
        catch (Throwable failure) { failures.add(failure); return; }
        try {
            hookNamed(adapter, "bindViewHolder", new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    beginBinding(itemView(param.args[0]));
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    finishBinding(packageName, itemView(param.args[0]), param.hasThrowable());
                }
            }, 2, failures);
            Class<?> recycler = Class.forName(recyclerName + "$Recycler", false, loader);
            HookCallback recycled = new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    releaseRecycled(itemView(param.args[0]));
                }
            };
            hookNamed(recycler, "recycleView", recycled, 1, failures);
            // Covers cached, invalid and pooled holders too, before Adapter.onViewRecycled runs.
            hookNamed(recycler, "recycleViewHolderInternal", recycled, 1, failures);
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    private static void installListView(String packageName, List<Throwable> failures) {
        try {
            // AbsListView owns ListView/GridView's real getView transaction. Suppress intermediate
            // setText matches until the adapter has finished writing *all* fields of the new item.
            hookNamed(AbsListView.class, "obtainView", new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    beginBinding(null);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    finishBinding(packageName, itemView(param.getResult()), param.hasThrowable());
                }
            }, 2, failures);
            Class<?> bin = Class.forName("android.widget.AbsListView$RecycleBin");
            HookCallback obtainedScrap = new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    // Runs before mAdapter.getView(position, scrapView, parent), so the host's first
                    // setVisibility(VISIBLE) is no longer rewritten to GONE by an obsolete lease.
                    releaseRecycled(itemView(param.getResult()));
                }
            };
            hookNamed(bin, "getScrapView", obtainedScrap, 1, failures);
            hookNamed(bin, "getTransientStateView", obtainedScrap, 1, failures);
            HookCallback recycle = new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    releaseRecycled(itemView(param.args[0]));
                }
            };
            hookNamed(bin, "addScrapView", recycle, 2, failures);
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    private static void hookNamed(Class<?> type, String name, HookCallback hook, int parameterCount,
                                   List<Throwable> failures) {
        boolean found = false;
        for (Method method : type.getDeclaredMethods()) {
            if (!name.equals(method.getName()) || method.getParameterCount() != parameterCount) continue;
            found = true;
            try { OBSERVERS.hook(method, hook); }
            catch (Throwable failure) { failures.add(failure); }
        }
        if (!found) failures.add(new NoSuchMethodError(type.getName() + '#' + name));
    }

    static boolean isBinding() {
        return BIND_DEPTH.get() > 0;
    }

    static void beginBinding(View item) {
        BIND_DEPTH.set(BIND_DEPTH.get() + 1);
        if (item != null) {
            ITEMS.put(item, Boolean.TRUE);
            releaseRecycled(item);
        }
    }

    static void finishBinding(String packageName, View item, boolean failed) {
        int remaining = Math.max(0, BIND_DEPTH.get() - 1);
        if (remaining == 0) BIND_DEPTH.remove();
        else BIND_DEPTH.set(remaining);
        if (item == null || failed) return;
        ITEMS.put(item, Boolean.TRUE);
        if (remaining == 0 && CustomRuleEngine.canApplyObservedRules() && CustomRuleEngine.hasActiveRulesForHost(packageName)) {
            try {
                CustomRuleEngine.applyToSubtree(packageName, item);
            } catch (Throwable failure) {
                AdHookLog.w("[CustomRuleEngine] Bound item scan failed: " + failure.getMessage());
            }
        }
    }

    static void releaseRecycled(View item) {
        if (item == null) return;
        try {
            CustomRuleEngine.releaseForReuse(item);
        } catch (Throwable failure) {
            AdHookLog.w("[CustomRuleEngine] Recycled item release failed: " + failure.getMessage());
        }
    }

    /** A freshly bound item is often not attached to its RecyclerView/ListView yet. */
    static View knownItemRoot(View view) {
        for (int depth = 0; view != null && depth < 128; depth++) {
            if (ITEMS.containsKey(view)) return view;
            view = view.getParent() instanceof View parent ? parent : null;
        }
        return null;
    }

    private static View itemView(Object holderOrView) {
        if (holderOrView instanceof View view) return view;
        if (holderOrView == null) return null;
        for (Class<?> type = holderOrView.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("itemView");
                field.setAccessible(true);
                Object value = field.get(holderOrView);
                return value instanceof View view ? view : null;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }
}
