package com.adhook.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;

import com.adhook.config.model.AppConfig;
import com.adhook.config.model.GlobalConfig;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class RemoteConfigPublisherTest {
    @Test public void pendingFailureAndAcknowledgementNotifyTheAppObserver() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        AtomicInteger changes = new AtomicInteger();
        Uri uri = ConfigProvider.CONTENT_URI.buildUpon().appendPath("remote-publication").build();
        ContentObserver observer = new ContentObserver(null) {
            @Override public void onChange(boolean selfChange, Uri changed) {
                assertEquals(uri, changed);
                changes.incrementAndGet();
            }
        };
        context.getContentResolver().registerContentObserver(uri, false, observer);
        Field app = RemoteConfigPublisher.class.getDeclaredField("appContext"); app.setAccessible(true);
        Field state = RemoteConfigPublisher.class.getDeclaredField("publication"); state.setAccessible(true);
        Field executor = RemoteConfigPublisher.class.getDeclaredField("IO"); executor.setAccessible(true);
        Object previousApp = app.get(null);
        Object previousState = state.get(null);
        ExecutorService io = (ExecutorService) executor.get(null);
        Method result = RemoteConfigPublisher.class.getDeclaredMethod("result", long.class, boolean.class,
                String.class, int.class);
        result.setAccessible(true);
        try {
            io.submit(() -> {}).get(5L, TimeUnit.SECONDS);
            app.set(null, context);
            state.set(null, new RemotePublicationState(0L, 0L, null, 0));
            RemoteConfigPublisher.expectVersion(10L);
            io.submit(() -> {}).get(5L, TimeUnit.SECONDS);
            assertEquals(1, changes.get());
            assertFalse(RemoteConfigPublisher.getState().isConfirmed());
            result.invoke(null, 10L, false, "COMMIT_FAILED", 1);
            io.submit(() -> {}).get(5L, TimeUnit.SECONDS);
            assertEquals(2, changes.get());
            result.invoke(null, 10L, true, null, 2);
            io.submit(() -> {}).get(5L, TimeUnit.SECONDS);
            assertEquals(3, changes.get());
            assertTrue(RemoteConfigPublisher.getState().isConfirmed());
        } finally {
            app.set(null, previousApp);
            state.set(null, previousState);
            context.getContentResolver().unregisterContentObserver(observer);
        }
    }

    @Test public void retryBudgetStopsAfterFourCompletedAttempts() {
        assertEquals(1000L, RemoteConfigPublisher.retryDelayAfterAttempt(1));
        assertEquals(3000L, RemoteConfigPublisher.retryDelayAfterAttempt(2));
        assertEquals(10000L, RemoteConfigPublisher.retryDelayAfterAttempt(3));
        assertEquals(-1L, RemoteConfigPublisher.retryDelayAfterAttempt(4));
        assertEquals(-1L, RemoteConfigPublisher.retryDelayAfterAttempt(5));
    }

    @Test public void failedCommitIsNotReportedAsAcknowledged() {
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    if ("commit".equals(method.getName())) return false;
                    return proxy;
                });
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> editor);
        assertFalse(RemoteConfigPublisher.commitPreferences(preferences, "full", "launch"));
    }

    @Test public void fullAndLaunchPoliciesUseExactlyOneEditorAndCommit() throws Exception {
        SharedPreferences stored = RuntimeEnvironment.getApplication()
                .getSharedPreferences("remote-publication-test", Context.MODE_PRIVATE);
        stored.edit().clear().putString("unrelated", "keep").commit();
        AtomicInteger edits = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!"edit".equals(method.getName())) return method.invoke(stored, args);
                    edits.incrementAndGet();
                    SharedPreferences.Editor editor = stored.edit();
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, editorMethod, editorArgs) -> {
                                if ("commit".equals(editorMethod.getName())) commits.incrementAndGet();
                                Object result = editorMethod.invoke(editor, editorArgs);
                                return result instanceof SharedPreferences.Editor ? editorProxy : result;
                            });
                });
        AppConfig app = new AppConfig("com.test.app");
        app.setHookEnabled(true);
        app.setFeatureEnabled("native_splash", false);
        JSONObject full = new JSONObject().put("version", 1).put("updated_at", 42L)
                .put("global_sdk", new GlobalConfig().toJson())
                .put("apps", new JSONObject().put(app.getPackageName(), app.toJson()));
        String launch = HookLaunchSnapshot.fromConfig(full);
        assertTrue(RemoteConfigPublisher.commitPreferences(preferences, full.toString(), launch));
        assertEquals(1, edits.get());
        assertEquals(1, commits.get());
        assertEquals("keep", stored.getString("unrelated", null));
        assertEquals(new JSONObject(stored.getString("config_json", null)).getLong("updated_at"),
                new JSONObject(stored.getString(HookLaunchSnapshot.KEY, null)).getLong("updated_at"));
        assertFalse(HookRuntimeSnapshot.parseLaunch(stored.getString(HookLaunchSnapshot.KEY, null))
                .apps.get(app.getPackageName()).isFeatureEnabled("native_splash", true));
    }
}
