package com.adhook.sdk;

import com.adhook.core.FeatureGuard;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

import com.adhook.runtime.HookCallback;

import static com.adhook.sdk.SdkAccountingTestSupport.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = {
        HookTestShadows.Bridge.class,
        SdkAccountingTestSupport.Helpers.class, SdkAccountingTestSupport.Client.class
})
public class SdkCallbackAccountingTest {
    private record Scenario(String sdk, Class<?> blocker, String detector, String method, Consumer<ClassLoader> install) { }
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("pangle", PangleBlocker.class, "com.bytedance.sdk.openadsdk.TTAdSdk", "loadSplashAd", PangleBlocker::install),
            new Scenario("kwad", KwadBlocker.class, "com.kwad.sdk.api.KsAdSDK", "loadSplashScreenAd", KwadBlocker::install),
            new Scenario("gdt", GdtBlocker.class, "com.qq.e.ads.splash.SplashAD", "fetchAdOnly", GdtBlocker::install),
            new Scenario("baidu", BaiduMobadsBlocker.class, "com.baidu.mobads.sdk.api.SplashAd", "load", BaiduMobadsBlocker::install),
            new Scenario("google", GoogleAdMobBlocker.class, "com.google.android.gms.ads.appopen.AppOpenAd", "load", GoogleAdMobBlocker::install),
            new Scenario("facebook", FacebookAdsBlocker.class, "com.facebook.ads.AdView", "loadAd", FacebookAdsBlocker::install),
            new Scenario("topon", TopOnBlocker.class, "com.anythink.splashad.api.ATSplashAd", "loadAd", TopOnBlocker::install),
            new Scenario("sigmob", SigmobBlocker.class, "com.sigmob.windad.Splash.WindSplashAD", "loadAd", SigmobBlocker::install),
            new Scenario("mintegral", MintegralBlocker.class, "com.mbridge.msdk.out.MBSplashHandler", "load", MintegralBlocker::install),
            new Scenario("mimo", MimoBlocker.class, "com.miui.zeus.mimo.sdk.ad.interstitial.InterstitialAd", "load", MimoBlocker::install));

    @Test public void allTenSdkLoadCallbacksCountOnlyRuntimeActionsAndRespectFeatureSwitches() throws Exception {
        for (Scenario scenario : SCENARIOS) {
            prepare(scenario);
            assertEquals(scenario.sdk, 0, FeatureGuard.getHitCount(FEATURE));
            Method method = AdFixture.class.getDeclaredMethod(scenario.method, TestListener.class);
            HookCallback callback = callback(method);
            TestListener listener = new TestListener();
            AdFixture ad = new AdFixture();
            HookCallback.MethodHookParam blocked = param(method, ad, listener);
            invoke(callback, "beforeHookedMethod", blocked);
            assertTrue(scenario.sdk, shortCircuited(blocked));
            assertEquals(scenario.sdk, 1, listener.failures);
            assertEquals(scenario.sdk, 1, FeatureGuard.getHitCount(FEATURE));
            assertEquals(scenario.sdk, 0, FeatureGuard.getHitCount(SdkRuntime.SHARED_FEATURE));

            feature(FEATURE, false);
            HookCallback.MethodHookParam featureOff = param(method, ad, listener);
            invoke(callback, "beforeHookedMethod", featureOff);
            assertFalse(scenario.sdk, shortCircuited(featureOff));
            assertEquals(scenario.sdk, 1, listener.failures);
            assertEquals(scenario.sdk, 1, FeatureGuard.getHitCount(FEATURE));

            feature(FEATURE, true);
            invoke(callback, "beforeHookedMethod", param(method, ad, listener));
            assertEquals(scenario.sdk, 2, listener.failures);
            assertEquals(scenario.sdk, 2, FeatureGuard.getHitCount(FEATURE));
        }
    }

    @Test public void readyDecisionsAreNotInstallationCounts() throws Exception {
        for (Scenario scenario : SCENARIOS) {
            if (!List.of("topon", "sigmob", "mintegral", "mimo").contains(scenario.sdk)) continue;
            prepare(scenario);
            Method method = AdFixture.class.getDeclaredMethod("topon".equals(scenario.sdk) ? "isAdReady" : "isReady");
            HookCallback callback = callback(method);
            assertEquals(0, FeatureGuard.getHitCount(FEATURE));
            HookCallback.MethodHookParam ready = param(method, new AdFixture());
            invoke(callback, "beforeHookedMethod", ready);
            assertTrue(shortCircuited(ready));
            assertEquals(false, ready.getResult());
            assertEquals(1, FeatureGuard.getHitCount(FEATURE));
        }
    }

    @Test public void missingSdkCanAppearLaterWithoutLosingFeatureOwnershipOrDoublingHooks() throws Exception {
        Scenario scenario = SCENARIOS.stream().filter(s -> "google".equals(s.sdk)).findFirst().orElseThrow();
        reset();
        clearHooks(scenario.blocker);
        FeatureGuard guard = new FeatureGuard(HOST);
        assertTrue(guard.run(FEATURE, () -> scenario.install.accept(getClass().getClassLoader())));
        assertTrue(HookTestShadows.Bridge.callbacks.isEmpty());
        assertEquals(0, FeatureGuard.getHitCount(FEATURE));
        Helpers.classes.put(scenario.detector, AdFixture.class);
        scenario.install.accept(getClass().getClassLoader());
        scenario.install.accept(getClass().getClassLoader());
        Method method = AdFixture.class.getDeclaredMethod("load", TestListener.class);
        assertEquals(Integer.valueOf(1), HookTestShadows.Bridge.successes.get(method));
        feature(FEATURE, false);
        HookCallback.MethodHookParam off = param(method, new AdFixture(), new TestListener());
        invoke(callback(method), "beforeHookedMethod", off);
        assertFalse(shortCircuited(off));
    }

    @Test public void pangleAndKwadExistingProxiesDelegateToRealSdkWhenFeatureOffAndDoNotCountCreation() throws Exception {
        for (String sdk : new String[]{"pangle", "kwad"}) {
            reset();
            clearHooks("pangle".equals(sdk) ? PangleBlocker.class : KwadBlocker.class);
            RealNative real = new RealNative();
            AdLoadApi proxy = makeProxy(sdk, real);
            assertNotSame(real, proxy);
            assertEquals(0, FeatureGuard.getHitCount(FEATURE));
            TestListener listener = new TestListener();
            proxy.loadFeedAd(listener);
            assertEquals(1, listener.failures);
            assertEquals(0, real.loads);
            assertEquals(1, FeatureGuard.getHitCount(FEATURE));
            assertEquals("real", proxy.regularCall());
            assertEquals(1, FeatureGuard.getHitCount(FEATURE));

            feature(FEATURE, false);
            proxy.loadFeedAd(listener);
            assertEquals(1, real.loads);
            assertEquals(1, listener.failures);
            assertEquals(1, FeatureGuard.getHitCount(FEATURE));
            real.failure = new IllegalStateException("original SDK error");
            try { proxy.loadFeedAd(listener); fail("expected original SDK error"); }
            catch (IllegalStateException expected) { assertSame(real.failure, expected); }

            real.failure = null;
            proxy.loadFeedAd(listener);
            assertEquals(3, real.loads);
            assertEquals(1, FeatureGuard.getHitCount(FEATURE));
        }
    }

    private void prepare(Scenario scenario) throws Exception {
        reset();
        clearHooks(scenario.blocker);
        if ("pangle".equals(scenario.sdk)) {
            Helpers.classes.put(scenario.detector, SdkMarker.class);
            Helpers.classes.put("com.bytedance.sdk.openadsdk.TTAdNative", PangleNativeApi.class);
        } else if ("kwad".equals(scenario.sdk)) {
            Helpers.classes.put(scenario.detector, SdkMarker.class);
            Helpers.classes.put("com.kwad.sdk.api.KsLoadManager", AdFixture.class);
        } else Helpers.classes.put(scenario.detector, AdFixture.class);
        assertTrue(scenario.sdk, new FeatureGuard(HOST).run(FEATURE,
                () -> scenario.install.accept(getClass().getClassLoader())));
        if ("pangle".equals(scenario.sdk)) {
            NativeFactory factory = new NativeFactory();
            Method get = SdkMarker.class.getDeclaredMethod("getAdManager");
            HookCallback.MethodHookParam manager = param(get, null);
            manager.setResult(factory);
            invoke(callback(get), "afterHookedMethod", manager);
            Method create = NativeFactory.class.getDeclaredMethod("createAdNative");
            HookCallback.MethodHookParam nativeResult = param(create, factory);
            nativeResult.setResult(new AdFixture());
            invoke(callback(create), "afterHookedMethod", nativeResult);
        }
    }

    private AdLoadApi makeProxy(String sdk, RealNative real) throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        if ("pangle".equals(sdk)) {
            Helpers.classes.put("com.bytedance.sdk.openadsdk.TTAdSdk", SdkMarker.class);
            Helpers.classes.put("com.bytedance.sdk.openadsdk.TTAdNative", PangleNativeApi.class);
            assertTrue(new FeatureGuard(HOST).run(FEATURE, () -> PangleBlocker.install(loader)));
            NativeFactory factory = new NativeFactory();
            Method get = SdkMarker.class.getDeclaredMethod("getAdManager");
            HookCallback.MethodHookParam manager = param(get, null);
            manager.setResult(factory);
            invoke(callback(get), "afterHookedMethod", manager);
            Method create = NativeFactory.class.getDeclaredMethod("createAdNative");
            HookCallback.MethodHookParam nativeResult = param(create, factory);
            nativeResult.setResult(real);
            invoke(callback(create), "afterHookedMethod", nativeResult);
            return (AdLoadApi) nativeResult.getResult();
        }
        Helpers.classes.put("com.kwad.sdk.api.KsAdSDK", SdkMarker.class);
        Helpers.classes.put("com.kwad.sdk.api.KsLoadManager", AdLoadApi.class);
        assertTrue(new FeatureGuard(HOST).run(FEATURE, () -> KwadBlocker.install(loader)));
        Method get = SdkMarker.class.getDeclaredMethod("getLoadManager");
        HookCallback.MethodHookParam manager = param(get, null);
        manager.setResult(real);
        invoke(callback(get), "afterHookedMethod", manager);
        return (AdLoadApi) manager.getResult();
    }

    public static class SdkMarker {
        public static PangleManagerApi getAdManager() { return null; }
        public static Object getLoadManager() { return null; }
    }

    public static class AdFixture implements PangleNativeApi {
        public AdFixture() { }
        public void load(TestListener listener) { }
        public void loadAd(TestListener listener) { }
        public void fetchAdOnly(TestListener listener) { }
        @Override public void loadSplashAd(TestListener listener) { }
        public void loadSplashScreenAd(TestListener listener) { }
        @Override public void loadFeedAd(TestListener listener) { }
        @Override public String regularCall() { return "fixture"; }
        public boolean isReady() { return true; }
        public boolean isAdReady() { return true; }
    }

    public static class TestListener {
        int failures;
        public void onNoAD(int code, String message) { failures++; }
    }

    public interface AdLoadApi {
        void loadFeedAd(TestListener listener);
        String regularCall();
    }

    public interface PangleNativeApi extends AdLoadApi {
        void loadSplashAd(TestListener listener);
    }

    public interface PangleManagerApi {
        PangleNativeApi createAdNative();
    }

    public static class RealNative implements PangleNativeApi {
        int loads;
        RuntimeException failure;
        @Override public void loadFeedAd(TestListener listener) { loads++; if (failure != null) throw failure; }
        @Override public void loadSplashAd(TestListener listener) { loads++; if (failure != null) throw failure; }
        @Override public String regularCall() { return "real"; }
    }

    public static class NativeFactory implements PangleManagerApi {
        @Override public PangleNativeApi createAdNative() { return null; }
    }
}
