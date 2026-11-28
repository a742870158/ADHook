package com.adhook.config.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个宿主应用的去广告与拾取器配置。
 */
public class AppConfig {

    private String packageName;
    private boolean inspectorEnabled = false;
    /** Only used by internal tests to exercise the legacy disabled branch; production is always on. */
    private Boolean hookEnabled = null;
    private final Map<String, Boolean> nativeFeatures = new LinkedHashMap<>();
    private final List<CustomRule> customRules = new ArrayList<>();

    public AppConfig() {
    }

    public AppConfig(String packageName) {
        this.packageName = packageName;
    }

    public boolean isFeatureEnabled(String featureId, boolean defaultValue) {
        Boolean val = nativeFeatures.get(featureId);
        return val != null ? val : defaultValue;
    }

    public void setFeatureEnabled(String featureId, boolean enabled) {
        nativeFeatures.put(featureId, enabled);
    }

    public void addCustomRule(CustomRule rule) {
        if (rule == null) return;
        // 如果同 ID 已存在则替换，否则追加
        for (int i = 0; i < customRules.size(); i++) {
            if (customRules.get(i) != null && rule.getId() != null
                    && rule.getId().equals(customRules.get(i).getId())) {
                customRules.set(i, rule);
                return;
            }
        }
        customRules.add(rule);
    }

    public boolean removeCustomRule(String ruleId) {
        if (ruleId == null) return false;
        return customRules.removeIf(r -> ruleId.equals(r.getId()));
    }

    public List<CustomRule> getRulesBySource(String source) {
        List<CustomRule> list = new ArrayList<>();
        if (source == null) return list;
        for (CustomRule r : customRules) {
            if (source.equals(r.getSource())) {
                list.add(r);
            }
        }
        return list;
    }

    public List<CustomRule> getPickerRules() {
        List<CustomRule> list = new ArrayList<>();
        for (CustomRule r : customRules) {
            if (r.getSource() == null || CustomRule.SOURCE_PICKER.equals(r.getSource())) {
                list.add(r);
            }
        }
        return list;
    }

    public boolean removeRulesBySource(String source) {
        if (source == null) return false;
        return customRules.removeIf(r -> source.equals(r.getSource()));
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("packageName", packageName);
            json.put("inspectorEnabled", inspectorEnabled);
            // Compatibility: Hook has no user switch anymore, but older readers still expect the field.
            json.put("hookEnabled", true);

            JSONObject features = new JSONObject();
            for (Map.Entry<String, Boolean> entry : nativeFeatures.entrySet()) {
                features.put(entry.getKey(), entry.getValue());
            }
            json.put("nativeFeatures", features);

            JSONArray rules = new JSONArray();
            for (CustomRule r : customRules) {
                if (r != null) rules.put(r.toJson());
            }
            json.put("customRules", rules);
        } catch (JSONException ignored) {
        }
        return json;
    }

    public static AppConfig fromJson(JSONObject json) {
        if (json == null) return null;
        AppConfig config = new AppConfig();
        config.packageName = json.optString("packageName", "");
        config.inspectorEnabled = json.optBoolean("inspectorEnabled", false);
        // Legacy hookEnabled values are intentionally ignored; the current policy is always on.

        JSONObject features = json.optJSONObject("nativeFeatures");
        if (features != null) {
            Iterator<String> keys = features.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                config.nativeFeatures.put(k, features.optBoolean(k, true));
            }
        }

        JSONArray rules = json.optJSONArray("customRules");
        if (rules != null) {
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rObj = rules.optJSONObject(i);
                if (rObj != null) {
                    CustomRule rule = CustomRule.fromJson(rObj);
                    if (rule != null) {
                        config.customRules.add(rule);
                    }
                }
            }
        }
        return config;
    }

    /** Detached deep copy; callers holding ConfigManager's monitor get a consistent snapshot. */
    public AppConfig copy() {
        AppConfig copy = new AppConfig(packageName);
        copy.inspectorEnabled = inspectorEnabled;
        copy.hookEnabled = hookEnabled;
        copy.nativeFeatures.putAll(nativeFeatures);
        for (CustomRule rule : customRules) {
            if (rule != null) copy.customRules.add(rule.copy());
        }
        return copy;
    }

    public List<CustomRule> getRemoteRules() {
        return getRulesBySource("remote");
    }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public boolean isInspectorEnabled() { return inspectorEnabled; }
    public void setInspectorEnabled(boolean inspectorEnabled) { this.inspectorEnabled = inspectorEnabled; }

    /**
     * 作用域内 Hook 默认执行；不再有每应用用户开关。
     *
     * 持久化的 {@code hookEnabled=false} 属于旧版本遗留值，加载时被忽略。保留 public
     * setter 仅为插件 ABI 兼容与内部测试覆盖禁用分支，生产入口不调用。
     */
    public boolean isHookEnabled() {
        return hookEnabled == null || hookEnabled;
    }

    public Boolean getRawHookEnabled() { return hookEnabled; }
    public void setHookEnabled(Boolean hookEnabled) { this.hookEnabled = hookEnabled; }

    public Map<String, Boolean> getNativeFeatures() { return nativeFeatures; }
    public List<CustomRule> getCustomRules() { return customRules; }
}
