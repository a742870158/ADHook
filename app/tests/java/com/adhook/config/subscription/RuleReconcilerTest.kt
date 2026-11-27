package com.adhook.config.subscription

import com.adhook.config.model.CustomRule
import com.adhook.config.model.SubscriptionRuleItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuleReconcilerTest {
    private val pkg = "com.example"

    @Test fun tupleIdentityCannotCollideAcrossSourceSubscriptionPackageOrDelimiters() {
        val key = RuleIdentity("remote", "2", pkg, "same").key
        assertNotEquals(key, RuleIdentity("remote", "3", pkg, "same").key)
        assertNotEquals(key, RuleIdentity("remote", "2", "com.other", "same").key)
        val escaped = RuleIdentity("remote", "a:b", "com.example", "id:/中文")
        assertEquals(escaped, RuleIdentity.fromKey(escaped.key))
        assertNotEquals(RuleIdentity("remote", "a", "b:c", "d").key, RuleIdentity("remote", "a:b", "c", "d").key)
    }

    @Test fun sameIdentityReplacesEverySourceFieldAndPreservesIndependentUserDisable() {
        val old = definition("same").also {
            it.rule.name = "old"
            it.rule.activityFilter = "old.Activity"
            it.rule.maxTextLength = 19
            it.rule.createTime = 1234
        }
        val incoming = definition("same", type = "text", target = "Close").also {
            it.rule.name = "updated"
            it.rule.action = "collapse"
            it.rule.parentDepth = 2
            it.rule.activityFilter = "new.Activity"
            it.rule.maxTextLength = 7
            it.rule.textProperty = "desc"
            it.rule.textMatchMode = "equals"
            it.rule.isTrimText = false
            it.rule.isExactActivityFilter = true
            it.rule.isExactResourceId = true
        }
        val prefs = RulePreferences().apply { setEnabled(old.identity.key, false) }
        val existing = old.identity.bind(old.rule.copy())
        val result = reconcile(listOf(existing), listOf(incoming), listOf(old), prefs)
        val actual = result.rulesByPackage.getValue(pkg).single()
        assertEquals(incoming.identity.key, actual.id)
        assertEquals("updated", actual.name)
        assertTrue(RuleReconciler.sameMatcher(incoming.rule, actual))
        assertEquals(CustomRule.SOURCE_REMOTE, actual.source)
        assertFalse(actual.isEnabled)
        assertEquals(1234L, actual.createTime)
        assertEquals("old", existing.name) // no mutation of the snapshot
    }

    @Test fun fieldsRemovedUpstreamAreClearedNotLeftStale() {
        val old = definition("same", type = "text").also {
            it.rule.activityFilter = "old.Activity"; it.rule.maxTextLength = 12
            it.rule.textProperty = "text"; it.rule.textMatchMode = "equals"; it.rule.isTrimText = false
            it.rule.isExactActivityFilter = true; it.rule.isExactResourceId = true; it.rule.parentDepth = 3
        }
        val latest = definition("same")
        val result = reconcile(listOf(old.identity.bind(old.rule.copy())), listOf(latest), listOf(old))
        val actual = result.rulesByPackage.getValue(pkg).single()
        assertTrue(RuleReconciler.sameMatcher(latest.rule, actual))
        assertEquals("", actual.activityFilter)
        assertEquals(0, actual.maxTextLength)
    }

    @Test fun withdrawalAndSourceDisableRemoveOnlyOwnedRulesIncludingAbsentPackages() {
        val owned = definition("remote")
        val local = local("local", "Banner", "same.target")
        val current = listOf(local, owned.identity.bind(owned.rule.copy()))
        val withdrawn = reconcile(current, emptyList(), listOf(owned))
        assertEquals(listOf("local"), withdrawn.rulesByPackage.getValue(pkg).map { it.id })
        val disabled = reconcile(current, listOf(owned.copy(active = false)), listOf(owned))
        assertEquals(listOf("local"), disabled.rulesByPackage.getValue(pkg).map { it.id })
    }

    @Test fun disabledAndDeletedPreferencesSurviveWithdrawalAndReappearance() {
        val disabled = definition("disabled")
        val removed = definition("deleted")
        val prefs = RulePreferences().apply {
            setEnabled(disabled.identity.key, false)
            remove(removed.identity.key)
        }
        val noSource = reconcile(emptyList(), emptyList(), listOf(disabled, removed), prefs)
        val reappeared = reconcile(emptyList(), listOf(disabled, removed), noSource.knownRules, noSource.preferences)
        val rules = reappeared.rulesByPackage.getValue(pkg)
        assertEquals(1, rules.size)
        assertEquals(disabled.identity.key, rules.single().id)
        assertFalse(rules.single().isEnabled)
    }

    @Test fun equalNamesTargetsAndSimpleClassesNeverMergeUnrelatedLocalOrRemoteRules() {
        val local = local("local", "same-name", "other.package.Banner")
        val first = definition("id", subscription = "10", type = "class_name", target = "one.package.Banner")
        val second = definition("id", subscription = "20", type = "class_name", target = "two.package.Banner")
        first.rule.name = "same-name"; second.rule.name = "same-name"
        val result = reconcile(listOf(local), listOf(first, second))
        assertEquals(3, result.rulesByPackage.getValue(pkg).size)
        assertEquals("other.package.Banner", result.rulesByPackage.getValue(pkg).first { it.id == "local" }.targetValue)
    }

    @Test fun sameExternalIdInDifferentPackagesRemainsIndependent() {
        val one = definition("common")
        val two = definition("common", packageName = "com.other")
        val prefs = RulePreferences().apply { remove(one.identity.key) }
        val result = RuleReconciler.reconcile("remote", emptyMap(), listOf(one, two), emptyList(), prefs)
        assertEquals(two.identity.key, result.rulesByPackage.getValue("com.other").single().id)
        assertTrue(result.rulesByPackage[pkg].isNullOrEmpty())
    }

    @Test fun conflictingDefinitionsDoNotUseOrderDependentLastWriterWins() {
        val one = definition("same", target = "one")
        val two = definition("same", target = "two")
        val old = one.identity.bind(one.rule.copy())
        val result = reconcile(listOf(old), listOf(one, two))
        assertTrue(result.rulesByPackage.getValue(pkg).isEmpty())
        assertTrue(result.diagnostics.any { "Conflicting" in it })
    }

    @Test fun canonicalIdCollisionDoesNotDeleteOrReplaceAnUnownedPicker() {
        val incoming = definition("same")
        val picker = local(incoming.identity.key, "local", "different.target")
        val result = reconcile(listOf(picker), listOf(incoming))
        assertEquals(1, result.rulesByPackage.getValue(pkg).size)
        assertEquals("different.target", result.rulesByPackage.getValue(pkg).single().targetValue)
        assertTrue(result.diagnostics.any { "collision" in it })
    }

    @Test fun historicalPickerLabelMigratesUsingExactLegacyIdAndFullOldPayload() {
        val historical = definition("legacy")
        val oldPicker = historical.rule.copy().also { it.id = "legacy"; it.source = "picker"; it.isEnabled = false }
        val latest = definition("legacy", target = "new.target")
        val localSameName = local("truly-local", oldPicker.name, oldPicker.targetValue)
        val result = reconcile(listOf(oldPicker, localSameName), listOf(latest), listOf(historical))
        assertEquals(2, result.rulesByPackage.getValue(pkg).size)
        val migrated = result.rulesByPackage.getValue(pkg).single { it.id == latest.identity.key }
        assertEquals("new.target", migrated.targetValue)
        assertEquals("remote", migrated.source)
        assertFalse(migrated.isEnabled)
        assertTrue(result.preferences.disabled.contains(latest.identity.key))
        assertEquals("picker", result.rulesByPackage.getValue(pkg).single { it.id == "truly-local" }.source)
    }

    @Test fun withdrawnLegacyPickerRuleIsRemovedUsingPreviousSourceManifest() {
        val historical = definition("legacy")
        val legacy = historical.rule.copy().also { it.id = "legacy"; it.source = "picker" }
        val result = reconcile(listOf(legacy), emptyList(), listOf(historical))
        assertTrue(result.rulesByPackage.getValue(pkg).isEmpty())
    }

    @Test fun sameLegacyIdWithDifferentPayloadIsNotProofOfPickerOwnership() {
        val incoming = definition("same")
        val picker = local("same", incoming.rule.name, "different.target")
        val result = reconcile(listOf(picker), listOf(incoming))
        assertEquals(2, result.rulesByPackage.getValue(pkg).size)
        assertEquals("different.target", result.rulesByPackage.getValue(pkg).first { it.id == "same" }.targetValue)
        assertTrue(result.diagnostics.any { "Unresolved legacy/local" in it })
    }

    @Test fun legacyBackupPreferencesMigrateOnlyWhenOwnerIsUnique() {
        val incoming = definition("legacy")
        val prefs = RulePreferences.fromJson(JSONObject().put("removedRuleIds", JSONArray().put("legacy")))
        val result = reconcile(emptyList(), listOf(incoming), emptyList(), prefs)
        assertTrue(result.rulesByPackage[pkg].isNullOrEmpty())
        assertTrue(incoming.identity.key in result.preferences.removed)
        assertFalse("legacy" in result.preferences.removed)
        val conflict = definition("legacy", subscription = "other")
        val ambiguous = reconcile(emptyList(), listOf(incoming, conflict), emptyList(), prefs)
        assertEquals(2, ambiguous.rulesByPackage.getValue(pkg).size)
        assertTrue("legacy" in ambiguous.preferences.removed)
        assertTrue(ambiguous.diagnostics.any { "Ambiguous" in it })
    }

    @Test fun explicitEnableWinsOverStaleDisabledProjection() {
        val incoming = definition("same")
        val old = incoming.identity.bind(incoming.rule.copy()).also { it.isEnabled = false }
        val prefs = RulePreferences().apply { setEnabled(incoming.identity.key, true) }
        assertTrue(reconcile(listOf(old), listOf(incoming), emptyList(), prefs).rulesByPackage.getValue(pkg).single().isEnabled)
    }

    @Test fun preferenceAndOwnershipBackupRoundTripRetainsAllFields() {
        val incoming = definition("same", type = "text").also {
            it.rule.maxTextLength = 9; it.rule.textProperty = "text"; it.rule.textMatchMode = "equals"
            it.rule.isTrimText = false; it.rule.isExactActivityFilter = true; it.rule.activityFilter = "com.example.Splash"
        }
        val bound = incoming.identity.bind(incoming.rule.copy())
        val copy = CustomRule.fromJson(bound.toJson())
        assertTrue(RuleReconciler.sameMatcher(bound, copy))
        assertEquals(incoming.identity, RuleIdentity.fromRule(copy.copy()))
        val item = SubscriptionRuleItem.fromJson(bound.toJson())
        val itemAgain = SubscriptionRuleItem.fromJson(item.toJson())
        assertTrue(RuleReconciler.sameMatcher(bound, itemAgain.toCustomRule("remote")))
        val known = ManagedRule.fromJson(incoming.toJson())
        assertEquals(incoming.identity, known.identity)
        assertTrue(RuleReconciler.sameMatcher(incoming.rule, known.rule))
        val prefs = RulePreferences().apply { setEnabled(incoming.identity.key, false); remove("unresolved-legacy") }
        val restored = RulePreferences.fromJson(prefs.toJson())
        assertEquals(prefs.disabled, restored.disabled)
        assertEquals(prefs.removed, restored.removed)
    }

    private fun definition(id: String, subscription: String = "42", packageName: String = pkg,
        type: String = "res_id", target: String = "same.target"): ManagedRule {
        val identity = RuleIdentity("remote", subscription, packageName, id)
        val rule = CustomRule("rule", type, target, "collapse", 0, "remote").also { it.id = id; it.createTime = 0 }
        return ManagedRule(identity, rule)
    }
    private fun local(id: String, name: String?, target: String): CustomRule =
        CustomRule(name, "class_name", target, "collapse").also { it.id = id }
    private fun reconcile(existing: List<CustomRule>, incoming: List<ManagedRule>, history: List<ManagedRule> = emptyList(), preferences: RulePreferences = RulePreferences()): ReconcileResult =
        RuleReconciler.reconcile("remote", mapOf(pkg to existing), incoming, history, preferences)
}
