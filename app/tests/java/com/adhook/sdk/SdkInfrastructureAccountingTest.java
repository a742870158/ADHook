package com.adhook.sdk;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.adhook.core.FeatureGuard;
import com.adhook.core.UniversalWebViewBlocker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import com.adhook.runtime.HookCallback;

import static com.adhook.sdk.SdkAccountingTestSupport.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = {
        HookTestShadows.Bridge.class,
        SdkAccountingTestSupport.Helpers.class, SdkAccountingTestSupport.Client.class
})
public class SdkInfrastructureAccountingTest {
    @Before public void setUp() throws Exception {
        reset();
        clearHooks(UniversalActivityBlocker.class);
        clearHooks(UniversalWebViewBlocker.class);
        ReflectionHelpers.<Map<?, ?>>getStaticField(UniversalActivityBlocker.class, "FEATURE_ROUTES").clear();
        ReflectionHelpers.<Set<?>>getStaticField(UniversalActivityBlocker.class, "extraBlockedActivities").clear();
    }

    @Test public void hostRouteUsesItsOwnFeatureNotAnotherSdkOwner() throws Exception {
        assertTrue(new FeatureGuard(HOST).run(FEATURE, UniversalActivityBlocker::install));
        UniversalActivityBlocker.addBlockedActivity(HOST, SECOND, "com.host.MarketingPage");
        feature(FEATURE, false);
        assertTrue(shortCircuited(route("com.host.MarketingPage")));
        assertEquals(1, FeatureGuard.getHitCount(SECOND));
        assertEquals(0, FeatureGuard.getHitCount(FEATURE));
        feature(SECOND, false);
        assertFalse(shortCircuited(route("com.host.MarketingPage")));
        assertEquals(1, FeatureGuard.getHitCount(SECOND));

        feature(FEATURE, true);
        assertTrue(shortCircuited(route("com.google.android.gms.ads.AdActivity")));
        assertEquals(1, FeatureGuard.getHitCount(FEATURE));
        assertFalse(shortCircuited(route("com.google.android.gms.ads.RewardActivity")));
        assertFalse(shortCircuited(route("com.host.NormalPage")));
        assertEquals(1, FeatureGuard.getHitCount(FEATURE));
    }

    @Test public void oneRouteOwnedByTwoFeaturesDoesNotCountTwice() throws Exception {
        UniversalActivityBlocker.install();
        UniversalActivityBlocker.addBlockedActivity(HOST, FEATURE, "com.host.MarketingPage");
        UniversalActivityBlocker.addBlockedActivity(HOST, SECOND, "com.host.MarketingPage");
        assertEquals(0, FeatureGuard.getHitCount(FEATURE) + FeatureGuard.getHitCount(SECOND));
        assertTrue(shortCircuited(route("com.host.MarketingPage")));
        assertEquals(1, FeatureGuard.getHitCount(FEATURE) + FeatureGuard.getHitCount(SECOND));
        assertEquals(0, FeatureGuard.getHitCount(SdkRuntime.SHARED_FEATURE));
    }

    @Test public void webRequestsCountActualBlocksButCssInjectionDoesNotPretendAnElementWasHidden() throws Exception {
        assertTrue(new FeatureGuard(HOST).run(FEATURE, UniversalWebViewBlocker::install));
        Method intercept = WebViewClient.class.getDeclaredMethod("shouldInterceptRequest", WebView.class, WebResourceRequest.class);
        HookCallback.MethodHookParam normal = param(intercept, new WebViewClient(), null, new Request("https://example.org/article"));
        invoke(callback(intercept), "beforeHookedMethod", normal);
        assertFalse(shortCircuited(normal));
        assertEquals(0, FeatureGuard.getHitCount(FEATURE));
        HookCallback.MethodHookParam ad = param(intercept, new WebViewClient(), null, new Request("https://pos.baidu.com/material"));
        invoke(callback(intercept), "beforeHookedMethod", ad);
        assertTrue(shortCircuited(ad));
        assertNotNull(ad.getResult());
        assertEquals(1, FeatureGuard.getHitCount(FEATURE));

        Method finished = WebViewClient.class.getDeclaredMethod("onPageFinished", WebView.class, String.class);
        RecordingWebView view = new RecordingWebView(RuntimeEnvironment.getApplication());
        HookCallback.MethodHookParam page = param(finished, new WebViewClient(), view, "https://example.org/article");
        invoke(callback(finished), "afterHookedMethod", page);
        assertEquals(1, view.injections);
        assertEquals("CSS injection is not a proven ad removal", 1, FeatureGuard.getHitCount(FEATURE));
        assertEquals(0, FeatureGuard.getHitCount("webview-css-inject"));

        feature(FEATURE, false);
        HookCallback.MethodHookParam off = param(intercept, new WebViewClient(), null, new Request("https://pos.baidu.com/material"));
        invoke(callback(intercept), "beforeHookedMethod", off);
        assertFalse(shortCircuited(off));
        invoke(callback(finished), "afterHookedMethod", page);
        assertEquals(1, view.injections);
        assertEquals(1, FeatureGuard.getHitCount(FEATURE));
        view.destroy();
    }

    @Test public void realSdkActivityFinishRequiresEnabledFeatureAndCountsOnlySuccessfulFirstFinish() throws Exception {
        for (String sdk : new String[]{"topon", "sigmob", "mintegral"}) {
            reset();
            assertTrue(new FeatureGuard(HOST).run(FEATURE, () -> SdkRuntime.bind(sdk)));
            ActivityController<SdkRuntimeRegressionTest.TrackingActivity> controller =
                    Robolectric.buildActivity(SdkRuntimeRegressionTest.TrackingActivity.class).create();
            SdkRuntimeRegressionTest.TrackingActivity activity = controller.get();
            Method create = activity.getClass().getDeclaredMethod("onCreate", Bundle.class);
            HookCallback.MethodHookParam param = param(create, activity, new Bundle());
            HookCallback finish = SdkActivityLifecycle.finishAfterCreate(sdk, sdk);
            feature(FEATURE, false);
            invoke(finish, "afterHookedMethod", param);
            assertFalse(activity.isFinishing());
            feature(FEATURE, true);
            activity.taskRoot = true;
            invoke(finish, "afterHookedMethod", param);
            assertFalse(activity.isFinishing()); assertEquals(0, FeatureGuard.getHitCount(FEATURE));
            activity.unknownTaskRoot = true;
            invoke(finish, "afterHookedMethod", param);
            assertFalse(activity.isFinishing()); assertEquals(0, FeatureGuard.getHitCount(FEATURE));
            activity.taskRoot = false; activity.unknownTaskRoot = false;
            activity.throwTransition = true;
            invoke(finish, "afterHookedMethod", param);
            assertTrue(activity.createCompleted);
            assertTrue(activity.isFinishing());
            assertEquals(1, activity.finishCalls);
            assertEquals(1, FeatureGuard.getHitCount(FEATURE));
            invoke(finish, "afterHookedMethod", param);
            assertEquals(1, FeatureGuard.getHitCount(FEATURE));
            assertFalse(shortCircuited(param));
            controller.destroy();
        }
    }

    @Test public void mimoViewConstructorsCountOnlyRealFirstCollapse() throws Exception {
        clearHooks(MimoBlocker.class);
        Helpers.classes.put("com.miui.zeus.mimo.sdk.ad.interstitial.InterstitialAd", SdkCallbackAccountingTest.AdFixture.class);
        Helpers.classes.put("com.miui.zeus.mimo.sdk.ad.interstitial.view.InterstitialSkipCountDownView", SkipView.class);
        assertTrue(new FeatureGuard(HOST).run(FEATURE, () -> MimoBlocker.install(getClass().getClassLoader())));
        Constructor<SkipView> constructor = SkipView.class.getDeclaredConstructor(Context.class);
        SkipView view = new SkipView(RuntimeEnvironment.getApplication());
        view.setLayoutParams(new ViewGroup.LayoutParams(100, 80));
        HookCallback.MethodHookParam construction = param(constructor, view, RuntimeEnvironment.getApplication());
        assertEquals(0, FeatureGuard.getHitCount(FEATURE));
        invoke(callback(constructor), "afterHookedMethod", construction);
        assertEquals(View.GONE, view.getVisibility());
        assertEquals(0, view.getLayoutParams().height);
        assertEquals(1, FeatureGuard.getHitCount(FEATURE));
        invoke(callback(constructor), "afterHookedMethod", construction);
        assertEquals(1, FeatureGuard.getHitCount(FEATURE));
        feature(FEATURE, false);
        SkipView untouched = new SkipView(RuntimeEnvironment.getApplication());
        untouched.setLayoutParams(new ViewGroup.LayoutParams(100, 80));
        invoke(callback(constructor), "afterHookedMethod", param(constructor, untouched, RuntimeEnvironment.getApplication()));
        assertEquals(View.VISIBLE, untouched.getVisibility());
        assertEquals(80, untouched.getLayoutParams().height);
        assertEquals(1, FeatureGuard.getHitCount(FEATURE));
    }

    private HookCallback.MethodHookParam route(String className) throws Exception {
        Method method = HookTestShadows.Bridge.callbacks.keySet().stream()
                .filter(member -> member.getDeclaringClass() == Instrumentation.class)
                .map(member -> (Method) member).filter(candidate -> Arrays.asList(candidate.getParameterTypes()).contains(Intent.class))
                .findFirst().orElseThrow();
        Object[] args = new Object[method.getParameterCount()];
        int index = Arrays.asList(method.getParameterTypes()).indexOf(Intent.class);
        args[index] = new Intent().setComponent(new ComponentName(HOST, className));
        HookCallback.MethodHookParam param = param(method, new Instrumentation(), args);
        invoke(callback(method), "beforeHookedMethod", param);
        return param;
    }

    public static class RecordingWebView extends WebView {
        int injections;
        public RecordingWebView(Context context) { super(context); }
        @Override public void evaluateJavascript(String script, ValueCallback<String> result) { injections++; }
    }

    public static class SkipView extends View {
        public SkipView(Context context) { super(context); }
    }

    private record Request(String url) implements WebResourceRequest {
        @Override public Uri getUrl() { return Uri.parse(url); }
        @Override public boolean isForMainFrame() { return false; }
        @Override public boolean isRedirect() { return false; }
        @Override public boolean hasGesture() { return false; }
        @Override public String getMethod() { return "GET"; }
        @Override public Map<String, String> getRequestHeaders() { return Map.of(); }
    }
}
