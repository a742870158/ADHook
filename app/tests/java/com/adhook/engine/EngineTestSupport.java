package com.adhook.engine;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import com.adhook.config.ConfigClient;
import com.adhook.config.model.AppConfig;
import com.adhook.config.model.GlobalConfig;
import com.adhook.core.FeatureGuard;
import com.adhook.core.ViewCollapser;

import org.junit.After;
import org.junit.Before;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

/** Uses real Android Views; only the config transport and unavailable LSPosed native hook are isolated. */
abstract class EngineTestSupport {
    static final String PACKAGE = "com.adhook.test.host";
    ActivityController<Activity> controller;
    Activity activity;
    FrameLayout root;
    AppConfig app;
    GlobalConfig global;
    private boolean hookInstalled;

    @Before public void setUpEngine() throws Exception {
        RuleTreeTraversal.cancelAll();
        // Robolectric recreates the main queue between methods but retains this module's statics.
        // A coalesced refresh from the previous queue must not suppress this application lifetime.
        ((java.util.concurrent.atomic.AtomicBoolean) getStatic(CustomRuleEngine.class, "sRefreshPosted")).set(false);
        // Standalone policy fixtures do not install native observers; clear readiness from prior test lifetimes.
        setStatic(CustomRuleEngine.class, "sInstallAttempted", false);
        setStatic(CustomRuleEngine.class, "sObserversReady", false);
        hookInstalled = (boolean) getStatic(ViewCollapser.class, "visibilityHookInstalled");
        setStatic(ViewCollapser.class, "visibilityHookInstalled", true);
        setStatic(FeatureGuard.class, "sCurrentPackage", null);
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        root = new FrameLayout(activity);
        activity.setContentView(root);
        app = new AppConfig(PACKAGE);
        app.setHookEnabled(true);
        global = new GlobalConfig();
        publish();
        CustomRuleEngine.applyToActivity(PACKAGE, activity);
    }

    @After public void tearDownEngine() throws Exception {
        app.getCustomRules().clear();
        publish();
        CustomRuleEngine.releaseForReuse(root);
        controller.pause().stop().destroy();
        ((Map<?, ?>) getStatic(CustomRuleEngine.class, "sKnownRoots")).clear();
        drain();
        setStatic(ViewCollapser.class, "visibilityHookInstalled", hookInstalled);
    }

    @SuppressWarnings("unchecked")
    void publish() throws Exception {
        // Publish the same immutable runtime snapshot used by callbacks; no transport/TTL read.
        Class<?> runtimeSnapshot = Class.forName("com.adhook.config.HookRuntimeSnapshot");
        java.lang.reflect.Method parse = runtimeSnapshot.getDeclaredMethod("parseFull", org.json.JSONObject.class);
        parse.setAccessible(true);
        org.json.JSONObject snapshot = new org.json.JSONObject()
                .put("version", 1).put("updated_at", 1L)
                .put("global_sdk", global.toJson())
                .put("apps", new org.json.JSONObject().put(PACKAGE, app.toJson()));
        Object parsed = parse.invoke(null, snapshot);
        org.junit.Assert.assertNotNull("Engine fixture must publish a valid full runtime snapshot", parsed);
        setStatic(ConfigClient.class, "sHookRuntime", parsed);
        // Legacy fixtures still inspect these caches; keep them consistent without calling getters.
        setStatic(ConfigClient.class, "sGlobalConfig", global);
        setStatic(ConfigClient.class, "sGlobalConfigTimestamp", Long.MAX_VALUE);
        setStatic(ConfigClient.class, "sGlobalFallback", false);
        ((Map<String, AppConfig>) getStatic(ConfigClient.class, "sAppConfigCache")).put(PACKAGE, app);
        ((Map<String, Long>) getStatic(ConfigClient.class, "sCacheTimestamps")).put(PACKAGE, Long.MAX_VALUE);
        ((Set<String>) getStatic(ConfigClient.class, "sFallbackPackages")).remove(PACKAGE);
        CustomRuleEngine.notifyRulesChanged();
        drain();
    }

    void drain() {
        // Rule traversal now continues on animation frames, not solely the current message queue.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(250, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    FrameLayout row() {
        FrameLayout row = new FrameLayout(activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(240, 120);
        params.setMargins(3, 7, 5, 11);
        root.addView(row, params);
        row.setMinimumHeight(23);
        return row;
    }

    static Object getStatic(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    static void setStatic(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
