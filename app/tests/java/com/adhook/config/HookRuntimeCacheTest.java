package com.adhook.config;

import com.adhook.config.model.AppConfig;
import com.adhook.config.model.GlobalConfig;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class HookRuntimeCacheTest {
    private Field runtime;
    private Object previous;
    private static final String PKG = "com.runtime.test";

    @Before public void setUp() throws Exception {
        runtime = ConfigClient.class.getDeclaredField("sHookRuntime");
        runtime.setAccessible(true);
        previous = runtime.get(null);
        runtime.set(null, HookRuntimeSnapshot.empty());
    }

    @After public void tearDown() throws Exception {
        runtime.set(null, previous);
        Field legacy = ConfigClient.class.getDeclaredField("sAppConfigCache");
        legacy.setAccessible(true);
        ((Map<?, ?>) legacy.get(null)).clear();
    }

    @Test public void unknownNeverConsumesLegacyTtlOrDefaultFeatureCaches() throws Exception {
        AppConfig stale = new AppConfig(PKG);
        stale.setHookEnabled(true);
        Field legacy = ConfigClient.class.getDeclaredField("sAppConfigCache");
        legacy.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, AppConfig> cache = (Map<String, AppConfig>) legacy.get(null);
        cache.put(PKG, stale);
        assertFalse(ConfigClient.isHookEnabledCached(PKG));
        assertNull(ConfigClient.getAppConfigCached(PKG));
        assertFalse(ConfigClient.getGlobalConfigCached().isLocalRulesEnabled());
    }

    @Test public void oldPrivatePreferencesCannotAuthorizeStartupOrSupplyTheFullSnapshot() throws Exception {
        android.content.SharedPreferences oldMirror = org.robolectric.RuntimeEnvironment.getApplication()
                .getSharedPreferences("adhook_config", android.content.Context.MODE_PRIVATE);
        String previousLaunch = oldMirror.getString(HookLaunchSnapshot.KEY, null);
        String previousFull = oldMirror.getString("config_json", null);
        AppConfig app = new AppConfig(PKG);
        app.setHookEnabled(true);
        JSONObject full = new JSONObject().put("version", 1).put("updated_at", 101L)
                .put("global_sdk", new GlobalConfig().toJson()).put("apps", new JSONObject().put(PKG, app.toJson()));
        Field module = ConfigClient.class.getDeclaredField("sXposedModule");
        module.setAccessible(true);
        Object previousModule = module.get(null);
        Field cache = ConfigClient.class.getDeclaredField("sSnapshotCache");
        cache.setAccessible(true);
        Object previousCache = cache.get(null);
        try {
            module.set(null, null);
            cache.set(null, null);
            assertTrue(oldMirror.edit().putString("config_json", full.toString())
                    .putString(HookLaunchSnapshot.KEY, HookLaunchSnapshot.fromConfig(full)).commit());
            java.lang.reflect.Method launch = ConfigClient.class.getDeclaredMethod("readTrustedLaunchJson");
            launch.setAccessible(true);
            assertNull(launch.invoke(null));
            java.lang.reflect.Method snapshot = ConfigClient.class.getDeclaredMethod("readSnapshot");
            snapshot.setAccessible(true);
            assertNull(snapshot.invoke(null));
            java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                assertNull(worker.submit(() -> snapshot.invoke(null)).get(5L, java.util.concurrent.TimeUnit.SECONDS));
            } finally {
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(5L, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertFalse(ConfigClient.isHookEnabledCached(PKG));
        } finally {
            module.set(null, previousModule);
            cache.set(null, previousCache);
            oldMirror.edit().putString("config_json", previousFull)
                    .putString(HookLaunchSnapshot.KEY, previousLaunch).commit();
        }
    }

    @Test public void saveRuleFacadeRejectsMainThreadBeforeTouchingCachesOrProvider() throws Exception {
        Field cache = ConfigClient.class.getDeclaredField("sAppConfigCache");
        cache.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, AppConfig> apps = (Map<String, AppConfig>) cache.get(null);
        AppConfig retained = new AppConfig(PKG);
        apps.put(PKG, retained);
        com.adhook.config.model.CustomRule rule = new com.adhook.config.model.CustomRule("ad",
                com.adhook.config.model.CustomRule.TYPE_RES_ID, "ad_container",
                com.adhook.config.model.CustomRule.ACTION_COLLAPSE, 0);
        assertFalse(ConfigClient.saveCustomRule(PKG, rule));
        assertSame(retained, apps.get(PKG));
    }

    @Test public void cachedApisReadTheOnePublishedSnapshotWithoutSourceRefresh() throws Exception {
        AppConfig app = new AppConfig(PKG);
        app.setHookEnabled(true);
        app.setFeatureEnabled("native_splash", false);
        GlobalConfig global = new GlobalConfig();
        JSONObject root = new JSONObject().put("version", 1).put("updated_at", 1L)
                .put("global_sdk", global.toJson()).put("apps", new JSONObject().put(PKG, app.toJson()));
        HookRuntimeSnapshot snapshot = HookRuntimeSnapshot.parseFull(root);
        runtime.set(null, snapshot);
        for (int i = 0; i < 100; i++) {
            assertTrue(ConfigClient.isHookEnabledCached(PKG));
            assertSame(snapshot.apps.get(PKG), ConfigClient.getAppConfigCached(PKG));
            assertSame(snapshot.global, ConfigClient.getGlobalConfigCached());
            assertFalse(ConfigClient.getAppConfigCached(PKG).isFeatureEnabled("native_splash", true));
        }
        assertFalse(ConfigClient.isHookEnabledCached("com.unknown.app"));
        assertFalse(ConfigClient.isHookEnabledCached(null));
    }
}
