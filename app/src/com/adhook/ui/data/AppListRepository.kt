package com.adhook.ui.data

import com.adhook.config.model.AppConfig
import com.adhook.config.model.FeatureMeta
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@androidx.compose.runtime.Immutable
data class AppHookItemState(
    val pkg: String,
    val appName: String,
    val enabledNative: Int,
    val totalNative: Int,
    val customCount: Int,
    val isInspectorOn: Boolean,
    /** Installed plugin targeting this package; null for generic-only hosts. */
    val pluginId: String? = null,
    val pluginVersion: String? = null,
    val pluginName: String? = null
)

data class InstalledApp(val pkg: String, val label: String, val system: Boolean = false)

/** One installed plugin target package. Plugins are always enabled after installation. */
data class PluginTargetApp(
    val pkg: String,
    val label: String,
    val pluginId: String,
    val pluginName: String,
    val pluginVersion: String,
    val features: List<FeatureMeta>
)

interface AppCatalog {
    fun installedApps(): List<InstalledApp>

    /** Plugin target catalog; a default keeps catalogue fakes and tests source-compatible. */
    fun pluginApps(): List<PluginTargetApp> = emptyList()
}

/** A read contract has no creating getter, so enumerating packages cannot grant or create anything. */
fun interface AppConfigReader { fun snapshot(): Map<String, AppConfig> }

data class AppListSnapshot(
    val apps: List<AppHookItemState>,
    /** Visible apps backed by an installed plugin target. */
    val pluginCount: Int = 0
)

class AppListRepository(
    private val catalog: AppCatalog,
    private val configs: AppConfigReader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun load(): AppListSnapshot = withContext(ioDispatcher) {
        val snapshot = configs.snapshot()
        val installedApps = catalog.installedApps()
        val packages = installedApps.map { it.pkg }.toSet()
        val pluginTargets = resolvePluginTargets(packages)
        val apps = installedApps.mapNotNull { installed ->
            if (installed.pkg == "com.adhook" || installed.pkg == "android") return@mapNotNull null
            val plugin = pluginTargets[installed.pkg]
            // System apps stay hidden unless a plugin explicitly targets them.
            if (installed.system && plugin == null) return@mapNotNull null
            val config = snapshot[installed.pkg]
            val features = plugin?.features.orEmpty().filter { it.isImplemented }
            AppHookItemState(
                pkg = installed.pkg,
                appName = installed.label,
                // Plugin features start disabled; only an explicit preference counts as enabled.
                enabledNative = features.count {
                    com.adhook.core.FeatureGuard.configuredEnabled(config, installed.pkg, it.id, false)
                },
                totalNative = features.size,
                customCount = config?.customRules?.size ?: 0,
                isInspectorOn = config?.isInspectorEnabled ?: false,
                pluginId = plugin?.pluginId,
                pluginVersion = plugin?.pluginVersion,
                pluginName = plugin?.pluginName
            )
        }.sortedWith(compareByDescending<AppHookItemState> { it.pluginId != null }
            .thenByDescending { it.customCount > 0 }
            .thenByDescending { it.isInspectorOn }
            .thenBy { it.appName.lowercase(Locale.ROOT) })
        AppListSnapshot(apps, apps.count { it.pluginId != null })
    }

    /**
     * Maps plugin target packages onto installed applications. Package order follows the plugin
     * catalogue, so the first plugin targeting a package wins.
     */
    private fun resolvePluginTargets(packages: Set<String>): Map<String, PluginTargetApp> {
        val resolved = LinkedHashMap<String, PluginTargetApp>()
        for (target in catalog.pluginApps()) {
            if (target.pkg !in packages) continue
            resolved.putIfAbsent(target.pkg, target)
        }
        return resolved
    }
}
