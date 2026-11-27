package com.adhook.sdk;

import com.adhook.config.ConfigClient;
import com.adhook.config.model.AppConfig;
import com.adhook.config.model.FeatureMeta;
import com.adhook.core.FeatureGuard;

import org.json.JSONObject;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookHelpers;

import static org.junit.Assert.assertNotNull;

/** Test-only SDK lookup/config seam; production callbacks, FeatureGuard and the Android objects stay real. */
public final class SdkAccountingTestSupport {
    static final String HOST = "com.adhook.sdkfixture";
    static final String FEATURE = "fixture-sdk-feature";
    static final String SECOND = "fixture-popup-feature";

    private SdkAccountingTestSupport() { }

    static void reset() throws Exception {
        HookTestShadows.Bridge.reset();
        Helpers.classes.clear();
        Client.apps.clear();
        clearStaticMaps(FeatureGuard.class);
        clearStaticMaps(SdkRuntime.class);
        for (String field : new String[]{"sCurrentPackage", "sPrimaryPackage", "sProcessName"}) {
            ReflectionHelpers.setStaticField(FeatureGuard.class, field, null);
        }
        Field scope = FeatureGuard.class.getDeclaredField("REGISTRATION_FEATURE");
        scope.setAccessible(true);
        ((ThreadLocal<?>) scope.get(null)).remove();
        FeatureGuard.configureHost(HOST, HOST, HOST, List.of(
                new FeatureMeta(FEATURE, "SDK fixture", "test"), new FeatureMeta(SECOND, "Popup fixture", "test")));
        AppConfig config = new AppConfig(HOST);
        config.setHookEnabled(true);
        Client.apps.put(HOST, config);
    }

    private static void clearStaticMaps(Class<?> type) throws Exception {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Map.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                ((Map<?, ?>) field.get(null)).clear();
            }
        }
    }

    static void clearHooks(Class<?> blocker) throws Exception {
        Field holder = blocker.getDeclaredField("HOOKS");
        holder.setAccessible(true);
        Field hooks = SdkHookRegistry.class.getDeclaredField("hooks");
        hooks.setAccessible(true);
        ((Map<?, ?>) hooks.get(holder.get(null))).clear();
    }

    static void feature(String feature, boolean enabled) { Client.apps.get(HOST).setFeatureEnabled(feature, enabled); }

    static HookCallback callback(Member method) {
        HookCallback callback = HookTestShadows.Bridge.callbacks.get(method);
        assertNotNull("Production callback missing for " + method, callback);
        return callback;
    }

    static HookCallback.MethodHookParam param(Member member, Object target, Object... args) {
        HookCallback.MethodHookParam param = ReflectionHelpers.callConstructor(HookCallback.MethodHookParam.class);
        param.method = member;
        param.thisObject = target;
        param.args = args;
        return param;
    }

    static boolean shortCircuited(HookCallback.MethodHookParam param) {
        return param.isReturnEarly();
    }

    static void invoke(HookCallback callback, String phase, HookCallback.MethodHookParam param) throws Exception {
        Method method = HookCallback.class.getDeclaredMethod(phase, HookCallback.MethodHookParam.class);
        method.setAccessible(true);
        method.invoke(callback, param);
    }

    @Implements(value = HookHelpers.class, isInAndroidSdk = false)
    public static class Helpers {
        static final Map<String, Class<?>> classes = new HashMap<>();
        @Implementation protected static Class<?> findClassIfExists(String name, ClassLoader loader) { return classes.get(name); }
    }

    @Implements(value = ConfigClient.class, isInAndroidSdk = false)
    public static class Client {
        static final Map<String, AppConfig> apps = new HashMap<>();
        @Implementation protected static boolean isHookEnabledCached(String pkg) {
            AppConfig config = apps.get(FeatureGuard.runtimePackageFor(pkg));
            return config != null && config.isHookEnabled();
        }
        @Implementation protected static AppConfig getAppConfigCached(String pkg) {
            return apps.get(FeatureGuard.runtimePackageFor(pkg));
        }
        @Implementation protected static boolean isFeatureEnabled(String pkg, String feature, boolean fallback) {
            throw new AssertionError("SDK callback must not read configuration transport");
        }
        @Implementation protected static void scheduleRuntimeStats(String pkg, Supplier<JSONObject> snapshot) {
            // No Provider/threads/network or persistent test configuration; only the real in-memory counters are examined.
        }
    }
}
