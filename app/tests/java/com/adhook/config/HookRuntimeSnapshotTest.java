package com.adhook.config;

import com.adhook.config.model.AppConfig;
import com.adhook.config.model.GlobalConfig;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class HookRuntimeSnapshotTest {
    private JSONObject full(AppConfig app, GlobalConfig global) throws Exception {
        return new JSONObject().put("version", 1).put("updated_at", 123L)
                .put("global_sdk", global.toJson())
                .put("apps", new JSONObject().put(app.getPackageName(), app.toJson()));
    }

    @Test public void launchPreservesNativeOptOutBeforeCompleteRulesAreAvailable() throws Exception {
        AppConfig app = new AppConfig("com.test.app");
        app.setHookEnabled(true);
        app.setFeatureEnabled("native_splash", false);
        app.setInspectorEnabled(true);
        GlobalConfig global = new GlobalConfig();
        global.setLocalRulesEnabled(false);
        HookRuntimeSnapshot snapshot = HookRuntimeSnapshot.parseLaunch(
                HookLaunchSnapshot.fromConfig(full(app, global)));
        assertNotNull(snapshot);
        assertFalse(snapshot.complete);
        assertFalse(snapshot.isPackageComplete("com.test.app"));
        assertTrue(snapshot.apps.get("com.test.app").isHookEnabled());
        assertFalse(snapshot.apps.get("com.test.app").isFeatureEnabled("native_splash", true));
        assertTrue(snapshot.apps.get("com.test.app").isInspectorEnabled());
        assertFalse(snapshot.global.isLocalRulesEnabled());
        assertEquals(123L, snapshot.updatedAt);
    }

    @Test public void legacyOptOutIsIgnoredAndHookRemainsOn() throws Exception {
        for (boolean legacy : new boolean[]{false, true}) {
            AppConfig app = new AppConfig("com.test.app");
            app.setHookEnabled(legacy);
            app.setFeatureEnabled("native_splash", false);
            JSONObject root = full(app, new GlobalConfig());
            HookRuntimeSnapshot complete = HookRuntimeSnapshot.parseFull(root);
            HookRuntimeSnapshot launch = HookRuntimeSnapshot.parseLaunch(HookLaunchSnapshot.fromConfig(root));
            assertNotNull(complete);
            assertNotNull(launch);
            assertTrue(complete.apps.get("com.test.app").isHookEnabled());
            assertTrue(launch.apps.get("com.test.app").isHookEnabled());
            assertFalse(launch.apps.get("com.test.app").isFeatureEnabled("native_splash", true));
            assertFalse(new JSONObject(HookLaunchSnapshot.fromConfig(root)).getJSONObject("apps")
                    .getJSONObject("com.test.app").has("customRules"));
        }
    }

    @Test public void fullRootProvesBothPresentAndAbsentPackagesWhileLaunchProvesNeitherComplete() throws Exception {
        AppConfig app = new AppConfig("com.test.app");
        JSONObject root = full(app, new GlobalConfig());
        HookRuntimeSnapshot complete = HookRuntimeSnapshot.parseFull(root);
        HookRuntimeSnapshot launch = HookRuntimeSnapshot.parseLaunch(HookLaunchSnapshot.fromConfig(root));
        assertTrue(complete.isPackageComplete("com.test.app"));
        assertTrue(complete.isPackageComplete("com.missing.app"));
        assertFalse(launch.isPackageComplete("com.test.app"));
        assertFalse(launch.isPackageComplete("com.missing.app"));
        assertFalse(HookRuntimeSnapshot.empty().isPackageComplete("com.test.app"));
    }

    @Test public void unknownAndLegacySwitchOnlyNeverEnableNativeDefaults() {
        assertTrue(HookRuntimeSnapshot.empty().apps.isEmpty());
        assertFalse(HookRuntimeSnapshot.empty().global.isLocalRulesEnabled());
        assertNull(HookRuntimeSnapshot.parseLaunch("{\"version\":1,\"updated_at\":1,\"apps\":{\"com.test.app\":true}}"));
        assertNull(HookRuntimeSnapshot.parseLaunch("{\"version\":2,\"updated_at\":1,\"global_sdk\":{},\"apps\":{\"com.test.app\":{\"hookEnabled\":true}}}"));
    }

    @Test public void invalidToggleOrNativePreferenceRejectsTheWholePolicy() throws Exception {
        JSONObject root = full(new AppConfig("com.test.app"), new GlobalConfig());
        root.getJSONObject("apps").getJSONObject("com.test.app").put("hookEnabled", "true");
        assertNull(HookRuntimeSnapshot.parseFull(root));
        root.getJSONObject("apps").getJSONObject("com.test.app").put("hookEnabled", true)
                .getJSONObject("nativeFeatures").put("native_splash", "false");
        assertNull(HookRuntimeSnapshot.parseFull(root));
    }

    @Test public void readOnlySnapshotDoesNotCreateOrMigrateIndependentSwitches() throws Exception {
        AppConfig app = new AppConfig("com.test.app");
        app.setInspectorEnabled(true);
        JSONObject root = full(app, new GlobalConfig());
        String before = root.toString();
        HookRuntimeSnapshot snapshot = HookRuntimeSnapshot.parseFull(root);
        assertNotNull(snapshot);
        assertTrue(snapshot.apps.get("com.test.app").isInspectorEnabled());
        assertTrue(snapshot.apps.get("com.test.app").isHookEnabled());
        assertEquals(before, root.toString());
        assertNull(app.getRawHookEnabled());
        assertNull(snapshot.apps.get("com.missing.app"));
    }
}
