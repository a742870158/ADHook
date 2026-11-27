package com.adhook.config.model

import org.json.JSONArray
import org.json.JSONObject

/** Source data only. Ownership is assigned by the subscription manager, never by names/targets. */
data class SubscriptionRuleItem(
    val id: String,
    val name: String,
    val type: String,
    val targetValue: String,
    val action: String,
    var enabled: Boolean = true,
    val parentDepth: Int = 0,
    val activityFilter: String = "",
    val maxTextLength: Int = 0,
    val textProperty: String = "any",
    val textMatchMode: String = "contains",
    val trimText: Boolean = true,
    val exactActivityFilter: Boolean = false,
    val exactResourceId: Boolean = false,
    val createTime: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type)
        put("targetValue", targetValue)
        put("action", action)
        put("enabled", enabled)
        put("parentDepth", parentDepth)
        put("activityFilter", activityFilter)
        put("maxTextLength", maxTextLength)
        put("textProperty", textProperty)
        put("textMatchMode", textMatchMode)
        put("trimText", trimText)
        put("exactActivityFilter", exactActivityFilter)
        put("exactResourceId", exactResourceId)
        put("createTime", createTime)
    }

    fun toCustomRule(source: String): CustomRule = CustomRule(
        name, type, targetValue, action, parentDepth, source
    ).also {
        it.id = id
        it.isEnabled = enabled
        it.activityFilter = activityFilter
        it.maxTextLength = maxTextLength
        it.textProperty = textProperty
        it.textMatchMode = textMatchMode
        it.isTrimText = trimText
        it.isExactActivityFilter = exactActivityFilter
        it.isExactResourceId = exactResourceId
        it.createTime = createTime
    }

    companion object {
        fun fromJson(json: JSONObject): SubscriptionRuleItem = SubscriptionRuleItem(
            id = json.optString("id", ""),
            name = json.optString("name", "未命名规则"),
            type = json.optString("type", "res_id"),
            targetValue = json.optString("targetValue", ""),
            action = json.optString("action", "collapse"),
            enabled = json.optBoolean("enabled", true),
            parentDepth = json.optInt("parentDepth", 0),
            activityFilter = json.optString("activityFilter", ""),
            maxTextLength = json.optInt("maxTextLength", 0),
            textProperty = json.optString("textProperty", "any"),
            textMatchMode = json.optString("textMatchMode", "contains"),
            trimText = json.optBoolean("trimText", true),
            exactActivityFilter = json.optBoolean("exactActivityFilter", false),
            exactResourceId = json.optBoolean("exactResourceId", false),
            createTime = json.optLong("createTime", 0L)
        )

        fun fromCustomRule(rule: CustomRule): SubscriptionRuleItem = fromJson(rule.toJson())
    }
}

data class SubscriptionApp(
    val id: String,
    val name: String,
    val rules: List<SubscriptionRuleItem>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("rules", JSONArray().also { arr -> rules.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): SubscriptionApp {
            val rules = mutableListOf<SubscriptionRuleItem>()
            json.optJSONArray("rules")?.let { arr ->
                for (i in 0 until arr.length()) rules.add(SubscriptionRuleItem.fromJson(arr.getJSONObject(i)))
            }
            return SubscriptionApp(json.optString("id", ""), json.optString("name", ""), rules)
        }
    }
}

data class Subscription(
    val id: Long,
    val name: String,
    val version: Int,
    val author: String,
    val description: String,
    val updateUrl: String,
    var updatedAt: Long = System.currentTimeMillis(),
    var isEnabled: Boolean = true,
    val apps: List<SubscriptionApp> = emptyList(),
    val isLocal: Boolean = false
) {
    val totalRulesCount: Int get() = apps.sumOf { it.rules.size }
    val totalAppsCount: Int get() = apps.size

    fun detachedCopy(): Subscription = copy(apps = apps.map { app ->
        app.copy(rules = app.rules.map { it.copy() })
    })

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("author", author)
        put("description", description)
        put("updateUrl", updateUrl)
        put("updatedAt", updatedAt)
        put("isEnabled", isEnabled)
        put("isLocal", isLocal)
        put("apps", JSONArray().also { arr -> apps.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): Subscription {
            val apps = mutableListOf<SubscriptionApp>()
            json.optJSONArray("apps")?.let { arr ->
                for (i in 0 until arr.length()) apps.add(SubscriptionApp.fromJson(arr.getJSONObject(i)))
            }
            // A generated timestamp changes ownership on every import and is not a source identity.
            require(json.has("id") && !json.isNull("id")) { "订阅缺少稳定 id" }
            val stableId = json.get("id").toString().takeIf { it.matches(Regex("-?[0-9]+")) }?.toLongOrNull()
            require(stableId != null) { "订阅 id 必须是整数" }
            return Subscription(
                id = stableId,
                name = json.optString("name", "未命名订阅"),
                version = json.optInt("version", 1),
                author = json.optString("author", "社区维护者"),
                description = json.optString("description", ""),
                updateUrl = json.optString("updateUrl", ""),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                isEnabled = json.optBoolean("isEnabled", true),
                apps = apps,
                isLocal = json.optBoolean("isLocal", false)
            )
        }
    }
}
