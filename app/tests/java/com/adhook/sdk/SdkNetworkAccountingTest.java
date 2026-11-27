package com.adhook.sdk;

import com.adhook.core.FeatureGuard;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.util.concurrent.Future;

import com.adhook.runtime.HookCallback;

import static com.adhook.sdk.SdkAccountingTestSupport.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = {
        HookTestShadows.Bridge.class,
        SdkAccountingTestSupport.Helpers.class, SdkAccountingTestSupport.Client.class
})
public class SdkNetworkAccountingTest {
    @Before public void setUp() throws Exception {
        reset();
        clearHooks(GdtBlocker.class);
        Helpers.classes.put("com.qq.e.ads.splash.SplashAD", SdkCallbackAccountingTest.AdFixture.class);
        Helpers.classes.put("com.qq.e.comm.net.NetworkClientImpl", ClientFixture.class);
        Helpers.classes.put("com.qq.e.comm.net.rr.Request", Request.class);
        Helpers.classes.put("com.qq.e.comm.net.rr.Response", Response.class);
        Helpers.classes.put("com.qq.e.comm.net.NetworkCallBack", NetworkCallback.class);
        Helpers.classes.put("com.qq.e.comm.net.NetworkClientImpl$TaskCallable", TaskFixture.class);
        Helpers.classes.put("com.qq.e.comm.net.rr.PlainRequest", Request.class);
        assertTrue(new FeatureGuard(HOST).run(FEATURE, () -> GdtBlocker.install(getClass().getClassLoader())));
    }

    @Test public void submitCountsOnlyActualAdResponseReplacementAndPreservesCallback() throws Exception {
        Method submit = ClientFixture.class.getDeclaredMethod("submit", Request.class, NetworkCallback.class);
        HookCallback hook = callback(submit);
        ClientFixture client = new ClientFixture();
        int[] replies = {0};
        NetworkCallback callback = (request, response) -> {
            assertNotNull(response);
            assertEquals("{}", response.getStringContent());
            replies[0]++;
        };
        assertEquals(0, FeatureGuard.getHitCount(FEATURE));
        HookCallback.MethodHookParam normal = param(submit, client, new Request("https://gdt.qq.com/config"), callback);
        invoke(hook, "beforeHookedMethod", normal);
        assertFalse(shortCircuited(normal));
        assertEquals(0, replies[0]);
        assertEquals(0, FeatureGuard.getHitCount(FEATURE));

        HookCallback.MethodHookParam ad = param(submit, client, new Request("https://e.qq.com/ad"), callback);
        invoke(hook, "beforeHookedMethod", ad);
        assertTrue(shortCircuited(ad));
        assertEquals(1, replies[0]);
        assertEquals(1, FeatureGuard.getHitCount(FEATURE));
        Future<?> future = (Future<?>) ad.getResult();
        assertEquals("{}", ((Response) future.get()).getStringContent());
        assertEquals("reading an already-created response is not a new block", 1, FeatureGuard.getHitCount(FEATURE));
    }

    @Test public void taskAndResponseFallbacksCountOnlyAfterCommittingAMatchingResponse() throws Exception {
        Request request = new Request("https://e.qq.com/ad");
        Method call = TaskFixture.class.getDeclaredMethod("call");
        HookCallback.MethodHookParam task = param(call, new TaskFixture(request));
        invoke(callback(call), "beforeHookedMethod", task);
        assertTrue(shortCircuited(task));
        assertNotNull(task.getResult());
        assertEquals(1, FeatureGuard.getHitCount(FEATURE));
        Method response = Request.class.getDeclaredMethod("initResponse", HttpURLConnection.class);
        HookCallback.MethodHookParam result = param(response, request, new Object[]{null});
        invoke(callback(response), "beforeHookedMethod", result);
        assertTrue(shortCircuited(result));
        assertEquals(2, FeatureGuard.getHitCount(FEATURE));
        feature(FEATURE, false);
        HookCallback.MethodHookParam off = param(response, request, new Object[]{null});
        invoke(callback(response), "beforeHookedMethod", off);
        assertFalse(shortCircuited(off));
        invoke(callback(call), "beforeHookedMethod", param(call, new TaskFixture(request)));
        assertEquals(2, FeatureGuard.getHitCount(FEATURE));
    }

    public interface Response { String getStringContent(); }
    public interface NetworkCallback { void onResponse(Request request, Response response); }
    public static class Request {
        private final String url;
        public Request(String url) { this.url = url; }
        public String getUrlWithParas() { return url; }
        public Response initResponse(HttpURLConnection connection) { return null; }
    }
    public static class ClientFixture {
        public Future<Response> submit(Request request, NetworkCallback callback) { return null; }
    }
    public static class TaskFixture {
        private final Request request;
        public TaskFixture(Request request) { this.request = request; }
        public Response call() { return null; }
    }
}
