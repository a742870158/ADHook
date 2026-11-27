package com.adhook.core;

import com.adhook.config.ConfigClient;
import com.adhook.config.model.AppConfig;
import com.adhook.config.model.FeatureMeta;
import com.adhook.runtime.HostContext;
import com.adhook.runtime.HookBridge;
import com.adhook.runtime.HookCallback;
import com.adhook.runtime.NativeApi101TestFramework;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import static org.junit.Assert.*;

/** Real gate -> native adapter -> chain, with only configuration transport/telemetry replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = HookRegistrationNativeTest.Client.class)
public class HookRegistrationNativeTest {
    private static final String PKG = "com.native.gate";
    private NativeApi101TestFramework framework;
    private Method target;
    private final List<String> phases = new ArrayList<>();

    @Implements(value = ConfigClient.class, isInAndroidSdk = false)
    public static class Client {
        static final Map<String, AppConfig> apps = new HashMap<>();
        @Implementation protected static boolean isHookEnabledCached(String pkg) {
            AppConfig app = apps.get(pkg); return app != null && app.isHookEnabled();
        }
        @Implementation protected static AppConfig getAppConfigCached(String pkg) { return apps.get(pkg); }
        @Implementation protected static void scheduleRuntimeStats(String pkg, Supplier<JSONObject> snapshot) { }
    }
    public static class Target { public String echo(String text) { return text; } }

    @Before public void setUp() throws Exception {
        framework = new NativeApi101TestFramework(); framework.reset(); framework.initialize();
        target = Target.class.getDeclaredMethod("echo", String.class);
        Client.apps.clear(); phases.clear();
        clearGuard(); authorize(PKG);
    }
    @After public void tearDown() throws Exception { framework.reset(); Client.apps.clear(); clearGuard(); }
    private static void clearGuard() throws Exception {
        for (Field field : FeatureGuard.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            if (Map.class.isAssignableFrom(field.getType())) ((Map<?, ?>) field.get(null)).clear();
            if (ThreadLocal.class.isAssignableFrom(field.getType())) ((ThreadLocal<?>) field.get(null)).remove();
        }
    }
    private static void authorize(String pkg) {
        FeatureGuard.configureHost(pkg, pkg, pkg, List.of(new FeatureMeta("same-feature", "", "")));
        AppConfig app = new AppConfig(pkg); app.setHookEnabled(true); Client.apps.put(pkg, app);
    }
    private HostContext host(String pkg) { return new HostContext(pkg, pkg, getClass().getClassLoader(), null, true); }
    private HookCallback first(String owner) {
        return new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                assertEquals(owner, FeatureGuard.currentPackage()); phases.add("A-before");
                param.setObjectExtra("pair", "A");
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                assertEquals("A", param.getObjectExtra("pair")); phases.add("A-after");
            }
        };
    }
    private HookCallback second(String owner) {
        return new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                assertEquals(owner, FeatureGuard.currentPackage()); phases.add("B-before");
                param.setObjectExtra("pair", "B");
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                assertEquals("B", param.getObjectExtra("pair")); phases.add("B-after");
            }
        };
    }
    private List<HookCallback.Unhook> registerSharedLine(HostContext host) throws Throwable {
        List<HookCallback.Unhook> handles = new ArrayList<>();
        FeatureGuard.withRegistrationOwner(host.packageName, "same-feature", () ->
                HookBridge.withRegistrationScope(host, () -> {
                    // Identical member/owner/feature/priority/source line, but independent origin classes.
                    for (HookCallback callback : List.of(first(host.packageName), second(host.packageName))) {
                        HookCallback.Unhook handle = HookRegistration.hookMethod(target, callback);
                        assertSame(callback.registrationType(), handle.getCallback().registrationType());
                        handles.add(handle);
                    }
                }));
        return handles;
    }
    @Test public void independentOriginsSurviveSharedGateAndRetryWithoutDuplicateNativeHooks() throws Throwable {
        HostContext host = host(PKG);
        List<HookCallback.Unhook> initial = registerSharedLine(host);
        assertEquals(2, framework.installCount(target));
        List<HookCallback.Unhook> retried = registerSharedLine(host);
        assertSame(initial.get(0), retried.get(0)); assertSame(initial.get(1), retried.get(1));
        assertEquals(2, framework.installCount(target));
        assertEquals("host", framework.call(target, new Target(), "host"));
        assertEquals(List.of("A-before", "B-before", "B-after", "A-after"), phases);
    }
    @Test public void nativeUnhookReleasesOriginalIdentityAndAnotherOwnerRemainsIndependent() throws Throwable {
        HostContext host = host(PKG);
        List<HookCallback.Unhook> initial = registerSharedLine(host);
        initial.get(0).unhook(); assertEquals(1, framework.installCount(target));
        List<HookCallback.Unhook> restored = registerSharedLine(host);
        assertNotSame(initial.get(0), restored.get(0)); assertSame(initial.get(1), restored.get(1));
        assertEquals(2, framework.installCount(target));
        String other = "com.native.other"; authorize(other);
        registerSharedLine(host(other)); assertEquals(4, framework.installCount(target));
        assertEquals("host", framework.call(target, new Target(), "host"));
        assertEquals(8, phases.size());
        Client.apps.get(other).setHookEnabled(false); phases.clear();
        assertEquals("host", framework.call(target, new Target(), "host"));
        assertEquals(4, phases.size()); // The unrelated disabled owner cannot suppress the first owner.
    }
}
