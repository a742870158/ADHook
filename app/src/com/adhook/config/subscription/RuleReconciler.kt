package com.adhook.config.subscription

import com.adhook.config.model.CustomRule
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** Collision-free tuple identity, independent of labels, rule order and abbreviated class names. */
data class RuleIdentity(
    val namespace: String,
    val subscriptionId: String,
    val packageName: String,
    val externalRuleId: String
) {
    init {
        require(namespace == CustomRule.SOURCE_REMOTE)
        require(subscriptionId.isNotBlank() && packageName.isNotBlank() && externalRuleId.isNotBlank())
    }

    val key: String = "adhook:v2:" + listOf(namespace, subscriptionId, packageName, externalRuleId)
        .joinToString(":") { hex(it) }

    fun bind(rule: CustomRule): CustomRule = rule.also {
        it.id = key
        it.source = namespace
        it.ruleNamespace = namespace
        it.subscriptionId = subscriptionId
        it.subscriptionPackage = packageName
        it.externalRuleId = externalRuleId
    }

    companion object {
        private fun hex(value: String): String {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            val alphabet = "0123456789abcdef"
            return buildString(bytes.size * 2) {
                for (byte in bytes) { val n = byte.toInt() and 255; append(alphabet[n ushr 4]); append(alphabet[n and 15]) }
            }
        }

        private fun unhex(value: String): String {
            require(value.length % 2 == 0)
            val bytes = ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            return String(bytes, StandardCharsets.UTF_8)
        }

        @JvmStatic fun fromKey(key: String?): RuleIdentity? = try {
            val parts = key.orEmpty().split(':')
            if (parts.size != 6 || parts[0] != "adhook" || parts[1] != "v2") null
            else RuleIdentity(unhex(parts[2]), unhex(parts[3]), unhex(parts[4]), unhex(parts[5]))
                .takeIf { it.key == key }
        } catch (_: IllegalArgumentException) { null }

        @JvmStatic fun fromRule(rule: CustomRule): RuleIdentity? = try {
            if (!rule.ruleNamespace.isNullOrBlank() && !rule.subscriptionId.isNullOrBlank() &&
                !rule.subscriptionPackage.isNullOrBlank() && !rule.externalRuleId.isNullOrBlank()) {
                RuleIdentity(rule.ruleNamespace, rule.subscriptionId, rule.subscriptionPackage, rule.externalRuleId)
            } else {
                // A local rule whose ID happens to collide with a reserved key is still local.
                fromKey(rule.id)?.takeIf { it.namespace == rule.source }
            }
        } catch (_: IllegalArgumentException) { null }
    }
}

/** Active means source/subscription enabled; user settings live exclusively in RulePreferences. */
data class ManagedRule(
    val identity: RuleIdentity,
    val rule: CustomRule,
    val active: Boolean = true,
    val aliases: Set<String> = setOf(identity.externalRuleId),
    val legacyVariants: List<CustomRule> = emptyList()
) {
    fun detachedCopy(): ManagedRule = copy(rule = rule.copy(), aliases = aliases.toSet(),
        legacyVariants = legacyVariants.map { it.copy() })

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", identity.key)
        put("rule", identity.bind(rule.copy()).toJson())
        put("active", active)
        put("aliases", JSONArray(aliases.toList()))
        put("legacyVariants", JSONArray().also { arr -> legacyVariants.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): ManagedRule {
            val identity = requireNotNull(RuleIdentity.fromKey(json.getString("key"))) { "Invalid rule ownership" }
            val aliases = mutableSetOf<String>()
            json.optJSONArray("aliases")?.let { arr ->
                for (i in 0 until arr.length()) aliases.add(arr.getString(i))
            }
            val variants = mutableListOf<CustomRule>()
            json.optJSONArray("legacyVariants")?.let { arr ->
                for (i in 0 until arr.length()) variants.add(CustomRule.fromJson(arr.getJSONObject(i)))
            }
            return ManagedRule(identity, CustomRule.fromJson(json.getJSONObject("rule")),
                json.optBoolean("active", true), aliases, variants)
        }
    }
}

/** Retains unresolved legacy IDs too; an ambiguous legacy preference is never spread to other owners. */
class RulePreferences(
    val removed: MutableSet<String> = linkedSetOf(),
    val disabled: MutableSet<String> = linkedSetOf(),
    val explicitlyEnabled: MutableSet<String> = linkedSetOf(),
    val protectedLocalRuleKeys: MutableSet<String> = linkedSetOf()
) {
    fun copy(): RulePreferences = RulePreferences(removed.toMutableSet(), disabled.toMutableSet(),
        explicitlyEnabled.toMutableSet(), protectedLocalRuleKeys.toMutableSet())

    private fun localKey(pkg: String, id: String): String = "${pkg.length}:$pkg${id.length}:$id"
    fun protectLocal(pkg: String, rule: CustomRule) { protectedLocalRuleKeys.add(localKey(pkg, rule.id.orEmpty())) }
    fun isProtectedLocal(pkg: String, rule: CustomRule): Boolean =
        RuleIdentity.fromRule(rule) == null && localKey(pkg, rule.id.orEmpty()) in protectedLocalRuleKeys

    fun setEnabled(key: String, enabled: Boolean) {
        if (enabled) {
            removed.remove(key)
            disabled.remove(key)
            explicitlyEnabled.add(key)
        } else {
            disabled.add(key)
            explicitlyEnabled.remove(key)
        }
    }

    fun remove(key: String) {
        removed.add(key)
        disabled.remove(key)
        explicitlyEnabled.remove(key)
    }

    fun hasEnabledPreference(key: String): Boolean = key in disabled || key in explicitlyEnabled

    fun migrateAliases(catalogue: Collection<ManagedRule>, diagnostics: MutableList<String>) {
        val aliases = linkedMapOf<String, MutableSet<String>>()
        for (item in catalogue) for (alias in item.aliases) {
            if (alias.isNotBlank()) aliases.getOrPut(alias) { linkedSetOf() }.add(item.identity.key)
        }
        for (set in listOf(removed, disabled, explicitlyEnabled)) {
            for (legacy in set.toList()) {
                if (RuleIdentity.fromKey(legacy) != null) continue
                val owners = aliases[legacy].orEmpty()
                if (owners.size == 1) {
                    set.remove(legacy)
                    set.add(owners.single())
                } else if (owners.size > 1) {
                    diagnostics.add("Ambiguous legacy preference preserved, not applied: $legacy")
                }
            }
        }
        // Deletion has precedence over switches, and disabling over contradictory old positive records.
        disabled.removeAll(removed)
        explicitlyEnabled.removeAll(removed + disabled)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", 2)
        put("removedRuleIds", JSONArray(removed.toList().sorted()))
        put("disabledRuleIds", JSONArray(disabled.toList().sorted()))
        put("enabledRuleIds", JSONArray(explicitlyEnabled.toList().sorted()))
        put("protectedLocalRuleKeys", JSONArray(protectedLocalRuleKeys.toList().sorted()))
    }

    companion object {
        fun fromJson(json: JSONObject?): RulePreferences {
            fun readSet(key: String): MutableSet<String> {
                val result = linkedSetOf<String>()
                SubscriptionBackupCodec.optionalArray(json, key)?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val id = arr.getString(i)
                        if (id.isNotBlank()) result.add(id)
                    }
                }
                return result
            }
            return RulePreferences(readSet("removedRuleIds"), readSet("disabledRuleIds"), readSet("enabledRuleIds"), readSet("protectedLocalRuleKeys"))
        }
    }
}

data class ReconcileResult(
    val rulesByPackage: Map<String, List<CustomRule>>,
    val preferences: RulePreferences,
    val knownRules: List<ManagedRule>,
    val diagnostics: List<String>
)

/** Android-free snapshot reconciliation. Inputs are never mutated. */
object RuleReconciler {
    @JvmStatic fun sameMatcher(a: CustomRule, b: CustomRule): Boolean =
        a.type == b.type && a.targetValue == b.targetValue && a.action == b.action &&
            a.parentDepth == b.parentDepth && a.activityFilter.orEmpty() == b.activityFilter.orEmpty() &&
            a.maxTextLength == b.maxTextLength && a.textProperty == b.textProperty &&
            a.textMatchMode == b.textMatchMode && a.isTrimText == b.isTrimText &&
            a.isExactActivityFilter == b.isExactActivityFilter && a.isExactResourceId == b.isExactResourceId

    /** Migrates only unique full-payload matches for historically mislabelled picker rules. */
    fun resolveLegacy(pkg: String, rule: CustomRule, catalogue: Collection<ManagedRule>): RuleIdentity? {
        RuleIdentity.fromRule(rule)?.let { return it }
        val candidates = catalogue.filter { item ->
            if (item.identity.packageName != pkg) return@filter false
            if (rule.source == CustomRule.SOURCE_REMOTE && item.identity.namespace != CustomRule.SOURCE_REMOTE) return@filter false
            if ((rule.source == null || rule.source == CustomRule.SOURCE_PICKER) && item.identity.namespace != CustomRule.SOURCE_REMOTE) return@filter false
            val idMatches = rule.id in item.aliases || rule.id == item.identity.key
            if (!idMatches) return@filter false
            if (rule.source == CustomRule.SOURCE_REMOTE && item.identity.namespace == CustomRule.SOURCE_REMOTE) {
                idMatches
            } else {
                // Do not use a name, entry name, simple class, or partial selector as an ownership proof.
                sameMatcher(rule, item.rule) || item.legacyVariants.any { sameMatcher(rule, it) }
            }
        }.map { it.identity }.distinct()
        return candidates.singleOrNull()
    }

    fun reconcile(
        namespace: String,
        existing: Map<String, List<CustomRule>>,
        incoming: List<ManagedRule>,
        history: List<ManagedRule>,
        preferences: RulePreferences
    ): ReconcileResult {
        require(incoming.all { it.identity.namespace == namespace })
        val diagnostics = mutableListOf<String>()
        val prefs = preferences.copy()
        val catalogue = history + incoming
        val observations = mutableListOf<ManagedRule>()
        val preserved = linkedMapOf<String, MutableList<CustomRule>>()
        val previous = linkedMapOf<String, CustomRule>()

        for ((pkg, rules) in existing) {
            val local = preserved.getOrPut(pkg) { mutableListOf() }
            for (rule in rules) {
                val identity = if (prefs.isProtectedLocal(pkg, rule)) null else resolveLegacy(pkg, rule, catalogue)
                if (identity == null || identity.namespace != namespace) {
                    if (identity == null && rule.source == namespace &&
                        namespace == CustomRule.SOURCE_REMOTE) {
                        // The source is explicit but no owner remains: this is a withdrawn legacy remote rule.
                        diagnostics.add("Removed withdrawn unowned $namespace rule: $pkg/${rule.id}")
                    } else {
                        if (identity == null && catalogue.any { item -> item.identity.packageName == pkg &&
                                (rule.id in item.aliases || rule.id == item.identity.key) }) {
                            prefs.protectLocal(pkg, rule)
                            diagnostics.add("Unresolved legacy/local ID collision preserved: $pkg/${rule.id}; ownership confirmation required")
                        }
                        local.add(rule.copy())
                    }
                    continue
                }
                previous[identity.key] = rule
                observations.add(ManagedRule(identity, rule.copy(), aliases = setOf(rule.id)))
            }
        }
        // Alias migration is global, not per package: identical external IDs across subscriptions are ambiguous.
        prefs.migrateAliases(catalogue + observations, diagnostics)
        for ((key, rule) in previous) {
            if (!rule.isEnabled && !prefs.hasEnabledPreference(key) && key !in prefs.removed) prefs.setEnabled(key, false)
        }

        val grouped = incoming.groupBy { it.identity.key }
        for ((key, definitions) in grouped) {
            val first = definitions.first()
            if (definitions.size > 1 && definitions.any {
                    !sameMatcher(first.rule, it.rule) || first.rule.name != it.rule.name || first.active != it.active
                }) {
                diagnostics.add("Conflicting subscription identity skipped: ${first.identity.packageName}/${first.identity.externalRuleId}")
                continue
            }
            if (!first.active || key in prefs.removed) continue
            val destination = preserved.getOrPut(first.identity.packageName) { mutableListOf() }
            if (destination.any { it.id == key }) {
                diagnostics.add("Local ID collision preserved; remote rule skipped: $key")
                continue
            }
            val rule = first.identity.bind(first.rule.copy())
            rule.isEnabled = key !in prefs.disabled
            // Creation is local history, not selector state; do not reset it on every refresh.
            if (rule.createTime <= 0L) rule.createTime = previous[key]?.createTime?.takeIf { it > 0L } ?: System.currentTimeMillis()
            destination.add(rule)
        }

        val known = linkedMapOf<String, ManagedRule>()
        // Keep withdrawn owners for deferred migration/tombstones. Never use them as active definitions.
        for (item in history + incoming) known[item.identity.key] = item.detachedCopy()
        return ReconcileResult(preserved, prefs, known.values.toList(), diagnostics.distinct())
    }
}
