package com.adhook.config;

import org.json.JSONException;
import org.json.JSONObject;

/** Compact preferences mirror: switches and native policy travel together, never custom rules. */
public final class HookLaunchSnapshot {
    public static final String KEY = "hook_launch_json";
    public static final int VERSION = 2;

    private HookLaunchSnapshot() {}

    public static String fromConfig(JSONObject config) throws JSONException {
        JSONObject switches = new JSONObject();
        JSONObject apps = config.getJSONObject("apps");
        java.util.Iterator<String> names = apps.keys();
        while (names.hasNext()) {
            String name = names.next();
            JSONObject app = apps.getJSONObject(name);
            JSONObject features = app.optJSONObject("nativeFeatures");
            switches.put(name, new JSONObject().put("packageName", name)
                    .put("hookEnabled", app.optBoolean("hookEnabled", false))
                    .put("inspectorEnabled", app.optBoolean("inspectorEnabled", false))
                    .put("nativeFeatures", features == null ? new JSONObject() : features));
        }
        JSONObject global = config.optJSONObject("global_sdk");
        return new JSONObject().put("version", VERSION)
                .put("updated_at", config.getLong("updated_at"))
                .put("global_sdk", global == null ? new JSONObject() : global)
                .put("apps", switches).toString();
    }

    /** Old switch-only mirrors cannot authorize default native features; they remain UNKNOWN. */
    public static Boolean enabled(String json, String packageName) {
        HookRuntimeSnapshot snapshot = HookRuntimeSnapshot.parseLaunch(json);
        if (snapshot == null || packageName == null) return null;
        com.adhook.config.model.AppConfig app = snapshot.apps.get(packageName);
        return app != null && app.isHookEnabled();
    }
}
