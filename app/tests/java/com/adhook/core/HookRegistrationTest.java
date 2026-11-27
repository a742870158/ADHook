package com.adhook.core;

import com.adhook.config.ConfigClient;
import com.adhook.config.model.AppConfig;
import com.adhook.config.model.FeatureMeta;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookBridge;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = {
        HookRegistrationTest.Client.class, HookRegistrationTest.Bridge.class
})
public class HookRegistrationTest {
    private static final String PKG = "com.guard.wrapper";

    @Implements(value = ConfigClient.class, isInAndroidSdk = false)
    public static class Client {
        static AppConfig app;
        static AppConfig other;
        static String lastStatsPackage;
        private static AppConfig configFor(String pkg) {
            if (PKG.equals(pkg)) return app;
            return other != null && other.getPackageName().equals(pkg) ? other : null;
        }
        @Implementation protected static boolean isHookEnabledCached(String pkg) {
            AppConfig config = configFor(pkg);
            return config != null && config.isHookEnabled();
        }
        @Implementation protected static AppConfig getAppConfigCached(String pkg) { return configFor(pkg); }
        @Implementation protected static void scheduleRuntimeStats(String pkg, Supplier<JSONObject> source) {
            lastStatsPackage = pkg;
        }
        @Implementation protected static boolean isFeatureEnabled(String pkg, String id, boolean fallback) {
            throw new AssertionError("No config transport in callback");
        }
    }

    @Implements(value = HookBridge.class, isInAndroidSdk = false)
    public static class Bridge {
        static HookCallback captured;
        @Implementation protected static HookCallback.Unhook hookMethod(Member member, HookCallback callback) {
            captured = callback;
            return null;
        }
        @Implementation protected static void log(String text) { }
    }

    @Before public void setUp() throws Exception {
        clearRuntime();
        FeatureGuard.configureHost(PKG, PKG, PKG, List.of(new FeatureMeta("feature", "", "")));
        Client.app = new AppConfig(PKG);
        Client.app.setHookEnabled(true);
    }

    @After public void tearDown() throws Exception { clearRuntime(); }

    private static void clearRuntime() throws Exception {
        for (Field field : FeatureGuard.class.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) ((Map<?, ?>) field.get(null)).clear();
            }
        }
        for (String name : new String[]{"sCurrentPackage", "sPrimaryPackage", "sProcessName"}) {
            ReflectionHelpers.setStaticField(FeatureGuard.class, name, null);
        }
        for (String name : new String[]{"REGISTRATION_FEATURE", "REGISTRATION_PACKAGE"}) {
            Field scope = FeatureGuard.class.getDeclaredField(name);
            scope.setAccessible(true);
            ((ThreadLocal<?>) scope.get(null)).remove();
        }
        Client.app = null;
        Client.other = null;
        Client.lastStatsPackage = null;
        Bridge.captured = null;
    }

    @Test public void disabledAndUnknownStillRegisterThenGateBothPhasesWithoutReinstall() throws Exception {
        int[] calls = {0};
        Client.app.setHookEnabled(false);
        new FeatureGuard(PKG).run("feature", () -> HookRegistration.hookMethod(target(), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { calls[0]++; }
            @Override protected void afterHookedMethod(MethodHookParam param) { calls[0]++; }
        }));
        HookCallback installed = Bridge.captured;
        assertNotNull(installed);
        invoke(installed, "beforeHookedMethod", param());
        invoke(installed, "afterHookedMethod", param());
        assertEquals(0, calls[0]);
        Client.app.setHookEnabled(true);
        Client.app.setFeatureEnabled("feature", false);
        invoke(installed, "beforeHookedMethod", param());
        assertEquals(0, calls[0]);
        Client.app.setFeatureEnabled("feature", true);
        invoke(installed, "beforeHookedMethod", param());
        invoke(installed, "afterHookedMethod", param());
        assertEquals(2, calls[0]);
        Client.app = null;
        invoke(installed, "beforeHookedMethod", param());
        assertEquals(2, calls[0]);
    }

    @Test public void preservesPriorityParamResultAndDeferredRegistrationOwner() throws Exception {
        HookCallback original = new HookCallback(123) {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                assertEquals("feature", FeatureGuard.currentRegistrationFeature());
                HookRegistration.hookMethod(param.method, new HookCallback() {
                    @Override protected void beforeHookedMethod(MethodHookParam nested) { nested.setResult(false); }
                });
                param.setResult(false);
            }
        };
        new FeatureGuard(PKG).run("feature", () -> HookRegistration.hookMethod(target(), original));
        HookCallback installed = Bridge.captured;
        assertEquals(original.priority, installed.priority);
        HookCallback.MethodHookParam param = param();
        invoke(installed, "beforeHookedMethod", param);
        assertEquals(Boolean.FALSE, param.getResult());
        assertNull(FeatureGuard.currentRegistrationFeature());
        HookCallback nested = Bridge.captured;
        Client.app.setFeatureEnabled("feature", false);
        HookCallback.MethodHookParam off = param();
        invoke(nested, "beforeHookedMethod", off);
        assertNull(off.getResult());
    }

    @Test public void crossPackageCallbacksAndDeferredGuardKeepTheirOwnPackageAndAliases() throws Throwable {
        String otherPackage = "com.guard.other.variant";
        String otherPrimary = "com.guard.other";
        FeatureGuard.configureHost(otherPackage, otherPrimary, PKG, List.of(new FeatureMeta("other-feature", "", "")));
        FeatureGuard.registerAlias(PKG, "feature", "old-feature");
        FeatureGuard.registerAlias(otherPackage, "other-feature", "old-feature");
        Client.other = new AppConfig(otherPackage);
        Client.other.setHookEnabled(true);
        FeatureGuard[] deferredGuard = new FeatureGuard[1];
        FeatureGuard.withRegistrationOwner(otherPackage, null, () -> {
            deferredGuard[0] = new FeatureGuard(otherPrimary);
            assertTrue(deferredGuard[0].run("old-feature", () -> HookRegistration.hookMethod(target(), new HookCallback() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    assertEquals(otherPackage, FeatureGuard.currentPackage());
                    assertEquals(otherPackage, FeatureGuard.runtimePackageFor(otherPrimary));
                    assertTrue(FeatureGuard.isEnabled(otherPrimary, "old-feature"));
                    FeatureGuard.hit("old-feature");
                    HookRegistration.hookMethod(param.method, new HookCallback() {
                        @Override protected void beforeHookedMethod(MethodHookParam nested) {
                            assertEquals(otherPackage, FeatureGuard.currentPackage());
                            nested.setResult(false);
                        }
                    });
                }
            })));
        });
        HookCallback installed = Bridge.captured;
        assertEquals(PKG, FeatureGuard.currentPackage());
        Client.app.setHookEnabled(false); // An unrelated active context must not suppress the other package.
        FeatureGuard.withRegistrationOwner(PKG, "feature", () -> {
            invoke(installed, "beforeHookedMethod", param());
            assertEquals(PKG, FeatureGuard.currentPackage());
            assertEquals("feature", FeatureGuard.currentRegistrationFeature());
            assertTrue(deferredGuard[0].run("old-feature", () -> {
                assertEquals(otherPackage, FeatureGuard.currentPackage());
                assertEquals("other-feature", FeatureGuard.currentRegistrationFeature());
            }));
            assertEquals(PKG, FeatureGuard.currentPackage());
        });
        assertEquals(otherPackage, Client.lastStatsPackage);
        assertEquals(1, FeatureGuard.getHitCount("other-feature"));
        assertEquals(0, FeatureGuard.getHitCount("feature"));
        HookCallback nested = Bridge.captured;
        Client.other.setHookEnabled(false);
        Client.app.setHookEnabled(true);
        HookCallback.MethodHookParam off = param();
        invoke(nested, "beforeHookedMethod", off);
        assertNull(off.getResult());
        assertEquals(PKG, FeatureGuard.currentPackage());
        assertNull(FeatureGuard.currentRegistrationFeature());
    }

    @Test public void independentCallbackStillNeedsHookSwitchAndExceptionIsNotWrapped() throws Exception {
        IllegalStateException expected = new IllegalStateException("original");
        HookRegistration.hookMethod(target(), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { throw expected; }
        });
        Client.app.setHookEnabled(false);
        invoke(Bridge.captured, "beforeHookedMethod", param());
        Client.app.setHookEnabled(true);
        try {
            invoke(Bridge.captured, "beforeHookedMethod", param());
            fail("Expected original failure");
        } catch (InvocationTargetException failure) {
            assertSame(expected, failure.getCause());
        }
        assertNull(FeatureGuard.currentRegistrationFeature());
    }

    private static Method target() throws NoSuchMethodException { return Target.class.getMethod("load"); }
    private static HookCallback.MethodHookParam param() throws NoSuchMethodException {
        HookCallback.MethodHookParam value = ReflectionHelpers.callConstructor(HookCallback.MethodHookParam.class);
        value.method = target();
        value.args = new Object[0];
        return value;
    }
    private static void invoke(HookCallback callback, String phase, HookCallback.MethodHookParam param) throws Exception {
        Method method = HookCallback.class.getDeclaredMethod(phase, HookCallback.MethodHookParam.class);
        method.setAccessible(true);
        method.invoke(callback, param);
    }
    public static class Target { public boolean load() { return true; } }
}
