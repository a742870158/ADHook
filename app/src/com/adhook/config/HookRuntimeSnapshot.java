package com.adhook.config;

import com.adhook.config.model.AppConfig;
import com.adhook.config.model.GlobalConfig;
import com.adhook.config.security.ConfigInputPolicy;
import com.adhook.config.security.ConfigSnapshotPolicy;

import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** One atomically published, read-only-by-contract host policy; no source reads in its getters. */
final class HookRuntimeSnapshot {
    final long updatedAt;
    final boolean complete;
    final GlobalConfig global;
    final Map<String, AppConfig> apps;
    final Map<String, Long> appVersions;
    /** Package-scoped Provider responses may be complete while the whole root is not. */
    final Set<String> completePackages;

    HookRuntimeSnapshot(long updatedAt, boolean complete, GlobalConfig global, Map<String, AppConfig> apps) {
        this(updatedAt, complete, global, apps, versions(updatedAt, apps),
                complete ? apps.keySet() : Collections.emptySet());
    }

    HookRuntimeSnapshot(long updatedAt, boolean complete, GlobalConfig global, Map<String, AppConfig> apps,
                        Map<String, Long> appVersions, Set<String> completePackages) {
        this.updatedAt = updatedAt;
        this.complete = complete;
        this.global = global;
        this.apps = Collections.unmodifiableMap(new LinkedHashMap<>(apps));
        this.appVersions = Collections.unmodifiableMap(new LinkedHashMap<>(appVersions));
        this.completePackages = Collections.unmodifiableSet(new LinkedHashSet<>(completePackages));
    }

    boolean isPackageComplete(String packageName) {
        // A coherent full root also proves that absent packages have no configured rules.
        return complete || completePackages.contains(packageName);
    }

    private static Map<String, Long> versions(long version, Map<String, AppConfig> apps) {
        Map<String, Long> versions = new LinkedHashMap<>();
        for (String pkg : apps.keySet()) versions.put(pkg, version);
        return versions;
    }

    static HookRuntimeSnapshot empty() {
        GlobalConfig global = new GlobalConfig();
        global.setLocalRulesEnabled(false);
        return new HookRuntimeSnapshot(0L, false, global, Collections.emptyMap());
    }

    static HookRuntimeSnapshot parseLaunch(String json) {
        if (json == null || json.length() > 2 * 1024 * 1024) return null;
        try {
            JSONObject root = new JSONObject(json);
            if (!(root.opt("version") instanceof Integer)
                    || root.getInt("version") != HookLaunchSnapshot.VERSION) return null;
            JSONObject apps = root.getJSONObject("apps");
            Iterator<String> names = apps.keys();
            while (names.hasNext()) {
                JSONObject app = apps.getJSONObject(names.next());
                // Presence matters: a switch-only snapshot would incorrectly use native defaults.
                if (!(app.opt("hookEnabled") instanceof Boolean)
                        || !(app.opt("nativeFeatures") instanceof JSONObject)) return null;
            }
            return parse(root, false);
        } catch (Exception ignored) {
            return null;
        }
    }

    static HookRuntimeSnapshot parseFull(JSONObject root) {
        if (root == null || !(root.opt("version") instanceof Integer) || root.optInt("version") != 1) return null;
        return parse(root, true);
    }

    private static HookRuntimeSnapshot parse(JSONObject root, boolean complete) {
        if (!ConfigSnapshotPolicy.isValid(root)) return null;
        try {
            Object version = root.opt("updated_at");
            if (!(version instanceof Integer || version instanceof Long) || ((Number) version).longValue() <= 0L) return null;
            Map<String, AppConfig> configs = new LinkedHashMap<>();
            JSONObject apps = root.getJSONObject("apps");
            Iterator<String> names = apps.keys();
            while (names.hasNext()) {
                String pkg = names.next();
                JSONObject raw = apps.getJSONObject(pkg);
                if (!ConfigInputPolicy.isPackageName(pkg)
                        || (raw.has("hookEnabled") && !(raw.opt("hookEnabled") instanceof Boolean))) return null;
                AppConfig app = AppConfig.fromJson(raw);
                app.setPackageName(pkg);
                configs.put(pkg, app);
            }
            return new HookRuntimeSnapshot(((Number) version).longValue(), complete,
                    GlobalConfig.fromJson(root.getJSONObject("global_sdk")), configs);
        } catch (Exception ignored) {
            return null;
        }
    }
}
