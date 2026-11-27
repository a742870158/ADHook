package com.adhook.core;

import com.adhook.config.ConfigClient;
import com.adhook.config.model.AppConfig;
import com.adhook.config.model.FeatureMeta;
import com.adhook.config.model.GlobalConfig;
import com.adhook.runtime.HookBridge;
import com.adhook.runtime.HookCallback;
import com.adhook.runtime.NativeApi101TestFramework;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Uses the real ConfigClient memory getter and production guard/native bridge, not a guard shadow. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class FeatureGuardSingleSnapshotTest {
    private static final String ACTUAL = "com.snapshot.actual", PRIMARY = "com.snapshot.primary", FEATURE = "native-feature";
    private Field runtime;
    private Object previousRuntime, previousPackage, previousPrimary, previousProcess;
    private NativeApi101TestFramework framework;
    public static class Target { public String echo(String value) { return "host:" + value; } }

    /** A deterministic publication seam exactly between the Hook boolean read and feature evaluation. */
    private static class PublishingApp extends AppConfig {
        Runnable publication;
        PublishingApp() { super(ACTUAL); }
        @Override public boolean isHookEnabled() {
            boolean captured = super.isHookEnabled();
            Runnable action = publication; publication = null;
            if (action != null) action.run();
            return captured;
        }
    }
    @Before public void setUp() throws Exception {
        framework = new NativeApi101TestFramework(); framework.reset(); framework.initialize();
        runtime = ConfigClient.class.getDeclaredField("sHookRuntime"); runtime.setAccessible(true); previousRuntime = runtime.get(null);
        previousPackage = value("sCurrentPackage"); previousPrimary = value("sPrimaryPackage"); previousProcess = value("sProcessName");
        set("sCurrentPackage", ACTUAL); set("sPrimaryPackage", PRIMARY); set("sProcessName", ACTUAL);
        for (String field : new String[]{"ALIASES", "PREFIX_ALIASES", "DEFAULTS", "CATALOG_PRIMARY"}) {
            Field map = FeatureGuard.class.getDeclaredField(field); map.setAccessible(true);
            ((Map<?, ?>) map.get(null)).remove(ACTUAL); ((Map<?, ?>) map.get(null)).remove(PRIMARY);
        }
        FeatureGuard.configureHost(ACTUAL, PRIMARY, ACTUAL, List.of(new FeatureMeta(FEATURE, "", "")));
        FeatureGuard.registerAlias(PRIMARY, FEATURE, "old-native");
    }
    @After public void tearDown() throws Exception {
        framework.reset(); runtime.set(null, previousRuntime);
        set("sCurrentPackage", previousPackage); set("sPrimaryPackage", previousPrimary); set("sProcessName", previousProcess);
    }
    private static Object value(String name) throws Exception { Field field = FeatureGuard.class.getDeclaredField(name); field.setAccessible(true); return field.get(null); }
    private static void set(String name, Object value) throws Exception { Field field = FeatureGuard.class.getDeclaredField(name); field.setAccessible(true); field.set(null, value); }
    private Object snapshot(long version, Map<String, AppConfig> apps) throws Exception {
        Class<?> type = Class.forName("com.adhook.config.HookRuntimeSnapshot");
        Constructor<?> constructor = type.getDeclaredConstructor(long.class, boolean.class, GlobalConfig.class, Map.class); constructor.setAccessible(true);
        return constructor.newInstance(version, true, new GlobalConfig(), apps);
    }
    private AppConfig app(boolean hook, boolean feature) { AppConfig app = new AppConfig(ACTUAL); app.setHookEnabled(hook); app.setFeatureEnabled(FEATURE, feature); return app; }
    private void installInterleaving() throws Exception {
        PublishingApp old = new PublishingApp(); old.setHookEnabled(true); old.setFeatureEnabled(FEATURE, false);
        AppConfig closed = app(false, true);
        Object replacement = snapshot(102L, Map.of(ACTUAL, closed));
        old.publication = () -> { try { runtime.set(null, replacement); } catch (IllegalAccessException failure) { throw new AssertionError(failure); } };
        runtime.set(null, snapshot(101L, Map.of(ACTUAL, old)));
    }

    @Test public void publicationBetweenBooleanAndFeatureReadCannotManufacturePermission() throws Exception {
        installInterleaving();
        assertFalse("Old Hook=true/feature=false must not combine with new Hook=false/feature=true", FeatureGuard.isEnabled(ACTUAL, FEATURE, true));
        assertFalse(FeatureGuard.isEnabled(ACTUAL, FEATURE, true));
        assertFalse(ConfigClient.getAppConfigCached(ACTUAL).isHookEnabled());
        assertTrue(ConfigClient.getAppConfigCached(ACTUAL).getNativeFeatures().get(FEATURE));
    }

    @Test public void realGuardedNativeCallbackNeverActsForEitherIndividuallyDeniedSnapshot() throws Throwable {
        installInterleaving(); AtomicInteger before = new AtomicInteger(), after = new AtomicInteger();
        Method method = Target.class.getMethod("echo", String.class);
        FeatureGuard.withRegistrationOwner(ACTUAL, FEATURE, () -> HookRegistration.hookMethod(method, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { before.incrementAndGet(); param.setResult("blocked"); }
            @Override protected void afterHookedMethod(MethodHookParam param) { after.incrementAndGet(); }
        }));
        assertEquals("host:x", framework.call(method, new Target(), "x"));
        assertEquals(0, before.get()); assertEquals(0, after.get());
    }

    @Test public void closedAndUnknownRemainDeniedEvenWithEnabledFeatureAndTrueFallback() throws Exception {
        runtime.set(null, snapshot(103L, Map.of(ACTUAL, app(false, true))));
        assertFalse(FeatureGuard.isEnabled(ACTUAL, FEATURE, true));
        runtime.set(null, snapshot(104L, Collections.emptyMap()));
        assertFalse(FeatureGuard.isEnabled(ACTUAL, FEATURE, true)); assertFalse(FeatureGuard.isEnabled(ACTUAL, null, true));
    }

    @Test public void actualPackageRemapAndCanonicalAliasPreferenceRemainEffective() throws Exception {
        AppConfig enabled = app(true, true); enabled.setFeatureEnabled("old-native", false);
        runtime.set(null, snapshot(105L, Map.of(ACTUAL, enabled)));
        assertTrue(FeatureGuard.isEnabled(PRIMARY, "old-native", false));
        enabled = app(true, false); enabled.setFeatureEnabled("old-native", true);
        runtime.set(null, snapshot(106L, Map.of(ACTUAL, enabled)));
        assertFalse(FeatureGuard.isEnabled(PRIMARY, "old-native", true));
        assertFalse(FeatureGuard.isEnabled("com.other.host", FEATURE, true));
    }

    @Test public void authorizedSnapshotStillAllowsProductionCallback() throws Throwable {
        runtime.set(null, snapshot(107L, Map.of(ACTUAL, app(true, true))));
        Method method = Target.class.getMethod("echo", String.class); AtomicInteger hits = new AtomicInteger();
        FeatureGuard.withRegistrationOwner(ACTUAL, FEATURE, () -> HookRegistration.hookMethod(method, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { hits.incrementAndGet(); param.setResult("blocked"); }
        }));
        assertEquals("blocked", framework.call(method, new Target(), "x")); assertEquals(1, hits.get());
    }
}
