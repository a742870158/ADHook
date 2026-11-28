package com.adhook.config.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 全局执行策略配置。
 *
 * 通用广告联盟 SDK 拦截不再提供全局开关：LSPosed 作用域内随 Hook 默认生效
 * （Hook 开 = 穿山甲等联盟断源 + 应用专有 Hook 全套挂载）。
 * 本地规则开关与近期任务隐藏、规则 Toast 提示独立。
 */
public class GlobalConfig {

    private volatile boolean excludeFromRecents = false;
    private volatile boolean ruleToastEnabled = false;
    private boolean localRulesEnabled = true;

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("exclude_from_recents", excludeFromRecents);
            json.put("rule_toast_enabled", ruleToastEnabled);
            json.put("local_rules_enabled", localRulesEnabled);
        } catch (JSONException ignored) {
        }
        return json;
    }

    public static GlobalConfig fromJson(JSONObject json) {
        GlobalConfig config = new GlobalConfig();
        if (json == null) return config;
        // Missing/invalid historical values never enable recents hiding or rule toast.
        config.excludeFromRecents = Boolean.TRUE.equals(json.opt("exclude_from_recents"));
        config.ruleToastEnabled = Boolean.TRUE.equals(json.opt("rule_toast_enabled"));
        config.localRulesEnabled = json.optBoolean("local_rules_enabled", true);
        return config;
    }

    public boolean isExcludeFromRecents() { return excludeFromRecents; }
    public void setExcludeFromRecents(boolean excludeFromRecents) { this.excludeFromRecents = excludeFromRecents; }

    public boolean isRuleToastEnabled() { return ruleToastEnabled; }
    public void setRuleToastEnabled(boolean ruleToastEnabled) { this.ruleToastEnabled = ruleToastEnabled; }

    public boolean isLocalRulesEnabled() { return localRulesEnabled; }
    public void setLocalRulesEnabled(boolean localRulesEnabled) { this.localRulesEnabled = localRulesEnabled; }
}
