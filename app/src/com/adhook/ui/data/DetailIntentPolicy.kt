package com.adhook.ui.data

import android.content.Intent
import com.adhook.config.security.ConfigInputPolicy

/** External Intents are navigation hints only, never commands or configuration payloads. */
object DetailIntentPolicy {
    fun packageName(intent: Intent?): String? = try {
        intent?.getStringExtra("package_name")?.takeIf { ConfigInputPolicy.isPackageName(it) }
    } catch (_: RuntimeException) {
        null
    }

    fun consumePayloadHint(intent: Intent?): Boolean {
        if (intent == null) return false
        return try {
            val hadPayload = intent.hasExtra("pending_rule_json")
            // Do not read, parse, log or compare attacker-supplied rule IDs/payloads.
            intent.removeExtra("pending_rule_json")
            intent.removeExtra("app_name")
            hadPayload
        } catch (_: RuntimeException) {
            false
        }
    }
}
