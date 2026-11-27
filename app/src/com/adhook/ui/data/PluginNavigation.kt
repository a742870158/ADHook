package com.adhook.ui.data

import android.content.Intent
import com.adhook.plugin.PluginConstants

/**
 * Navigation-only plugin entry point. An external Intent may select a plugin id for display;
 * it can never install, enable, authorize or uninstall anything.
 */
object PluginNavigation {
    const val EXTRA_PLUGIN_ID = "plugin_id"

    fun pluginId(intent: Intent?): String? = try {
        intent?.getStringExtra(EXTRA_PLUGIN_ID)?.takeIf { PluginConstants.isValidPluginId(it) }
    } catch (_: RuntimeException) {
        null
    }
}
