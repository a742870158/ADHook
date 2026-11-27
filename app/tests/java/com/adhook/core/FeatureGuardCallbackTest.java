package com.adhook.core;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import com.adhook.config.ConfigClient;
import com.adhook.config.model.AppConfig;
import com.adhook.config.model.FeatureMeta;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import com.adhook.runtime.HookCallback;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = {
        FeatureGuardCallbackTest.Client.class
})
public class FeatureGuardCallbackTest {
    private static final String PKG = "com.guard.test";

    @Implements(value = ConfigClient.class, isInAndroidSdk = false)
    public static class Client {
        static AppConfig config;
        @Implementation protected static boolean isHookEnabledCached(String pkg) {
            return config != null && config.isHookEnabled();
        }
        @Implementation protected static AppConfig getAppConfigCached(String pkg) { return config; }
        @Implementation protected static boolean isFeatureEnabled(String pkg, String id, boolean fallback) {
            throw new AssertionError("Callback must not read configuration transport");
        }
        @Implementation protected static void scheduleRuntimeStats(String pkg, Supplier<JSONObject> source) { }
    }

    @Before public void setUp() throws Exception {
        reset();
        Client.config = new AppConfig(PKG);
        Client.config.setHookEnabled(true);
        FeatureGuard.configureHost(PKG, PKG, PKG, List.of(
                new FeatureMeta("feature", "", ""), new FeatureMeta("other", "", "")));
        ReflectionHelpers.setStaticField(ViewCollapser.class, "visibilityHookInstalled", true);
    }

    @After public void tearDown() throws Exception { reset(); }

    private static void reset() throws Exception {
        for (String name : new String[]{"sCurrentPackage", "sPrimaryPackage", "sProcessName"}) {
            Field field = FeatureGuard.class.getDeclaredField(name); field.setAccessible(true); field.set(null, null);
        }
        for (String name : new String[]{"ALIASES", "PREFIX_ALIASES", "DEFAULTS", "CATALOG_PRIMARY", "HIT_COUNTS", "LAST_HIT_TIMES", "GLOBAL_STATES", "REGISTRATION_STATES", "VIEW_OWNERS"}) {
            Field field = FeatureGuard.class.getDeclaredField(name); field.setAccessible(true); ((Map<?, ?>) field.get(null)).clear();
        }
    }

    @Test public void offAndUnknownStillRegisterButNeverExecuteBusinessActions() throws Exception {
        FeatureGuard guard = new FeatureGuard(PKG);
        Client.config.setHookEnabled(false);
        Client.config.setFeatureEnabled("feature", false);
        assertTrue(guard.run("feature", () -> assertEquals("feature", FeatureGuard.currentRegistrationFeature())));
        assertFalse(FeatureGuard.isEnabled(PKG, "feature"));
        HookCallback.MethodHookParam off = param(Methods.class.getMethod("load"));
        invoke(FeatureGuard.block(PKG, "feature", false), off);
        assertNull(off.getResult());
        Client.config = null;
        assertTrue(guard.run("feature", () -> { }));
        assertFalse(FeatureGuard.isEnabled(PKG, "feature"));
        assertEquals(0, FeatureGuard.getHitCount("feature"));
    }

    @Test public void registrationAndCapabilityDeclarationNeverCountAsHits() {
        FeatureGuard guard = new FeatureGuard(PKG);
        assertTrue(guard.run("feature", () -> FeatureGuard.track(PKG, "feature")));
        assertEquals(Integer.valueOf(0), FeatureGuard.getAllHitCounts().get("feature"));
        assertEquals(0L, FeatureGuard.getLastHitTime("feature"));
    }

    @Test public void onlyExecutingAnEnabledBlockingCallbackIncrementsTheCanonicalCounter() throws Exception {
        FeatureGuard.registerAlias(PKG, "feature", "internal-feature");
        HookCallback hook = FeatureGuard.block(PKG, "internal-feature", false);
        assertEquals(0, FeatureGuard.getHitCount("feature"));
        HookCallback.MethodHookParam first = param(Methods.class.getMethod("load"));
        invoke(hook, first);
        assertEquals(Boolean.FALSE, first.getResult());
        assertEquals(1, FeatureGuard.getHitCount("feature"));
        assertEquals(1, FeatureGuard.getHitCount("internal-feature"));
        assertFalse(FeatureGuard.getAllHitCounts().containsKey("internal-feature"));
        Client.config.setFeatureEnabled("feature", false);
        HookCallback.MethodHookParam off = param(Methods.class.getMethod("load"));
        invoke(hook, off);
        assertNull(off.getResult());
        assertEquals(1, FeatureGuard.getHitCount("feature"));
    }

    @Test public void unsafeResultsAndActivityOnCreateAreAlwaysAllowedThroughWithoutHits() throws Exception {
        invoke(FeatureGuard.block(PKG, "feature", "wrong"), param(Methods.class.getMethod("load")));
        invoke(FeatureGuard.block(PKG, "feature", null), param(TestActivity.class.getDeclaredMethod("onCreate", Bundle.class)));
        assertEquals(0, FeatureGuard.getHitCount("feature"));
    }

    @Test public void nullPrimitiveResultIsSafelyTypedAtTheBlockingBoundary() throws Exception {
        HookCallback.MethodHookParam param = param(Methods.class.getMethod("delay"));
        invoke(FeatureGuard.block(PKG, "feature", null), param);
        assertEquals(Long.valueOf(0), param.getResult());
        assertEquals(1, FeatureGuard.getHitCount("feature"));
    }

    @Test public void repeatedViewInspectionCountsOnceAndOnlyReleasesItsOwnOwner() {
        View view = new View(RuntimeEnvironment.getApplication());
        view.setLayoutParams(new ViewGroup.LayoutParams(100, 80));
        assertTrue(FeatureGuard.collapse(PKG, "feature", view));
        assertFalse(FeatureGuard.collapse(PKG, "feature", view));
        FeatureGuard.preventExpansion(PKG, "feature", view);
        assertEquals(1, FeatureGuard.getHitCount("feature"));
        Object otherOwner = new Object();
        ViewCollapser.collapse(view, otherOwner);
        Client.config.setFeatureEnabled("feature", false);
        assertFalse(FeatureGuard.collapse(PKG, "feature", view));
        assertEquals(View.GONE, view.getVisibility());
        ViewCollapser.restore(view, otherOwner);
        assertEquals(View.VISIBLE, view.getVisibility());
        Client.config.setFeatureEnabled("feature", true);
        assertTrue(FeatureGuard.collapse(PKG, "feature", view));
        assertEquals(2, FeatureGuard.getHitCount("feature"));
        FeatureGuard.restore(PKG, "feature", view);
    }

    @Test public void configurationPublicationReleasesDisabledFeatureButNotOtherOwners() {
        View view = new View(RuntimeEnvironment.getApplication());
        view.setLayoutParams(new ViewGroup.LayoutParams(100, 80));
        FeatureGuard.preventExpansion(PKG, "feature", view);
        Object other = new Object();
        ViewCollapser.preventExpansion(view, other);
        Client.config.setHookEnabled(false);
        FeatureGuard.onHookConfigChanged(PKG);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(View.GONE, view.getVisibility());
        ViewCollapser.restore(view, other);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(80, view.getLayoutParams().height);
    }

    @Test public void successfulNestedRegistrationRestoresTheOuterOwner() {
        FeatureGuard guard = new FeatureGuard(PKG);
        assertTrue(guard.run("feature", () -> {
            assertEquals("feature", FeatureGuard.currentRegistrationFeature());
            guard.run("other", () -> assertEquals("other", FeatureGuard.currentRegistrationFeature()));
            assertEquals("feature", FeatureGuard.currentRegistrationFeature());
        }));
        assertNull(FeatureGuard.currentRegistrationFeature());
    }

    @Test public void oneFailedAliasIsNotHiddenByOtherSuccessfulRegistrationsAndCanRecover() throws Exception {
        FeatureGuard.registerAlias(PKG, "feature", "part-one", "part-two");
        FeatureGuard guard = new FeatureGuard(PKG);
        assertFalse(guard.run("part-one", () -> { throw new IllegalStateException("missing"); }));
        assertTrue(guard.run("part-two", () -> { }));
        Field states = FeatureGuard.class.getDeclaredField("GLOBAL_STATES"); states.setAccessible(true);
        assertEquals(FeatureGuard.State.FAILED, ((Map<?, ?>) states.get(null)).get("feature"));
        assertTrue(guard.run("part-one", () -> { }));
        assertEquals(FeatureGuard.State.OK, ((Map<?, ?>) states.get(null)).get("feature"));
        assertEquals(0, FeatureGuard.getHitCount("feature"));
    }

    @Test public void realDynamicHitClearsAnOldDisabledRegistrationButNotFailure() throws Exception {
        FeatureGuard guard = new FeatureGuard(PKG);
        guard.markDisabled("feature");
        Field states = FeatureGuard.class.getDeclaredField("GLOBAL_STATES"); states.setAccessible(true);
        FeatureGuard.hit("feature");
        assertEquals(FeatureGuard.State.OK, ((Map<?, ?>) states.get(null)).get("feature"));
        guard.run("feature", () -> { throw new IllegalStateException("failed registration"); });
        FeatureGuard.hit("feature");
        assertEquals(FeatureGuard.State.FAILED, ((Map<?, ?>) states.get(null)).get("feature"));
    }

    @Test public void concurrentHitsAccumulateExactlyAndSaturateBeforePacketOverflow() throws Exception {
        int threadCount = 4;
        CountDownLatch done = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) new Thread(() -> {
            try { for (int hit = 0; hit < 500; hit++) FeatureGuard.hit("feature"); }
            finally { done.countDown(); }
        }).start();
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(2000, FeatureGuard.getHitCount("feature"));
        FeatureGuard.hit("feature", Integer.MAX_VALUE);
        FeatureGuard.hit("feature");
        assertEquals(1_000_000_000, FeatureGuard.getHitCount("feature"));
    }

    private static HookCallback.MethodHookParam param(Method method) {
        HookCallback.MethodHookParam value = ReflectionHelpers.callConstructor(HookCallback.MethodHookParam.class);
        value.method = method;
        value.args = new Object[0];
        return value;
    }

    private static void invoke(HookCallback hook, HookCallback.MethodHookParam param) throws Exception {
        Method before = hook.getClass().getDeclaredMethod("beforeHookedMethod", HookCallback.MethodHookParam.class);
        before.setAccessible(true); before.invoke(hook, param);
    }

    public static class Methods {
        public boolean load() { return true; }
        public long delay() { return 12L; }
    }
    public static class TestActivity extends Activity {
        @Override protected void onCreate(Bundle state) { super.onCreate(state); }
    }
}
