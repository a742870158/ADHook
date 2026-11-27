package com.adhook.config.backup

import android.content.Context
import com.adhook.config.ConfigManager
import com.adhook.config.PluginBackupCodec
import com.adhook.config.subscription.SubscriptionManager
import com.adhook.config.security.ConfigSnapshotPolicy
import com.adhook.core.AdHookLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Configuration backup only. Host authorization grants are never exported or imported here. */
object BackupManager {
    const val BACKUP_TYPE = "adhook_backup"
    const val SCHEMA_VERSION = 3
    private const val MAX_BACKUP_LENGTH = 16 * 1024 * 1024

    data class BackupSummary(val createdAt: Long, val appVersion: String, val appCount: Int,
        val customRuleCount: Int, val subscriptionCount: Int, val warnings: List<String> = emptyList(),
        val pluginSourceCount: Int = 0, val installedPluginCount: Int = 0)
    data class RestoreResult(val appCount: Int, val customRuleCount: Int, val subscriptionCount: Int,
        val preRestoreBackupPath: String?, val warnings: List<String> = emptyList(),
        val pluginSourceCount: Int = 0, val installedPluginCount: Int = 0,
        val pendingPluginReinstall: Int = 0)

    private data class ValidatedBackup(val root: JSONObject, val summary: BackupSummary)

    /** Call on IO. Lock order is SubscriptionManager -> ConfigManager -> map. */
    fun createBackupJson(context: Context): String {
        val app = context.applicationContext
        val cm = ConfigManager.getInstance(app)
        val sub = SubscriptionManager.getInstance(app)
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "" }.getOrDefault("")
        // Plugin metadata is captured before configuration locks; it never includes binaries,
        // publisher trust pins or host authorization grants.
        val plugins = PluginBackupCodec.export(app)
        return synchronized(sub) { synchronized(cm) {
            envelope(cm, sub, version, plugins).toString(2)
        } }
    }

    private fun envelope(cm: ConfigManager, sub: SubscriptionManager, version: String,
                         plugins: org.json.JSONObject): JSONObject {
        val source = sub.exportBackupJson()
        return JSONObject().put("type", BACKUP_TYPE).put("schema", SCHEMA_VERSION)
            .put("created_at", System.currentTimeMillis()).put("app_version", version)
            .put("config", cm.snapshotJson())
            .put("subscriptions", source.getJSONArray("subscriptions"))
            .put("subscription_overrides", source.getJSONObject("subscription_overrides"))
            .put("subscription_meta", source.getJSONObject("subscription_meta"))
            .put(PluginBackupCodec.KEY, plugins)
    }

    /** Pure preflight: no singleton, file, package-manager access or writes. */
    private fun validate(json: String?): ValidatedBackup {
        require(!json.isNullOrBlank() && json.length <= MAX_BACKUP_LENGTH) { "备份为空或过大" }
        val root = JSONObject(json)
        require(root.getString("type") == BACKUP_TYPE && root.getInt("schema") in 1..SCHEMA_VERSION) { "备份类型或版本不兼容" }
        val config = root.getJSONObject("config")
        require(ConfigSnapshotPolicy.isValid(config)) { "备份主配置无效" }
        val subs = if (root.has("subscriptions")) root.getJSONArray("subscriptions") else JSONArray()
        val overrides = if (root.has("subscription_overrides")) root.getJSONObject("subscription_overrides") else JSONObject()
        val metadata = if (root.has("subscription_meta")) root.getJSONObject("subscription_meta") else JSONObject()
        require(SubscriptionManager.validateBackupJson(subs, overrides, metadata)) { "备份订阅或用户规则偏好无效" }
        val plugins = if (root.has(PluginBackupCodec.KEY)) {
            root.getJSONObject(PluginBackupCodec.KEY).also {
                require(PluginBackupCodec.validate(it)) { "备份插件元数据无效" }
            }
        } else null
        root.put("subscriptions", subs).put("subscription_overrides", overrides).put("subscription_meta", metadata)
        if (plugins != null) root.put(PluginBackupCodec.KEY, plugins)
        val apps = config.getJSONObject("apps")
        var rules = 0
        val packages = apps.keys()
        while (packages.hasNext()) rules += apps.getJSONObject(packages.next()).optJSONArray("customRules")?.length() ?: 0
        return ValidatedBackup(root, BackupSummary(root.optLong("created_at", 0), root.optString("app_version", "未知"),
            apps.length(), rules, subs.length(),
            pluginSourceCount = plugins?.optJSONArray("sources")?.length() ?: 0,
            installedPluginCount = plugins?.optJSONArray("installed")?.length() ?: 0))
    }

    fun summarize(jsonStr: String?): BackupSummary? = try { validate(jsonStr).summary } catch (_: Exception) { null }

    /** IO-thread-only restore, with complete preflight before even initializing stateful managers. */
    fun restore(context: Context, jsonStr: String): Result<RestoreResult> {
        val incoming = try { validate(jsonStr) } catch (error: Exception) { return Result.failure(error) }
        val app = context.applicationContext
        val cm = ConfigManager.getInstance(app)
        val sub = SubscriptionManager.getInstance(app)
        return try { synchronized(sub) { synchronized(cm) restore@{
            val previous = envelope(cm, sub, incoming.summary.appVersion, PluginBackupCodec.export(app))
            val preBackupPath = savePreRestoreSnapshot(app, previous.toString(2))
                ?: return@restore Result.failure<RestoreResult>(IllegalStateException("恢复前快照创建失败，未修改配置"))
            try {
                val pluginOutcome = applyEnvelope(incoming.root, cm, sub, app)
                val sum = incoming.summary
                Result.success(RestoreResult(sum.appCount, sum.customRuleCount, sum.subscriptionCount, preBackupPath,
                    sum.warnings + pluginOutcome.warnings, pluginOutcome.restoredSources,
                    pluginOutcome.restoredInstalled, pluginOutcome.pendingReinstall))
            } catch (error: Exception) {
                // Try every rollback step even if one fails. Keep the disk snapshot for manual recovery.
                val sourceRestored = runCatching { sub.restoreBackupJson(previous.getJSONArray("subscriptions"),
                    previous.getJSONObject("subscription_overrides"), previous.getJSONObject("subscription_meta")) }.getOrDefault(false)
                val configRestored = runCatching { cm.restoreSnapshot(previous.getJSONObject("config")) }.getOrDefault(false)
                // Plugin metadata has no durable pre-restore copy; the codec already rolls its own
                // partial writes back, and this re-apply covers the in-process state if it got further.
                runCatching { PluginBackupCodec.restore(app, previous.optJSONObject(PluginBackupCodec.KEY)) }
                val flushed = cm.awaitPendingWrites()
                val rolledBack = sourceRestored && configRestored && flushed
                AdHookLog.w("[BackupManager] Restore failed; rollback=$rolledBack")
                Result.failure(IllegalStateException(if (rolledBack) "恢复失败，已回滚原配置" else "恢复失败；回滚未完全成功，请使用恢复前快照：$preBackupPath", error))
            }
        } } } catch (error: Exception) {
            Result.failure(IllegalStateException("无法创建可靠的恢复前快照，原数据未被覆盖", error))
        }
    }

    private fun applyEnvelope(root: JSONObject, cm: ConfigManager, sub: SubscriptionManager,
                              context: android.content.Context): PluginBackupCodec.Outcome {
        check(sub.restoreBackupJson(root.getJSONArray("subscriptions"), root.getJSONObject("subscription_overrides"),
            root.getJSONObject("subscription_meta"))) { "订阅恢复失败" }
        check(cm.restoreSnapshot(root.getJSONObject("config"))) { "主配置恢复写入排队失败" }
        check(sub.applyToConfigManager()) { "规则偏好协调失败" }
        check(cm.awaitPendingWrites()) { "主配置落盘失败" }
        // Plugin metadata is applied last; binaries/trust/authorization never travel in a backup.
        return PluginBackupCodec.restore(context, root.optJSONObject(PluginBackupCodec.KEY))
    }

    private fun savePreRestoreSnapshot(context: Context, json: String): String? = try {
        val dir = File(context.filesDir, "backup")
        if (!dir.exists() && !dir.mkdirs()) null else {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())
            val file = File(dir, "auto_before_restore_$timestamp.json")
            FileOutputStream(file).use { it.write(json.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            file.absolutePath
        }
    } catch (error: Exception) {
        AdHookLog.w("[BackupManager] 恢复前快照失败: ${error.javaClass.simpleName}")
        null
    }
}
