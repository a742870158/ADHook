package com.adhook.sdk;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.adhook.core.UniversalWebViewBlocker;
import com.adhook.runtime.NativeApi101TestFramework;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import static org.junit.Assert.*;

/** Same supported WebResourceRequest path, with real production callback/guard/native adapter. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = SdkAccountingTestSupport.Client.class)
public class PublicWebViewMinSdkTest {
    public static class Client extends WebViewClient {
        final WebResourceResponse original = new WebResourceResponse("original/plain", "UTF-8", new ByteArrayInputStream(new byte[]{1}));
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return original; }
        @Override public void onPageFinished(WebView view, String url) { }
    }
    private NativeApi101TestFramework framework;
    private Method intercept;
    @Before public void setUp() throws Exception {
        SdkAccountingTestSupport.reset(); SdkAccountingTestSupport.clearHooks(UniversalWebViewBlocker.class);
        framework = new NativeApi101TestFramework(); framework.reset(); framework.initialize();
        Method install = UniversalWebViewBlocker.class.getDeclaredMethod("hookClientClass", Class.class); install.setAccessible(true);
        SdkRuntime.bind("webview"); install.invoke(null, Client.class);
        intercept = Client.class.getDeclaredMethod("shouldInterceptRequest", WebView.class, WebResourceRequest.class);
        assertEquals(1, framework.installCount(intercept));
    }
    @After public void tearDown() { framework.reset(); }
    private static WebResourceRequest request(String url) {
        return new WebResourceRequest() {
            @Override public Uri getUrl() { return Uri.parse(url); }
            @Override public boolean isForMainFrame() { return false; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return false; }
            @Override public String getMethod() { return "GET"; }
            @Override public Map<String, String> getRequestHeaders() { return Collections.emptyMap(); }
        };
    }
    @Test public void enabledKnownAdRequestStillGetsTheSameEmptyResponse() throws Throwable {
        Client client = new Client();
        WebResourceResponse result = (WebResourceResponse) framework.call(intercept, client, null, request("https://pos.baidu.com/banner"));
        assertNotSame(client.original, result); assertEquals("text/plain", result.getMimeType()); assertEquals("UTF-8", result.getEncoding());
        assertEquals(-1, result.getData().read()); result.getData().close();
    }
    @Test public void closedHookLeavesTheOriginalRequestOutcomeUnchanged() throws Throwable {
        SdkAccountingTestSupport.Client.apps.get(SdkAccountingTestSupport.HOST).setHookEnabled(false);
        Client client = new Client();
        assertSame(client.original, framework.call(intercept, client, null, request("https://pos.baidu.com/banner")));
    }
    @Test public void nonAdRequestStillPassesThroughEvenWhenTheLayerIsEnabled() throws Throwable {
        Client client = new Client();
        assertSame(client.original, framework.call(intercept, client, null, request("https://example.org/content")));
    }
}
