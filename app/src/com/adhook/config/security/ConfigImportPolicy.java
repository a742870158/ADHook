package com.adhook.config.security;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Normalize all supported local-import formats, rejecting the whole input before any mutation. */
public final class ConfigImportPolicy {
    public static final int MAX_JSON_LENGTH = 16 * 1024 * 1024;
    private ConfigImportPolicy() {}

    /** Strings/escapes are lexical data, not structural nesting; bound recursion before JSONTokener runs. */
    private static boolean hasBoundedNesting(String text) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{' || c == '[') {
                if (++depth > 64) return false;
            } else if (c == '}' || c == ']') {
                if (--depth < 0) return false;
            }
        }
        return depth == 0 && quote == 0;
    }

    /** Shared lexical/size boundary for local imports and downloaded subscription JSON. */
    public static JSONObject parseObject(String text) throws JSONException {
        if (text == null || text.length() > MAX_JSON_LENGTH || text.trim().isEmpty()) {
            throw new IllegalArgumentException("规则内容为空或过大");
        }
        if (!hasBoundedNesting(text)) throw new IllegalArgumentException("规则JSON嵌套超限或不完整");
        JSONTokener reader = new JSONTokener(text);
        Object parsed = reader.nextValue();
        if (!(parsed instanceof JSONObject) || reader.nextClean() != 0) {
            throw new IllegalArgumentException("规则必须是完整JSON对象");
        }
        return (JSONObject) parsed;
    }

    public static JSONObject normalize(String text) throws JSONException {
        JSONObject raw = parseObject(text);
        JSONObject apps = new JSONObject();
        JSONObject global = new JSONObject();
        if (raw.opt("apps") instanceof JSONArray) {
            JSONArray input = raw.getJSONArray("apps");
            if (input.length() > 2000) throw new IllegalArgumentException("应用数量超限");
            for (int i = 0; i < input.length(); i++) {
                JSONObject app = input.getJSONObject(i);
                Object packageValue = app.has("id") ? app.get("id") : app.opt("packageName");
                if (!(packageValue instanceof String) || !ConfigInputPolicy.isPackageName((String) packageValue)
                        || apps.has((String) packageValue)) throw new IllegalArgumentException("订阅应用身份无效或重复");
                String pkg = (String) packageValue;
                JSONArray rules = app.getJSONArray("rules");
                apps.put(pkg, new JSONObject().put("packageName", pkg).put("customRules", rules));
            }
        } else if ("adhook_app_rules".equals(raw.opt("type")) || raw.has("package")) {
            Object packageValue = raw.get("package");
            if (!(packageValue instanceof String) || !ConfigInputPolicy.isPackageName((String) packageValue)) {
                throw new IllegalArgumentException("应用包名无效");
            }
            String pkg = (String) packageValue;
            JSONObject app = raw.has("data") ? raw.getJSONObject("data") : raw;
            if (app.has("packageName") && !pkg.equals(app.get("packageName"))) {
                throw new IllegalArgumentException("应用身份不一致");
            }
            apps.put(pkg, app);
        } else {
            apps = raw.getJSONObject("apps");
            if (raw.has("global_sdk")) global = raw.getJSONObject("global_sdk");
        }
        JSONObject normalized = new JSONObject().put("apps", apps).put("global_sdk", global);
        if (!ConfigSnapshotPolicy.isValid(normalized)) throw new IllegalArgumentException("规则配置无效，未导入任何条目");
        java.util.Iterator<String> packages = apps.keys();
        while (packages.hasNext()) {
            JSONArray rules = apps.getJSONObject(packages.next()).optJSONArray("customRules");
            if (rules == null) continue;
            for (int i = 0; i < rules.length(); i++) {
                if (RulePayloadPolicy.parse(rules.getJSONObject(i).toString()) == null) {
                    throw new IllegalArgumentException("规则类型或约束无效，未导入任何条目");
                }
            }
        }
        return normalized;
    }
}
