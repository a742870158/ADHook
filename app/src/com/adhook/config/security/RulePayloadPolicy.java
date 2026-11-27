package com.adhook.config.security;

import com.adhook.config.model.CustomRule;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.Arrays;

/** Validate raw types before tolerant model deserialization can drop or coerce constraints. */
public final class RulePayloadPolicy {
    public static final int MAX_JSON_LENGTH = 16 * 1024;
    private RulePayloadPolicy() {}

    public static CustomRule parse(String json) {
        if (json == null || json.isEmpty() || json.length() > MAX_JSON_LENGTH) return null;
        try {
            JSONTokener reader = new JSONTokener(json);
            Object root = reader.nextValue();
            if (!(root instanceof JSONObject) || reader.nextClean() != 0) return null;
            JSONObject raw = (JSONObject) root;
            if (raw.length() > 24) return null;
            for (String key : new String[]{"id", "name", "type", "targetValue", "action", "source", "activityFilter",
                    "textProperty", "textMatchMode", "ruleNamespace", "subscriptionId", "subscriptionPackage", "externalRuleId"}) {
                if (raw.has(key) && !(raw.get(key) instanceof String)) return null;
            }
            for (String key : new String[]{"enabled", "trimText", "exactActivityFilter", "exactResourceId"}) {
                if (raw.has(key) && !(raw.get(key) instanceof Boolean)) return null;
            }
            if (!integerInRange(raw, "parentDepth", -1, 100) || !integerInRange(raw, "maxTextLength", 0, 2000)
                    || !integerInRange(raw, "createTime", 0, Long.MAX_VALUE)) return null;
            CustomRule rule = CustomRule.fromJson(raw);
            if (rule == null || !ConfigInputPolicy.isRuleId(rule.getId())) return null;
            if (!Arrays.asList("res_id", "class_name", "text").contains(rule.getType())
                    || !"collapse".equals(rule.getAction())) return null;
            if (rule.getTargetValue() == null || rule.getTargetValue().trim().isEmpty() || rule.getTargetValue().length() > 2000) return null;
            if (rule.getName() != null && rule.getName().length() > 200) return null;
            if (rule.getActivityFilter() != null && rule.getActivityFilter().length() > 512) return null;
            if (!Arrays.asList("any", "text", "desc").contains(rule.getTextProperty())
                    || !Arrays.asList("contains", "equals").contains(rule.getTextMatchMode())) return null;
            return rule;
        } catch (Exception | StackOverflowError ignored) {
            return null;
        }
    }

    private static boolean integerInRange(JSONObject json, String key, long minimum, long maximum) throws org.json.JSONException {
        if (!json.has(key)) return true;
        Object value = json.get(key);
        if (!(value instanceof Integer || value instanceof Long)) return false;
        long number = ((Number) value).longValue();
        return number >= minimum && number <= maximum;
    }
}
