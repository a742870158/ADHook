package com.adhook.ui

import android.content.Intent
import com.adhook.config.model.CustomRule
import com.adhook.config.security.ConfigInputPolicy
import com.adhook.ui.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DetailIntentPolicyTest {
    @Test fun existingRuleIdAndClaimedOriginNeverMakePayloadWritable() {
        val persisted = CustomRule("saved", "res_id", "saved_target", "collapse").apply { id = "known-id" }
        val injected = Intent().putExtra("package_name", "com.host")
            .putExtra("app_name", "Trusted-looking name")
            .putExtra("launchedFromPackage", "com.host")
            .putExtra("pending_rule_json", "{\"id\":\"known-id\",\"type\":\"text\",\"targetValue\":\"Pay\",\"action\":\"click\"}")
        assertEquals("com.host", DetailIntentPolicy.packageName(injected))
        assertTrue(DetailIntentPolicy.consumePayloadHint(injected))
        assertFalse(injected.hasExtra("pending_rule_json"))
        assertFalse(injected.hasExtra("app_name"))
        assertEquals("saved_target", persisted.targetValue)
        assertEquals("collapse", persisted.action)
    }

    @Test fun payloadIsNeverParsedEvenWhenHugeOrNotJson() {
        val injected = Intent().putExtra("package_name", "com.host").putExtra("pending_rule_json", "{".repeat(200_000))
        assertTrue(DetailIntentPolicy.consumePayloadHint(injected))
        assertFalse(injected.hasExtra("pending_rule_json"))
    }

    @Test fun invalidNavigationTargetIsRejected() {
        for (pkg in listOf("com..host", "../host", "com.host/Other")) {
            assertNull(DetailIntentPolicy.packageName(Intent().putExtra("package_name", pkg)))
        }
        assertNull(DetailIntentPolicy.packageName(Intent().putExtra("package_name", 7)))
    }
}
