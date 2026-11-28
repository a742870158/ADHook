package com.adhook.config;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;

import com.adhook.config.model.AppConfig;
import com.adhook.config.model.CustomRule;
import com.adhook.config.model.GlobalConfig;
import com.adhook.core.FeatureGuard;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.*;

/** Runs real init, snapshot publication and refresh; only source reads and scheduling are recorded. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = HookRuntimeContextInitTest.Sources.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class HookRuntimeContextInitTest {
    private static final String PKG = "com.context.host";
    private final Map<String, Object> previousFields = new LinkedHashMap<>();
    private final List<Runnable> immediate = new ArrayList<>();
    private final List<Runnable> periodic = new ArrayList<>();
    private Set<String> packages, fallbackPackages, previousPackages, previousFallbacks;
    private Map<String, AppConfig> cache, previousCache;
    private Map<String, Long> timestamps, previousTimestamps;
    private Object previousPackage;
    private Context context;

    @Implements(value = ConfigClient.class, isInAndroidSdk = false)
    public static class Sources {
        static String launch;
        static JSONObject full;
        @Implementation protected static String readTrustedLaunchJson() { return launch; }
        @Implementation protected static JSONObject readSnapshot() { return full; }
    }

    @SuppressWarnings("unchecked")
    @Before public void setUp() {
        for (String name : new String[]{"sContext", "sHookRuntime", "sAsyncExecutor", "sRuntimeRefreshStarted"}) {
            previousFields.put(name, ReflectionHelpers.getStaticField(ConfigClient.class, name));
        }
        previousPackage = ReflectionHelpers.getStaticField(FeatureGuard.class, "sCurrentPackage");
        ReflectionHelpers.setStaticField(FeatureGuard.class, "sCurrentPackage", null);
        packages = ReflectionHelpers.getStaticField(ConfigClient.class, "sRuntimePackages");
        fallbackPackages = ReflectionHelpers.getStaticField(ConfigClient.class, "sFallbackPackages");
        cache = ReflectionHelpers.getStaticField(ConfigClient.class, "sAppConfigCache");
        timestamps = ReflectionHelpers.getStaticField(ConfigClient.class, "sCacheTimestamps");
        previousPackages = new HashSet<>(packages);
        previousFallbacks = new HashSet<>(fallbackPackages);
        previousCache = new LinkedHashMap<>(cache);
        previousTimestamps = new LinkedHashMap<>(timestamps);
        packages.clear(); fallbackPackages.clear(); cache.clear(); timestamps.clear();
        ReflectionHelpers.setStaticField(ConfigClient.class, "sContext", null);
        ReflectionHelpers.setStaticField(ConfigClient.class, "sHookRuntime", HookRuntimeSnapshot.empty());
        ReflectionHelpers.setStaticField(ConfigClient.class, "sRuntimeRefreshStarted", false);
        ScheduledExecutorService recorder = (ScheduledExecutorService) Proxy.newProxyInstance(
                ScheduledExecutorService.class.getClassLoader(), new Class<?>[]{ScheduledExecutorService.class},
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName())) { immediate.add((Runnable) args[0]); return null; }
                    if ("scheduleWithFixedDelay".equals(method.getName())) {
                        assertEquals(0L, args[1]); assertEquals(5000L, args[2]);
                        periodic.add((Runnable) args[0]); return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        ReflectionHelpers.setStaticField(ConfigClient.class, "sAsyncExecutor", recorder);
        Sources.launch = null; Sources.full = null;
        context = RuntimeEnvironment.getApplication();
    }

    @After public void tearDown() {
        for (Map.Entry<String, Object> entry : previousFields.entrySet()) {
            ReflectionHelpers.setStaticField(ConfigClient.class, entry.getKey(), entry.getValue());
        }
        packages.clear(); packages.addAll(previousPackages);
        fallbackPackages.clear(); fallbackPackages.addAll(previousFallbacks);
        cache.clear(); cache.putAll(previousCache);
        timestamps.clear(); timestamps.putAll(previousTimestamps);
        ReflectionHelpers.setStaticField(FeatureGuard.class, "sCurrentPackage", previousPackage);
        Sources.launch = null; Sources.full = null;
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private JSONObject snapshot(boolean hook, long version) throws Exception {
        AppConfig app = new AppConfig(PKG);
        app.setHookEnabled(hook);
        app.setInspectorEnabled(false);
        app.setFeatureEnabled("native-feature", false);
        return new JSONObject().put("version", 1).put("updated_at", version)
                .put("global_sdk", new GlobalConfig().toJson())
                .put("apps", new JSONObject().put(PKG, app.toJson()));
    }

    private void runImmediate() {
        List<Runnable> tasks = new ArrayList<>(immediate);
        immediate.clear();
        for (Runnable task : tasks) task.run();
    }

    @Test public void repeatedActivityContextsDoNotQueueAnotherFullRefreshWhenInspectorIsOff() throws Exception {
        Sources.launch = HookLaunchSnapshot.fromConfig(snapshot(true, 100L));
        ConfigClient.initializeHookRuntime(PKG);
        ConfigClient.init(context);
        for (int i = 0; i < 100; i++) {
            ConfigClient.init(context);
            ConfigClient.init(new ContextWrapper(context));
        }
        assertEquals("Only the first Application readiness should request an immediate refresh", 1, immediate.size());
        assertSame(context, ConfigClient.getContext());
        assertFalse(ConfigClient.getAppConfigCached(PKG).isInspectorEnabled());
        assertEquals(1, periodic.size());
    }

    @Test public void firstContextStillUpgradesLaunchRulesAtTheSameVersion() throws Exception {
        Sources.full = snapshot(true, 100L);
        CustomRule rule = new CustomRule("ad", CustomRule.TYPE_TEXT, "广告", CustomRule.ACTION_COLLAPSE, 0);
        rule.setId("context-rule");
        Sources.full.getJSONObject("apps").getJSONObject(PKG).getJSONArray("customRules").put(rule.toJson());
        Sources.launch = HookLaunchSnapshot.fromConfig(Sources.full);
        ConfigClient.initializeHookRuntime(PKG);
        assertTrue(ConfigClient.getAppConfigCached(PKG).getCustomRules().isEmpty());
        ConfigClient.init(context);
        runImmediate();
        assertEquals("context-rule", ConfigClient.getAppConfigCached(PKG).getCustomRules().get(0).getId());
        assertTrue(ConfigClient.isHookEnabledCached(PKG));
        assertFalse(ConfigClient.getAppConfigCached(PKG).isFeatureEnabled("native-feature", true));
    }

    @Test public void periodicRefreshStillConsumesNewVersionsAfterRepeatedInitIsSkipped() throws Exception {
        Sources.full = snapshot(true, 100L);
        Sources.launch = HookLaunchSnapshot.fromConfig(Sources.full);
        ConfigClient.initializeHookRuntime(PKG);
        ConfigClient.init(context);
        runImmediate();
        for (int i = 0; i < 100; i++) ConfigClient.init(context);
        assertTrue(immediate.isEmpty());
        Sources.full = snapshot(false, 101L);
        periodic.get(0).run();
        assertTrue(ConfigClient.isHookEnabledCached(PKG));
        HookRuntimeSnapshot current = ReflectionHelpers.getStaticField(ConfigClient.class, "sHookRuntime");
        assertEquals(101L, current.updatedAt);
    }

    @Test public void recoveringFallbackCachesCanRefreshTheSameContextOnce() throws Exception {
        ConfigClient.initializeHookRuntime(PKG);
        ConfigClient.init(context);
        immediate.clear();
        fallbackPackages.add(PKG);
        cache.put(PKG, new AppConfig(PKG));
        timestamps.put(PKG, 1L);
        ConfigClient.init(context);
        assertTrue(fallbackPackages.isEmpty());
        assertTrue(cache.isEmpty());
        assertTrue(timestamps.isEmpty());
        assertEquals(1, immediate.size());
        ConfigClient.init(context);
        assertEquals(1, immediate.size());
    }

    @Test public void changedApplicationContextRefreshesOnceAndThenBecomesStable() {
        ConfigClient.initializeHookRuntime(PKG);
        ConfigClient.init(context);
        Context replacement = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
        };
        ConfigClient.init(replacement);
        for (int i = 0; i < 100; i++) ConfigClient.init(replacement);
        assertSame(replacement, ConfigClient.getContext());
        assertEquals(2, immediate.size());
    }

    @Test public void nullAndUnscopedContextsDoNotQueueRuntimeRefresh() {
        ConfigClient.init(null);
        assertTrue(immediate.isEmpty());
        assertNull(ReflectionHelpers.getStaticField(ConfigClient.class, "sContext"));
        ConfigClient.init(context);
        assertTrue(immediate.isEmpty());
        assertSame(context, ConfigClient.getContext());
        assertTrue(periodic.isEmpty());
    }
}
