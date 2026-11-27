package com.adhook.config.security;

import com.adhook.config.model.AppConfig;
import com.adhook.config.model.CustomRule;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;

/** Full-snapshot preflight used before backup restore performs any write or manager initialization. */
public final class ConfigSnapshotPolicy {
    private ConfigSnapshotPolicy() {}

    private static boolean validRuleScalars(JSONObject raw) throws org.json.JSONException {
        for (String key : new String[]{"id", "name", "type", "targetValue", "action", "source", "activityFilter",
                "textProperty", "textMatchMode", "ruleNamespace", "subscriptionId", "subscriptionPackage", "externalRuleId"}) {
            if (raw.has(key) && !(raw.get(key) instanceof String)) return false;
        }
        for (String key : new String[]{"enabled", "trimText", "exactActivityFilter", "exactResourceId"}) {
            if (raw.has(key) && !(raw.get(key) instanceof Boolean)) return false;
        }
        for (String key : new String[]{"parentDepth", "maxTextLength", "createTime"}) {
            if (!raw.has(key)) continue;
            Object value = raw.get(key);
            if (!(value instanceof Integer || value instanceof Long)) return false;
            long number = ((Number) value).longValue();
            if (number < ("parentDepth".equals(key) ? -1 : 0)) return false;
            if (!"createTime".equals(key) && number > Integer.MAX_VALUE) return false;
        }
        return true;
    }

    public static boolean isValid(JSONObject root) {
        if (root == null || !(root.opt("global_sdk") instanceof JSONObject) || !(root.opt("apps") instanceof JSONObject)) return false;
        try {
            JSONObject global = root.getJSONObject("global_sdk");
            Iterator<String> keys = global.keys();
            while (keys.hasNext()) if (!(global.get(keys.next()) instanceof Boolean)) return false;
            JSONObject apps = root.getJSONObject("apps");
            if (apps.length() > 2000) return false;
            keys = apps.keys();
            int totalRules = 0;
            while (keys.hasNext()) {
                String pkg = keys.next();
                if (!ConfigInputPolicy.isPackageName(pkg)) return false;
                JSONObject app = apps.getJSONObject(pkg);
                if (app.has("packageName") && !pkg.equals(app.get("packageName"))) return false;
                if (app.has("inspectorEnabled") && !(app.get("inspectorEnabled") instanceof Boolean)) return false;
                if (app.has("hookEnabled") && !(app.get("hookEnabled") instanceof Boolean)) return false;
                if (app.has("nativeFeatures")) {
                    JSONObject features = app.getJSONObject("nativeFeatures");
                    if (features.length() > 256) return false;
                    Iterator<String> featureIds = features.keys();
                    while (featureIds.hasNext()) {
                        String feature = featureIds.next();
                        if (!ConfigInputPolicy.isFeatureId(feature) || !(features.get(feature) instanceof Boolean)) return false;
                    }
                }
                if (app.has("customRules")) {
                    JSONArray rules = app.getJSONArray("customRules");
                    totalRules += rules.length();
                    if (rules.length() > 10000 || totalRules > 50000) return false;
                    for (int i = 0; i < rules.length(); i++) {
                        JSONObject raw = rules.getJSONObject(i);
                        if (!validRuleScalars(raw)) return false;
                        CustomRule rule = CustomRule.fromJson(raw);
                        if (rule == null || !ConfigInputPolicy.isRuleId(rule.getId())
                                || rule.getTargetValue() == null || rule.getTargetValue().isEmpty()
                                || rule.getTargetValue().length() > 2000) return false;
                        if (!(CustomRule.TYPE_TEXT.equals(rule.getType()) || CustomRule.TYPE_RES_ID.equals(rule.getType())
                                || CustomRule.TYPE_CLASS_NAME.equals(rule.getType()))) return false;
                        if (!CustomRule.ACTION_COLLAPSE.equals(rule.getAction())) return false;
                        if (!java.util.Arrays.asList("any", "text", "desc").contains(rule.getTextProperty())
                                || !java.util.Arrays.asList("equals", "contains").contains(rule.getTextMatchMode())) return false;
                        if (rule.getMaxTextLength() < 0 || rule.getParentDepth() < -1) return false;
                    }
                }
                if (AppConfig.fromJson(app) == null) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
