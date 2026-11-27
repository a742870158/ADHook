package com.adhook.config.subscription

import com.adhook.config.model.CustomRule
import com.adhook.config.model.Subscription
import com.adhook.config.security.ConfigInputPolicy
import com.adhook.config.security.RulePayloadPolicy
import org.json.JSONArray
import org.json.JSONObject

/** Pure preflight shared by backup import, normal loading and SubscriptionManager; no singleton or IO. */
object SubscriptionBackupCodec {
    @JvmStatic fun validate(subs: JSONArray?, overrides: JSONObject?, meta: JSONObject?): Boolean = try {
        parseSubscriptions(subs ?: JSONArray())
        RulePreferences.fromJson(overrides)
        readKnownRules(overrides)
        readWarnings(overrides)
        readMeta(meta)
        true
    } catch (_: Exception) { false }

    fun validateSubscription(sub: Subscription) {
        require(sub.id != 1L && !sub.isLocal) { "本地规则容器不能作为远程订阅覆盖" }
        require(sub.apps.size <= 2000 && sub.totalRulesCount <= 50000) { "订阅应用或规则数量超过预算" }
        require(sub.apps.map { it.id }.distinct().size == sub.apps.size) { "订阅应用身份重复" }
        for (app in sub.apps) {
            require(ConfigInputPolicy.isPackageName(app.id)) {
                "无效订阅包名: ${app.id}"
            }
            require(app.rules.size <= 10000) { "单应用规则数量超过预算: ${app.id}" }
            for (rule in app.rules) {
                require(rule.id.isNotBlank()) { "订阅规则缺少稳定 id: ${app.id}" }
                require(RuleIdentity(CustomRule.SOURCE_REMOTE, sub.id.toString(), app.id, rule.id).key.length <= 4096) {
                    "订阅规则 id 过长: ${app.id}"
                }
                require(rule.type in setOf(CustomRule.TYPE_TEXT, CustomRule.TYPE_RES_ID, CustomRule.TYPE_CLASS_NAME)) { "不支持的规则类型: ${rule.type}" }
                require(rule.action == CustomRule.ACTION_COLLAPSE) { "不支持的规则动作: ${rule.action}" }
                require(rule.targetValue.isNotBlank() && rule.targetValue.length <= 2000) { "订阅规则目标为空或过长: ${rule.id}" }
                require(rule.name.length <= 200 && rule.activityFilter.length <= 512) { "订阅规则名称或Activity约束过长: ${rule.id}" }
                require(rule.textProperty in setOf("any", "text", "desc") && rule.textMatchMode in setOf("contains", "equals")) { "无效文本匹配模式: ${rule.id}" }
                require(rule.parentDepth in -1..100 && rule.maxTextLength in 0..2000 && rule.createTime >= 0) { "无效规则边界: ${rule.id}" }
            }
        }
    }

    /** Inspect raw types BEFORE optBoolean/optInt/optJSONArray can discard a constraint or source. */
    fun parseSubscription(json: JSONObject): Subscription {
        for ((key, limit) in mapOf("name" to 200, "author" to 200, "description" to 4096, "updateUrl" to 2048)) {
            if (json.has(key)) require(json.get(key) is String && json.getString(key).length <= limit) { "订阅字段类型或长度无效: $key" }
        }
        for (key in listOf("isEnabled", "isLocal")) {
            if (json.has(key)) require(json.get(key) is Boolean) { "订阅开关类型无效: $key" }
        }
        for (key in listOf("version", "updatedAt")) {
            if (!json.has(key)) continue
            val value = json.get(key)
            require((value is Int || value is Long) && (value as Number).toLong() >= 0 &&
                (key != "version" || value.toLong() <= Int.MAX_VALUE)) { "订阅版本或时间类型无效: $key" }
        }
        val apps = if (json.has("apps")) json.getJSONArray("apps") else null
        require(apps == null || apps.length() <= 2000) { "订阅应用数量超过预算" }
        var total = 0
        if (apps != null) for (i in 0 until apps.length()) {
            val app = apps.getJSONObject(i)
            require(app.opt("id") is String && ConfigInputPolicy.isPackageName(app.getString("id"))) { "无效订阅应用身份" }
            if (app.has("name")) require(app.get("name") is String && app.getString("name").length <= 200) { "订阅应用名称无效" }
            val rules = if (app.has("rules")) app.getJSONArray("rules") else continue
            total += rules.length()
            require(rules.length() <= 10000 && total <= 50000) { "订阅规则数量超过预算" }
            for (j in 0 until rules.length()) {
                val raw = rules.getJSONObject(j)
                require(raw.opt("id") is String && raw.getString("id").isNotBlank()) { "订阅规则缺少稳定字符串id" }
                val externalId = raw.getString("id")
                require(externalId.length <= 2000) { "订阅规则id过长" }
                val identity = RuleIdentity(CustomRule.SOURCE_REMOTE, json.get("id").toString(), app.getString("id"), externalId)
                require(identity.key.length <= 4096) { "订阅规则身份过长" }
                val normalized = JSONObject(raw.toString()).put("id", identity.key)
                require(RulePayloadPolicy.parse(normalized.toString()) != null) { "订阅规则类型或约束无效: $externalId" }
            }
        }
        return Subscription.fromJson(json).also(::validateSubscription)
    }

    fun parseSubscriptions(arr: JSONArray): List<Subscription> {
        val parsed = (0 until arr.length()).map { index ->
            parseSubscription(arr.getJSONObject(index))
        }
        require(parsed.sumOf { it.totalRulesCount.toLong() } <= 50000) { "订阅总规则数量超过预算" }
        require(parsed.map { it.id }.distinct().size == parsed.size) { "订阅 id 重复" }
        return parsed
    }

    fun readKnownRules(root: JSONObject?): List<ManagedRule> {
        val arr = optionalArray(root, "knownRules") ?: return emptyList()
        val result = (0 until arr.length()).map { ManagedRule.fromJson(arr.getJSONObject(it)) }
        require(result.map { it.identity.key }.distinct().size == result.size) { "重复的历史规则归属" }
        return result
    }

    fun readWarnings(root: JSONObject?): List<String> {
        val arr = optionalArray(root, "migrationWarnings") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun readMeta(root: JSONObject?): Map<String, Pair<Int, Long>> {
        if (root == null || !root.has("entries") || root.isNull("entries")) return emptyMap()
        val entries = root.getJSONObject("entries")
        val result = linkedMapOf<String, Pair<Int, Long>>()
        val keys = entries.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = entries.getJSONObject(key)
            result[key] = value.optInt("version", 0) to value.optLong("updatedAt", 0L)
        }
        return result
    }

    fun optionalArray(root: JSONObject?, key: String): JSONArray? {
        if (root == null || !root.has(key) || root.isNull(key)) return null
        return root.getJSONArray(key)
    }
}
