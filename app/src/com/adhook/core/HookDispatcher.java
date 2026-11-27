package com.adhook.core;

import android.content.pm.ApplicationInfo;

import com.adhook.runtime.HookBridge;
import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookHelpers;
import com.adhook.runtime.HostContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/**
 * ADHook 核心 Hook 分发调度器。
 * 承接 Native API101 生命周期回调与明确的宿主身份，
 * 避免重复分发，并安全路由至各应用模块。
 */
public final class HookDispatcher {

    private static final Set<DispatchKey> hookedKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<DispatchKey, Integer> registrationAttempts = new ConcurrentHashMap<>();
    private static final HookCallback CONTEXT_OBSERVER = new HookCallback() {
        @Override protected void afterHookedMethod(MethodHookParam param) {
            if (param.hasThrowable() || !(param.thisObject instanceof android.app.Application)) return;
            android.content.Context context = (android.content.Context) param.args[0];
            if (context != null) com.adhook.config.ConfigClient.init(context);
        }
    };

    private static final class DispatchKey {
        final String packageName;
        final String processName;
        final ClassLoader loader;
        DispatchKey(HostContext host) {
            packageName = host.packageName;
            processName = host.processName;
            loader = host.classLoader;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof DispatchKey)) return false;
            DispatchKey key = (DispatchKey) other;
            return packageName.equals(key.packageName) && processName.equals(key.processName) && loader == key.loader;
        }
        @Override public int hashCode() {
            return 31 * (31 * packageName.hashCode() + processName.hashCode()) + System.identityHashCode(loader);
        }
    }

    private HookDispatcher() {
    }

    /**
     * Native API101 dispatch using an immutable host identity.
     */
    public static synchronized void dispatch(HostContext lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }

        Integer appUid = lpparam.appInfo != null ? lpparam.appInfo.uid : null;
        if (!HookPackagePolicy.isHostApplication(lpparam.isFirstApplication, appUid, android.os.Process.myUid())) {
            return;
        }

        DispatchKey key = new DispatchKey(lpparam);
        if (hookedKeys.contains(key)) return;
        int attempt = registrationAttempts.merge(key, 1, Integer::sum);
        long failuresBefore = HookBridge.registrationFailureCount();
        // Scope determines injection; preferences gate callback actions, not registration.
        try {
            if (!HookBridge.isInitialized()) throw new IllegalStateException("API101 backend must precede dispatch");
            HookBridge.withRegistrationScope(lpparam, () ->
                    FeatureGuard.withRegistrationOwner(lpparam.packageName, null, () -> {
                        com.adhook.config.ConfigClient.initializeHookRuntime(lpparam.packageName);
                        performInjection(lpparam);
                    }));
            if (HookBridge.registrationFailureCount() != failuresBefore) {
                throw new IllegalStateException("Native hook registration partially failed");
            }
            hookedKeys.add(key); // Never suppress a failed native registration permanently.
            registrationAttempts.remove(key);
        } catch (Throwable failure) {
            AdHookLog.e("HookDispatcher: registration failed for " + lpparam.packageName, failure);
            if (attempt < 3 && android.os.Looper.getMainLooper() != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> dispatch(lpparam), 500L);
            }
        }
    }

    /** Register listeners and hooks before Application initialization, regardless of preferences. */
    private static void performInjection(HostContext lpparam) throws Throwable {
        FeatureGuard.setCurrentPackage(lpparam.packageName);
        // Both generic and specialized hosts need Context for authorized background calibration.
        HookHelpers.findAndHookMethod(android.app.Application.class, "attachBaseContext",
                android.content.Context.class, CONTEXT_OBSERVER);
        android.app.Application application = HostContext.currentApplication();
        if (application != null) com.adhook.config.ConfigClient.init(application);
        // 先观察生命周期，避免后续 SDK/指纹探测错过首页首轮 onResume。
        ActivityUiCoordinator.install(lpparam.packageName);

        // Installed deep hooks are plugins; the base ships no built-in host adapter.
        com.adhook.plugin.PluginRuntimeTable.Route pluginRoute =
                com.adhook.core.PluginBootstrap.routeFor(lpparam.packageName, lpparam.processName);
        if (pluginRoute != null) {
            runPlugin(pluginRoute, lpparam);
        } else {
            // 未被任何深度适配命中、但已被 LSPosed 注入时，自动启动通用托管 Hook。
            runGenericHook(lpparam);
        }
    }

    /**
     * Plugin routes own the host adapter. Base generic components are installed first so an
     * unavailable/failed plugin never removes the picker, local rules or universal SDK blocking.
     */
    private static void runPlugin(com.adhook.plugin.PluginRuntimeTable.Route route,
                                  HostContext lpparam) throws Throwable {
        String pkg = lpparam.packageName;
        FeatureGuard.configureHost(pkg, PluginBootstrap.primaryOf(route, pkg), lpparam.processName,
                PluginBootstrap.featureMetas(route));
        AdHookLog.i("Routing " + pkg + " (" + lpparam.processName + ") to plugin "
                + route.pluginId + " v" + route.versionCode);
        List<Throwable> failures = new ArrayList<>();
        attemptComponent(failures, "SDK", () -> com.adhook.sdk.UniversalSdkBlocker.install(lpparam.classLoader));
        attemptComponent(failures, "custom rules", () -> com.adhook.engine.CustomRuleEngine.install(
                lpparam.classLoader, pkg));
        attemptComponent(failures, "inspector", () -> com.adhook.inspector.VisualInspector.install(
                lpparam.classLoader, pkg));
        PluginBootstrap.LoadResult result;
        try {
            result = PluginBootstrap.load(route, lpparam, lpparam.processName);
        } catch (Throwable failure) {
            AdHookLog.e("[HookDispatcher] plugin load failed for " + pkg, failure);
            result = PluginBootstrap.LoadResult.FAILED_BEFORE_ENTRY;
        }
        if (result != PluginBootstrap.LoadResult.LOADED) {
            AdHookLog.w("[HookDispatcher] plugin unavailable for " + pkg + "; generic layer remains active");
        }
        throwIfIncomplete(pkg, failures);
    }

    private static void runGenericHook(HostContext lpparam) throws Throwable {
        String pkg = lpparam.packageName;
        FeatureGuard.configureHost(pkg, pkg, lpparam.processName, Collections.emptyList());
        if ("android".equals(pkg) || "com.android.systemui".equals(pkg)) {
            return;
        }
        AdHookLog.i("Routing " + pkg + " (" + lpparam.processName + ") to GenericUniversalHook");
        List<Throwable> failures = new ArrayList<>();
        attemptComponent(failures, "SDK", () -> com.adhook.sdk.UniversalSdkBlocker.install(lpparam.classLoader));
        attemptComponent(failures, "custom rules", () -> com.adhook.engine.CustomRuleEngine.install(lpparam.classLoader, pkg));
        attemptComponent(failures, "inspector", () -> com.adhook.inspector.VisualInspector.install(lpparam.classLoader, pkg));
        throwIfIncomplete(pkg, failures);
    }

    private static void attemptComponent(List<Throwable> failures, String component, FeatureGuard.Action action) {
        try { action.run(); }
        catch (Throwable failure) {
            failures.add(failure);
            AdHookLog.e("[HookDispatcher] " + component + " registration incomplete", failure);
        }
    }

    private static void throwIfIncomplete(String packageName, List<Throwable> failures) {
        if (failures.isEmpty()) return;
        IllegalStateException incomplete = new IllegalStateException("Hook components partially unavailable for " + packageName);
        for (Throwable failure : failures) incomplete.addSuppressed(failure);
        throw incomplete;
    }

    /**
     * Modern (LibXposed API 101) 模式分发入口。
     */
    public static void dispatch(String packageName, String processName, ClassLoader classLoader,
                                ApplicationInfo appInfo, boolean isFirstApplication) {
        if (packageName == null) {
            return;
        }

        dispatch(new HostContext(packageName, processName, classLoader, appInfo, isFirstApplication));
    }
}
