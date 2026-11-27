package com.adhook.config.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * 自定义 UI 去广告规则模型。
 */
public class CustomRule {

    public static final String TYPE_RES_ID = "res_id";
    public static final String TYPE_CLASS_NAME = "class_name";
    public static final String TYPE_TEXT = "text";

    public static final String ACTION_COLLAPSE = "collapse";

    public static final String SOURCE_PICKER = "picker";
    public static final String SOURCE_REMOTE = "remote";

    public static final int PARENT_DEPTH_SELF = 0;
    public static final int PARENT_DEPTH_AUTO_ITEM = -1; // 智能回溯列表条目容器根节点

    private String id;
    private String name;
    private String type;         // res_id / class_name / text
    private String targetValue;  // 匹配目标值，如 com.target:id/banner_ad 或文案
    private String action;       // 动作：collapse
    private boolean enabled;
    private long createTime;
    private int parentDepth;     // 向上折叠的层数：0=自身，-1=自动追溯列表条目根卡片，>0=指定层级
    private String source = SOURCE_PICKER; // 规则来源：picker / remote
    private String activityFilter = "";    // 可选：限定生效的 Activity 类名（多个用逗号分隔，支持后缀/子串匹配）
    private int maxTextLength = 0;         // 可选：TYPE_TEXT 匹配时的最大文本长度（0=不限制，防止短词误伤长正文）
    // Defaults preserve hand-written/picker rule semantics.
    private String textProperty = "any";   // any / text / desc
    private String textMatchMode = "contains"; // contains / equals
    private boolean trimText = true;
    private boolean exactActivityFilter = false;
    private boolean exactResourceId = false;

    // Explicit, persisted ownership; names and abbreviated targets are never identities.
    private String ruleNamespace = "";
    private String subscriptionId = "";
    private String subscriptionPackage = "";
    private String externalRuleId = "";

    public CustomRule() {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.type = TYPE_RES_ID;
        this.action = ACTION_COLLAPSE;
        this.enabled = true;
        this.createTime = System.currentTimeMillis();
        this.parentDepth = PARENT_DEPTH_SELF;
    }

    public CustomRule(String name, String type, String targetValue, String action) {
        this(name, type, targetValue, action, PARENT_DEPTH_SELF);
    }

    public CustomRule(String name, String type, String targetValue, String action, int parentDepth) {
        this(name, type, targetValue, action, parentDepth, SOURCE_PICKER);
    }

    public CustomRule(String name, String type, String targetValue, String action, int parentDepth, String source) {
        this();
        this.name = name;
        this.type = type;
        this.targetValue = targetValue;
        this.action = action;
        this.parentDepth = parentDepth;
        this.source = source != null ? source : SOURCE_PICKER;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("type", type);
            json.put("targetValue", targetValue);
            json.put("action", action);
            json.put("enabled", enabled);
            json.put("createTime", createTime);
            json.put("parentDepth", parentDepth);
            json.put("source", source);
            json.put("textProperty", textProperty);
            json.put("textMatchMode", textMatchMode);
            json.put("trimText", trimText);
            json.put("exactActivityFilter", exactActivityFilter);
            json.put("exactResourceId", exactResourceId);
            if (!ruleNamespace.isEmpty()) {
                json.put("ruleNamespace", ruleNamespace);
                json.put("subscriptionId", subscriptionId);
                json.put("subscriptionPackage", subscriptionPackage);
                json.put("externalRuleId", externalRuleId);
            }
            if (activityFilter != null && !activityFilter.isEmpty()) {
                json.put("activityFilter", activityFilter);
            }
            if (maxTextLength > 0) {
                json.put("maxTextLength", maxTextLength);
            }
        } catch (JSONException ignored) {
        }
        return json;
    }

    public static CustomRule fromJson(JSONObject json) {
        if (json == null) return null;
        String action = json.optString("action", ACTION_COLLAPSE);
        if (!ACTION_COLLAPSE.equals(action)) return null;
        String source = json.optString("source", SOURCE_PICKER);
        if (!SOURCE_PICKER.equals(source) && !SOURCE_REMOTE.equals(source)) return null;
        CustomRule rule = new CustomRule();
        rule.id = json.optString("id", rule.id);
        rule.name = json.optString("name", "未命名规则");
        rule.type = json.optString("type", TYPE_RES_ID);
        rule.targetValue = json.optString("targetValue", "");
        rule.action = action;
        rule.enabled = json.optBoolean("enabled", true);
        rule.createTime = json.optLong("createTime", System.currentTimeMillis());
        rule.parentDepth = json.optInt("parentDepth", PARENT_DEPTH_SELF);
        rule.source = source;
        rule.activityFilter = json.optString("activityFilter", "");
        rule.maxTextLength = Math.max(0, json.optInt("maxTextLength", 0));
        rule.textProperty = json.optString("textProperty", "any");
        rule.textMatchMode = json.optString("textMatchMode", "contains");
        rule.trimText = json.optBoolean("trimText", true);
        rule.exactActivityFilter = json.optBoolean("exactActivityFilter", false);
        rule.exactResourceId = json.optBoolean("exactResourceId", false);
        rule.ruleNamespace = json.optString("ruleNamespace", "");
        rule.subscriptionId = json.optString("subscriptionId", "");
        rule.subscriptionPackage = json.optString("subscriptionPackage", "");
        rule.externalRuleId = json.optString("externalRuleId", "");
        return rule;
    }

    /** A detached, full-field copy; never share mutable rules between UI and reconciliation. */
    public CustomRule copy() {
        CustomRule result = new CustomRule(name, type, targetValue, action, parentDepth, source);
        result.id = id;
        result.enabled = enabled;
        result.createTime = createTime;
        result.activityFilter = activityFilter;
        result.maxTextLength = maxTextLength;
        result.textProperty = textProperty;
        result.textMatchMode = textMatchMode;
        result.trimText = trimText;
        result.exactActivityFilter = exactActivityFilter;
        result.exactResourceId = exactResourceId;
        result.ruleNamespace = ruleNamespace;
        result.subscriptionId = subscriptionId;
        result.subscriptionPackage = subscriptionPackage;
        result.externalRuleId = externalRuleId;
        return result;
    }

    public String getTextProperty() { return textProperty; }
    public void setTextProperty(String value) { textProperty = value == null ? "any" : value; }
    public String getTextMatchMode() { return textMatchMode; }
    public void setTextMatchMode(String value) { textMatchMode = value == null ? "contains" : value; }
    public boolean isTrimText() { return trimText; }
    public void setTrimText(boolean value) { trimText = value; }
    public boolean isExactActivityFilter() { return exactActivityFilter; }
    public void setExactActivityFilter(boolean value) { exactActivityFilter = value; }
    public boolean isExactResourceId() { return exactResourceId; }
    public void setExactResourceId(boolean value) { exactResourceId = value; }
    public String getRuleNamespace() { return ruleNamespace; }
    public void setRuleNamespace(String value) { ruleNamespace = value == null ? "" : value; }
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String value) { subscriptionId = value == null ? "" : value; }
    public String getSubscriptionPackage() { return subscriptionPackage; }
    public void setSubscriptionPackage(String value) { subscriptionPackage = value == null ? "" : value; }
    public String getExternalRuleId() { return externalRuleId; }
    public void setExternalRuleId(String value) { externalRuleId = value == null ? "" : value; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTargetValue() { return targetValue; }
    public void setTargetValue(String targetValue) { this.targetValue = targetValue; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public int getParentDepth() { return parentDepth; }
    public void setParentDepth(int parentDepth) { this.parentDepth = parentDepth; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source != null ? source : SOURCE_PICKER; }

    public String getActivityFilter() { return activityFilter; }
    public void setActivityFilter(String activityFilter) {
        this.activityFilter = activityFilter != null ? activityFilter.trim() : "";
    }

    public int getMaxTextLength() { return maxTextLength; }
    public void setMaxTextLength(int maxTextLength) {
        this.maxTextLength = Math.max(0, maxTextLength);
    }
}
