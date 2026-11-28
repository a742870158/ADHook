package com.adhook.config;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class HookLaunchSnapshotTest {
    @Test public void mirrorsDefaultOnHookAndIgnoresLegacyOptOut() throws Exception {
        JSONObject apps = new JSONObject()
                .put("com.enabled.app", new JSONObject().put("hookEnabled", true))
                .put("com.disabled.app", new JSONObject().put("hookEnabled", false))
                .put("com.legacy.app", new JSONObject().put("inspectorEnabled", true));
        String json = HookLaunchSnapshot.fromConfig(new JSONObject().put("updated_at", 123L).put("apps", apps));
        assertEquals(Boolean.TRUE, HookLaunchSnapshot.enabled(json, "com.enabled.app"));
        assertEquals(Boolean.TRUE, HookLaunchSnapshot.enabled(json, "com.disabled.app"));
        assertEquals(Boolean.TRUE, HookLaunchSnapshot.enabled(json, "com.legacy.app"));
        assertEquals(Boolean.FALSE, HookLaunchSnapshot.enabled(json, "com.missing.app"));
        assertEquals(HookLaunchSnapshot.VERSION, new JSONObject(json).getInt("version"));
        assertTrue(new JSONObject(json).getJSONObject("apps").getJSONObject("com.enabled.app")
                .get("nativeFeatures") instanceof JSONObject);
    }

    @Test public void unavailableAndMalformedSnapshotsAreUnknownNotExplicitOff() {
        assertNull(HookLaunchSnapshot.enabled(null, "com.test.app"));
        assertNull(HookLaunchSnapshot.enabled("{}", "com.test.app"));
        assertNull(HookLaunchSnapshot.enabled("{broken", "com.test.app"));
        assertNull(HookLaunchSnapshot.enabled("{\"version\":1,\"updated_at\":1,\"apps\":{\"com.test.app\":\"true\"}}", "com.test.app"));
    }
}
