package com.adhook.sdk;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.adhook.core.UniversalWebViewBlocker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import com.adhook.runtime.HookCallback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = {
        HookTestShadows.Bridge.class
})
public class WebViewHookRetryTest {
    @Before public void resetHookFixtures() throws Exception {
        HookTestShadows.Bridge.reset();
        // Fail at the fixture's actual cause, not at a zero counter after production catches it.
        Method probe = ClientFixture.class.getDeclaredMethod("onPageFinished", WebView.class, String.class);
        assertNotNull(HookTestShadows.Bridge.hookMethod(probe, new HookCallback() { }));
        HookTestShadows.Bridge.reset();
        Field field = UniversalWebViewBlocker.class.getDeclaredField("HOOKS");
        field.setAccessible(true);
        SdkHookRegistry registry = (SdkHookRegistry) field.get(null);
        Field hooks = SdkHookRegistry.class.getDeclaredField("hooks");
        hooks.setAccessible(true);
        ((Map<?, ?>) hooks.get(registry)).clear();
    }

    @Test public void failedSetClientHookRetriesWithoutDuplicatingSuccessfulBaseClientHooks() throws Exception {
        Method setClient = WebView.class.getDeclaredMethod("setWebViewClient", WebViewClient.class);
        Method intercept = WebViewClient.class.getDeclaredMethod("shouldInterceptRequest", WebView.class, WebResourceRequest.class);
        Method finished = WebViewClient.class.getDeclaredMethod("onPageFinished", WebView.class, String.class);
        HookTestShadows.Bridge.failOnce.add(setClient);
        UniversalWebViewBlocker.install();
        assertEquals(1, attempts(setClient));
        assertEquals(0, successes(setClient));
        assertEquals(1, successes(intercept));
        assertEquals(1, successes(finished));

        UniversalWebViewBlocker.install();
        UniversalWebViewBlocker.install();
        assertEquals(2, attempts(setClient));
        assertEquals(1, successes(setClient));
        assertEquals(1, attempts(intercept));
        assertEquals(1, attempts(finished));
    }

    @Test public void dynamicClientPartialFailureRetriesOnlyTheFailedMethod() throws Exception {
        Method intercept = ClientFixture.class.getDeclaredMethod("shouldInterceptRequest", WebView.class, WebResourceRequest.class);
        Method finished = ClientFixture.class.getDeclaredMethod("onPageFinished", WebView.class, String.class);
        Method discover = UniversalWebViewBlocker.class.getDeclaredMethod("hookClientClass", Class.class);
        discover.setAccessible(true);
        HookTestShadows.Bridge.failOnce.add(intercept);
        discover.invoke(null, ClientFixture.class);
        assertEquals(1, attempts(intercept));
        assertEquals(0, successes(intercept));
        assertEquals(1, successes(finished));

        discover.invoke(null, ClientFixture.class);
        discover.invoke(null, ClientFixture.class);
        assertEquals(2, attempts(intercept));
        assertEquals(1, successes(intercept));
        assertEquals(1, attempts(finished));
    }

    private static int attempts(Method method) {
        return HookTestShadows.Bridge.attempts.getOrDefault(method, 0);
    }

    private static int successes(Method method) {
        return HookTestShadows.Bridge.successes.getOrDefault(method, 0);
    }

    public static class ClientFixture extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return null; }
        @Override public void onPageFinished(WebView view, String url) { }
    }
}
