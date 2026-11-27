package com.adhook.config.subscription

import com.adhook.config.model.Subscription
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubscriptionBackupCodecTest {
    @Test fun legacyMissingOptionalBlocksAreAcceptedWithoutConstructingManager() {
        val instance = SubscriptionManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
        assertNull(instance.get(null))
        val subs = JSONArray().put(Subscription(42, "legacy", 1, "author", "", "").toJson())
        assertTrue(SubscriptionManager.validateBackupJson(subs, null, null))
        assertNull(instance.get(null))
        assertTrue(SubscriptionManager.validateBackupJson(null, null, null))
    }

    @Test fun malformedBlocksAreNotSilentlyRestoredAsEmptyPreferencesOrWithdrawals() {
        val subs = JSONArray().put(Subscription(42, "legacy", 1, "author", "", "").toJson())
        for (field in listOf("removedRuleIds", "disabledRuleIds", "enabledRuleIds", "protectedLocalRuleKeys", "knownRules", "migrationWarnings")) {
            assertFalse(field, SubscriptionBackupCodec.validate(subs, JSONObject().put(field, "not an array"), null))
        }
        assertFalse(SubscriptionBackupCodec.validate(subs, null, JSONObject().put("entries", JSONArray())))
        val wrongApps = JSONObject().put("id", 42).put("apps", JSONObject())
        assertFalse(SubscriptionBackupCodec.validate(JSONArray().put(wrongApps), null, null))
        assertFalse(SubscriptionBackupCodec.validate(JSONArray().put(JSONObject().put("id", 42.5)), null, null))
        assertFalse(SubscriptionBackupCodec.validate(subs, JSONObject().put("knownRules", JSONArray().put(JSONObject().put("key", "broken"))), null))
    }

    @Test fun unknownLegacyPreferenceIdsAndMigrationProtectionRoundTrip() {
        val local = com.adhook.config.model.CustomRule("local", "text", "Skip", "collapse").also { it.id = "old-id" }
        val preferences = RulePreferences().apply { remove("unresolved-old-id"); protectLocal("com.example", local) }
        val root = preferences.toJson().put("migrationWarnings", JSONArray().put("com.example/old-id: confirm ownership"))
        assertTrue(SubscriptionBackupCodec.validate(null, root, null))
        val restored = RulePreferences.fromJson(root)
        assertTrue("unresolved-old-id" in restored.removed)
        assertTrue(restored.isProtectedLocal("com.example", local))
        assertEquals(listOf("com.example/old-id: confirm ownership"), SubscriptionBackupCodec.readWarnings(root))
    }
}
