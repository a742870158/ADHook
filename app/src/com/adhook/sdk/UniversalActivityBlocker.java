package com.adhook.sdk;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;

import com.adhook.core.FeatureGuard;
import com.adhook.core.AdHookLog;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import com.adhook.runtime.HookCallback;

/**
 * Blocks the existing SDK/host ad routes at execStartActivity, without short-circuiting onCreate.
 * SDK reward/award activities retain the existing exemption because this path cannot deliver SDK rewards.
 */
public final class UniversalActivityBlocker {
    private static final SdkHookRegistry HOOKS = new SdkHookRegistry();
    private static final Set<String> extraBlockedActivities = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String, Map<String, HostRoute>> FEATURE_ROUTES = new ConcurrentHashMap<>();
    private static final String[] REWARD_ACTIVITY_KEYWORDS = { "reward", "激励", "award" };

    private UniversalActivityBlocker() { }

    /** Compatibility for independently-installed host routes. Prefer the feature-aware overload. */
    public static void addBlockedActivity(String activityClassName) {
        if (activityClassName != null && !activityClassName.isEmpty()) extraBlockedActivities.add(activityClassName);
    }

    /** A host route remains controlled by its feature even when another feature installed the SDK layer. */
    public static void addBlockedActivity(String packageName, String featureId, String activityClassName) {
        if (packageName == null || featureId == null || activityClassName == null || activityClassName.isEmpty()) return;
        String pkg = FeatureGuard.runtimePackageFor(packageName);
        String feature = FeatureGuard.canonicalFeatureId(pkg, featureId);
        FEATURE_ROUTES.computeIfAbsent(activityClassName, ignored -> new ConcurrentHashMap<>())
                .put(pkg + ":" + feature, new HostRoute(pkg, feature));
        FeatureGuard.track(pkg, feature);
    }

    public static void install() {
        SdkRuntime.bind("activity");
        try {
            int count = 0;
            for (Method method : Instrumentation.class.getDeclaredMethods()) {
                if (!"execStartActivity".equals(method.getName())) continue;
                final int intentIndex = findIntentIndex(method.getParameterTypes());
                if (intentIndex < 0) continue;
                try {
                    if (HOOKS.hookObserverMethod(method, new HookCallback() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length <= intentIndex
                                    || !(param.args[intentIndex] instanceof Intent intent)) return;
                            SdkRuntime.Decision action = blockDecision(intent);
                            if (action == null) return;
                            AdCallbackHelper.setNullLikeResult(param);
                            action.hit();
                            ComponentName cmp = intent.getComponent();
                            AdHookLog.i("[UniversalActivityBlocker] Blocked ad activity launch: "
                                    + (cmp != null ? cmp.getClassName() : intent.getAction()));
                        }
                    })) count++;
                } catch (Throwable t) {
                    AdHookLog.e("[UniversalActivityBlocker] execStartActivity overload failed", t);
                }
            }
            if (count > 0) AdHookLog.i("[UniversalActivityBlocker] Hooked " + count + " execStartActivity overload(s)");
        } catch (Throwable t) {
            AdHookLog.e("[UniversalActivityBlocker] Failed to hook Instrumentation.execStartActivity", t);
        }
    }

    private static int findIntentIndex(Class<?>[] parameterTypes) {
        for (int i = 0; i < parameterTypes.length; i++) {
            if (Intent.class.isAssignableFrom(parameterTypes[i])) return i;
        }
        return -1;
    }

    static SdkRuntime.Decision blockDecision(Intent intent) {
        ComponentName cmp = intent == null ? null : intent.getComponent();
        if (cmp == null) return null;
        String className = cmp.getClassName();
        if (className == null) return null;
        Map<String, HostRoute> hostRoutes = FEATURE_ROUTES.get(className);
        if (hostRoutes != null) {
            // Stable selection and one action ticket prevent double counting a multiply-owned route.
            for (HostRoute route : new TreeMap<>(hostRoutes).values()) {
                SdkRuntime.Decision action = SdkRuntime.featureDecision(route.pkg, route.feature);
                if (action != null) return action;
            }
        }
        if (extraBlockedActivities.contains(className)) return SdkRuntime.decision("activity");
        // Keep the original order: an explicit host route is authoritative; ordinary SDK rewards pass.
        for (String keyword : REWARD_ACTIVITY_KEYWORDS) {
            if (className.toLowerCase(java.util.Locale.ROOT).contains(keyword)) return null;
        }
        return AdSdk.isAdClass(className) ? SdkRuntime.decision("activity") : null;
    }

    private static final class HostRoute {
        final String pkg;
        final String feature;
        HostRoute(String pkg, String feature) { this.pkg = pkg; this.feature = feature; }
    }
}
