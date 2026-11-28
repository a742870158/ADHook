package com.adhook.ui.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.adhook.config.backup.BackupManager
import com.adhook.config.ConfigManager
import com.adhook.config.subscription.SubscriptionManager
import com.adhook.config.model.AppConfig
import com.adhook.config.model.CustomRule
import com.adhook.config.model.FeatureMeta
import com.adhook.config.model.GlobalConfig
import com.adhook.config.model.Subscription
import com.adhook.ui.ImportExportHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface ConsoleDataSource {
    suspend fun load(): ConsoleSnapshot
    suspend fun setSdkEnabled(sdk: String, enabled: Boolean): Boolean
}

data class ConsoleSnapshot(
    val apps: List<AppHookItemState> = emptyList(),
    val globalConfig: GlobalConfig = GlobalConfig(),
    val subscriptions: List<Subscription> = emptyList(),
    val appVersion: String = "",
    val warning: String? = null,
    val migrationWarnings: List<String> = emptyList(),
    val pluginCount: Int = 0
)

data class AppDetailSnapshot(
    val packageName: String,
    val appName: String,
    val config: AppConfig,
    val features: List<FeatureMeta>,
    val stats: ConfigManager.AppRuntimeStats?,
    /** Remote commit acknowledgement is distinct from a host consuming the new version. */
    val remotePublication: com.adhook.config.RemotePublicationState? = null,
    val excludeFromRecents: Boolean = false,
    /** Installed plugin targeting this package (enabled or not); null for generic-only hosts. */
    val plugin: PluginInstallUi? = null
)

/**
 * UI's only data boundary. Lazy managers are initialized inside IO, never in Activity/remember.
 * Config operations serialize here; cross-entry correctness is protected by ConfigManager's monitor.
 */
class ConsoleRepository(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) : ConsoleDataSource {
    private val appContext = context.applicationContext
    private val manager by lazy { ConfigManager.getInstance(appContext) }
    private val subscriptions by lazy { SubscriptionManager.getInstance(appContext) }
    private val pluginRepository by lazy { PluginRepository(appContext, io) }
    private val writes = Mutex()
    private val icons = android.util.LruCache<String, ImageBitmap>(80)

    private val catalog = object : AppCatalog {
        override fun installedApps(): List<InstalledApp> {
            val pm = appContext.packageManager
            return pm.getInstalledPackages(0).mapNotNull { info ->
                val app = info.applicationInfo ?: return@mapNotNull null
                InstalledApp(info.packageName, runCatching { app.loadLabel(pm).toString() }.getOrDefault(info.packageName),
                    app.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            }
        }
        override fun pluginApps(): List<PluginTargetApp> {
            // Installed plugin targets are deep-hook catalog entries even before a host authorizes.
            val store = com.adhook.plugin.PluginStore.get(appContext)
            return store.list().flatMap { plugin ->
                plugin.targets.map { target ->
                    PluginTargetApp(
                        pkg = target.packageName,
                        label = target.label.ifBlank { target.packageName },
                        pluginId = plugin.id,
                        pluginName = plugin.name,
                        pluginVersion = plugin.version,
                        features = plugin.features.map { it.toFeatureMeta() }
                    )
                }
            }
        }
    }
    private val appList by lazy { AppListRepository(catalog, AppConfigReader { manager.snapshotAppConfigs() }, io) }

    override suspend fun load(): ConsoleSnapshot = withContext(io) {
        // Subscription initialization may reconcile; do it before taking detached configuration snapshots.
        val subscriptionList = listOf(subscriptions.getLocalSubscription()) + subscriptions.getSubscriptions()
        val apps = appList.load()
        val global = manager.globalConfigSnapshot
        ConsoleSnapshot(apps.apps, global, subscriptionList,
            runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "" }.getOrDefault(""),
            subscriptions.consumeLoadWarning(), subscriptions.getReconcileWarnings(),
            pluginCount = apps.pluginCount)
    }

    override suspend fun setSdkEnabled(sdk: String, enabled: Boolean): Boolean = mutate {
        manager.setSdkEnabled(sdk, enabled)
    }

    suspend fun loadDetail(requestedPackage: String): AppDetailSnapshot = withContext(io) {
        val pkg = requestedPackage
        val config = requireNotNull(manager.getAppConfigSnapshot(pkg)) { "无效包名" }
        val plugin = pluginRepository.pluginForPackage(pkg)
        val name = runCatching {
            val pm = appContext.packageManager
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        }.getOrElse { plugin?.name ?: pkg }
        val features = plugin?.features?.map { it.toFeatureMeta() }.orEmpty()
        val pluginPrimary = plugin?.targets?.firstOrNull {
            it.coveredPackages().contains(pkg)
        }?.let { target -> target.primary.ifBlank { target.packageName } }
        com.adhook.core.FeatureGuard.registerCatalog(pkg, pluginPrimary, features)
        AppDetailSnapshot(pkg, name, config, features, manager.getRuntimeStats(pkg),
            manager.remotePublicationState, manager.isExcludeFromRecents, plugin)
    }

    fun observeRuntimeStats(pkg: String): kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.callbackFlow {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        val uri = com.adhook.config.ConfigProvider.CONTENT_URI.buildUpon()
            .appendPath("runtime-stats").appendPath(pkg).build()
        appContext.contentResolver.registerContentObserver(uri, false, observer)
        val publicationUri = com.adhook.config.ConfigProvider.CONTENT_URI.buildUpon()
            .appendPath("remote-publication").build()
        appContext.contentResolver.registerContentObserver(publicationUri, false, observer)
        awaitClose { appContext.contentResolver.unregisterContentObserver(observer) }
    }.conflate()

    suspend fun setInspectorEnabled(pkg: String, enabled: Boolean): Boolean = mutate { manager.setInspectorEnabled(pkg, enabled) }
    suspend fun setFeatureEnabled(pkg: String, id: String, enabled: Boolean): Boolean = mutate { manager.setFeatureEnabled(pkg, id, enabled) }

    suspend fun setRuleEnabled(pkg: String, id: String, enabled: Boolean): Boolean = mutate {
        subscriptions.setRuleEnabled(pkg, id, enabled)
    }

    suspend fun removeRule(pkg: String, id: String): Boolean = mutate {
        subscriptions.removeRule(pkg, id)
    }

    suspend fun setSubscriptionEnabled(id: Long, enabled: Boolean): Boolean = mutate { subscriptions.setSubscriptionEnabled(id, enabled) }
    suspend fun deleteSubscription(id: Long): Boolean = mutate { subscriptions.deleteSubscription(id) }
    suspend fun updateSubscription(url: String): Result<Subscription> = withContext(io) { subscriptions.updateFromUrl(url) }

    suspend fun importRules(uri: Uri): String = mutate {
        val json = ImportExportHelper.readTextFromUri(appContext, uri)?.takeIf { it.isNotBlank() }
            ?: error("选中的文件为空或无法读取")
        manager.importJson(json)
    }
    suspend fun exportLocalRules(): String? = withContext(io) {
        runCatching {
            ImportExportHelper.exportToJsonFile(appContext, "adhook_local_rules", subscriptions.getLocalSubscription().toJson().toString(2))
        }.getOrNull()
    }
    suspend fun readBackup(uri: Uri): Pair<String, BackupManager.BackupSummary>? = withContext(io) {
        val json = ImportExportHelper.readTextFromUri(appContext, uri) ?: return@withContext null
        val summary = BackupManager.summarize(json) ?: return@withContext null
        json to summary
    }
    suspend fun exportBackup(): String? = withContext(io) {
        runCatching {
            ImportExportHelper.exportToJsonFile(appContext, "adhook_backup", BackupManager.createBackupJson(appContext))
        }.getOrNull()
    }
    suspend fun restoreBackup(json: String): Result<BackupManager.RestoreResult> = mutate {
        try { BackupManager.restore(appContext, json) }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun loadIcon(pkg: String): ImageBitmap? = withContext(io) {
        icons.get(pkg)?.let { return@withContext it }
        runCatching {
            val drawable = appContext.packageManager.getApplicationIcon(pkg)
            // Bound each cached allocation, even for a package with an oversized icon.
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 128, 128)
            drawable.draw(Canvas(bitmap))
            bitmap.asImageBitmap().also { icons.put(pkg, it) }
        }.getOrNull()
    }

    private suspend fun <T> mutate(block: () -> T): T = withContext(io) { writes.withLock { block() } }
}
