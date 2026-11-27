package com.adhook.config.subscription

import android.content.Context
import com.adhook.config.ConfigManager
import android.util.AtomicFile
import com.adhook.config.model.CustomRule
import com.adhook.config.model.Subscription
import com.adhook.config.model.SubscriptionApp
import com.adhook.config.model.SubscriptionRuleItem
import com.adhook.core.AdHookLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Source definitions, ownership catalogue and independent user overrides.
 * Lock order: this -> ConfigManager -> its appConfigs map. ConfigManager must not call back here.
 */
class SubscriptionManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val subFile = File(appContext.filesDir, "subscriptions.json")
    private val overrideFile = File(appContext.filesDir, "subscription_overrides.json")
    private val remoteMetaFile = File(appContext.filesDir, "subscription_meta.json")
    private val subscriptions = mutableListOf<Subscription>()
    private var preferences = RulePreferences()
    private val knownRules = linkedMapOf<String, ManagedRule>()
    private val remoteMeta = mutableMapOf<String, RemoteMeta>()
    private var overrideStateDirty = false
    private var overrideStateHealthy = true
    private val inFlightUrls = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var pendingLoadWarning: String? = null
    private var reconcileWarnings: List<String> = emptyList()
    private var transactionDepth = 0
    /** Fault boundary for persistence-adapter regression tests; production leaves it unset. */
    internal var beforeFileWrite: ((File) -> Unit)? = null
    /** Uses the same production transport; tests inject fake connections, never real network. */
    internal var downloader = SubscriptionDownloader()

    private data class SourceSnapshot(val json: JSONObject, val dirty: Boolean,
        val healthy: Boolean, val warning: String?)
    private data class FileSnapshot(val file: File, val text: String?)

    private fun sourceSnapshot() = SourceSnapshot(exportBackupJson(), overrideStateDirty,
        overrideStateHealthy, pendingLoadWarning)

    private fun restoreSourceMemory(previous: SourceSnapshot) {
        val root = previous.json
        subscriptions.clear(); subscriptions.addAll(parseSubscriptions(root.getJSONArray("subscriptions")))
        val overrides = root.getJSONObject("subscription_overrides")
        preferences = RulePreferences.fromJson(overrides)
        knownRules.clear(); readKnownRules(overrides).forEach { knownRules[it.identity.key] = it }
        remoteMeta.clear(); remoteMeta.putAll(readRemoteMeta(root.getJSONObject("subscription_meta")))
        reconcileWarnings = readMigrationWarnings(overrides)
        overrideStateDirty = previous.dirty
        overrideStateHealthy = previous.healthy
        pendingLoadWarning = previous.warning
    }

    private fun captureFiles(files: List<File>): List<FileSnapshot> = files.map { file ->
        FileSnapshot(file, if (file.exists() || File(file.path + ".bak").exists()) readText(file) else null)
    }

    private fun rollbackFiles(previous: List<FileSnapshot>): Boolean {
        var complete = true
        for ((file, text) in previous) {
            val restored = if (text != null) writeTextAtomically(file, text) else runCatching {
                AtomicFile(file).delete()
                !file.exists() && !File(file.path + ".bak").exists()
            }.getOrDefault(false)
            complete = restored && complete
        }
        return complete
    }

    /** In-process all-or-rollback; not a power-loss transaction across three separate files. */
    private fun sourceTransaction(checkCurrent: () -> Unit = {}, action: () -> Boolean): Boolean {
        if (!overrideStateHealthy) return false
        checkCurrent()
        if (transactionDepth > 0) return action()
        // Timeout is not cancellation: leave ALL source state untouched while an old config write is unresolved.
        if (!ConfigManager.getInstance(appContext).awaitWriteQueueIdle()) {
            pendingLoadWarning = "配置写入队列尚未完成，订阅修改已拒绝；旧写入不保证撤回，请稍后重试"
            return false
        }
        checkCurrent()
        val previous: SourceSnapshot
        val files: List<FileSnapshot>
        try {
            previous = sourceSnapshot()
            files = captureFiles(listOf(subFile, overrideFile, remoteMetaFile))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            AdHookLog.e("[SubscriptionManager] Cannot snapshot sources; update refused", failure)
            pendingLoadWarning = "订阅原状态无法完整读取，更新已拒绝；原文件保留，请检查或恢复完整备份"
            return false
        }
        transactionDepth++
        var committed = false
        try {
            committed = action()
            return committed
        } catch (failure: Throwable) {
            AdHookLog.e("[SubscriptionManager] Source transaction failed", failure)
            return false
        } finally {
            transactionDepth--
            if (!committed) {
                restoreSourceMemory(previous)
                if (!rollbackFiles(files)) {
                    overrideStateHealthy = false
                    pendingLoadWarning = "订阅保存失败且磁盘回滚未完整成功；旧内存已保留，已暂停订阅修改，请检查原文件并恢复完整备份"
                    AdHookLog.w("[SubscriptionManager] Source rollback incomplete; further mutations paused")
                }
            }
        }
    }

    init {
        val state = loadSubscriptions()
        loadOverrideState()
        if (state == LoadState.OK) applyToConfigManager()
    }

    companion object {
        const val LOCAL_SUBSCRIPTION_ID = 1L
        @JvmStatic fun validateBackupJson(subsArr: JSONArray?, overrideObj: JSONObject?, metaObj: JSONObject?): Boolean =
            SubscriptionBackupCodec.validate(subsArr, overrideObj, metaObj)
        @Volatile private var instance: SubscriptionManager? = null
        fun getInstance(context: Context): SubscriptionManager = instance ?: synchronized(this) {
            instance ?: SubscriptionManager(context).also { instance = it }
        }
    }

    private enum class LoadState { MISSING, OK, CORRUPT }
    private data class RemoteMeta(val version: Int, val updatedAt: Long)

    @Synchronized fun consumeLoadWarning(): String? = pendingLoadWarning.also { pendingLoadWarning = null }
    @Synchronized fun getReconcileWarnings(): List<String> = reconcileWarnings.toList()
    @Synchronized fun getSubscriptions(): List<Subscription> = subscriptions.map { it.detachedCopy() }

    @Synchronized fun addSubscription(sub: Subscription): Boolean {
        try {
            validateSubscription(sub)
            // A URL is not an identity alias. A changed publisher ID must not silently replace another owner.
            require(subscriptions.none { it.updateUrl.isNotBlank() && it.updateUrl == sub.updateUrl && it.id != sub.id }) {
                "同一订阅地址返回了不同 id，请先确认订阅来源"
            }
        } catch (t: IllegalArgumentException) {
            pendingLoadWarning = t.message
            AdHookLog.w("[SubscriptionManager] ${t.message}")
            return false
        }
        return sourceTransaction {
            // Preserve withdrawn legacy definitions before replacing their owner.
            rememberDefinitions(remoteDefinitions())
            val index = subscriptions.indexOfFirst { it.id == sub.id }
            val stored = sub.detachedCopy().let { incoming ->
                if (index >= 0) incoming.copy(isEnabled = subscriptions[index].isEnabled) else incoming
            }
            if (index >= 0) subscriptions[index] = stored else subscriptions.add(stored)
            save() && applyToConfigManager()
        }
    }

    @Synchronized fun setSubscriptionEnabled(id: Long, enabled: Boolean): Boolean {
        if (id == LOCAL_SUBSCRIPTION_ID) return setLocalRulesEnabled(enabled)
        val sub = subscriptions.find { it.id == id } ?: return false
        return sourceTransaction {
            sub.isEnabled = enabled
            save() && applyToConfigManager()
        }
    }

    @Synchronized fun deleteSubscription(id: Long): Boolean {
        if (id == LOCAL_SUBSCRIPTION_ID) return false
        val index = subscriptions.indexOfFirst { it.id == id }
        if (index < 0) return false
        return sourceTransaction {
            rememberDefinitions(remoteDefinitions())
            subscriptions.removeAt(index)
            // Tombstones survive source removal, but only after the matching projection commits.
            save() && applyToConfigManager()
        }
    }

    @Synchronized fun getLocalSubscription(): Subscription {
        val cm = ConfigManager.getInstance(appContext)
        val apps = mutableListOf<SubscriptionApp>()
        val enabled: Boolean
        synchronized(cm) {
            enabled = cm.globalConfig.isLocalRulesEnabled
            val configs = cm.allAppConfigs
            synchronized(configs) {
                for ((pkg, config) in configs) {
                    val rules = config.customRules.filter {
                        (it.source == null || it.source == CustomRule.SOURCE_PICKER) && RuleIdentity.fromRule(it) == null
                    }.map { SubscriptionRuleItem.fromCustomRule(it) }
                    if (rules.isEmpty()) continue
                    val name = try {
                        val pm = appContext.packageManager
                        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                    } catch (_: Throwable) { pkg }
                    apps.add(SubscriptionApp(pkg, name, rules))
                }
            }
        }
        val count = apps.sumOf { it.rules.size }
        return Subscription(LOCAL_SUBSCRIPTION_ID, "本地自定义规则", 1, "本机拾取",
            if (apps.isEmpty()) "用户屏幕拾取 · 暂无本地规则" else "用户屏幕拾取 · 涵盖 ${apps.size} 个应用 · 共 $count 条规则",
            "", isEnabled = enabled, apps = apps, isLocal = true)
    }

    /** The master switch gates execution; it never overwrites a per-rule choice. */
    @Synchronized fun setLocalRulesEnabled(enabled: Boolean): Boolean = try {
        val cm = ConfigManager.getInstance(appContext)
        synchronized(cm) {
            if (!cm.awaitWriteQueueIdle()) return@synchronized false
            val previous = cm.globalConfig.isLocalRulesEnabled
            cm.globalConfig.isLocalRulesEnabled = enabled
            if (cm.saveSynchronously()) true else { cm.globalConfig.isLocalRulesEnabled = previous; false }
        }
    } catch (t: Throwable) {
        AdHookLog.e("[SubscriptionManager] setLocalRulesEnabled failed", t)
        false
    }

    private fun validateSubscription(sub: Subscription) = SubscriptionBackupCodec.validateSubscription(sub)
    private fun parseSubscriptions(arr: JSONArray): List<Subscription> = SubscriptionBackupCodec.parseSubscriptions(arr)

    private fun remoteDefinitions(): List<ManagedRule> = subscriptions.flatMap { sub ->
        sub.apps.flatMap { app -> app.rules.map { item ->
            val identity = RuleIdentity(CustomRule.SOURCE_REMOTE, sub.id.toString(), app.id, item.id)
            ManagedRule(identity, item.toCustomRule(CustomRule.SOURCE_REMOTE), sub.isEnabled && item.enabled)
        } }
    }

    private fun rememberDefinitions(definitions: List<ManagedRule>) {
        definitions.forEach { knownRules[it.identity.key] = it.detachedCopy() }
        overrideStateDirty = true
    }

    @Synchronized private fun loadSubscriptions(): LoadState {
        if (!subFile.exists() && !File(subFile.path + ".bak").exists()) return LoadState.MISSING
        return try {
            subscriptions.addAll(parseSubscriptions(JSONArray(readText(subFile))))
            LoadState.OK
        } catch (t: Throwable) {
            AdHookLog.e("[SubscriptionManager] Failed to load subscriptions", t)
            val backup = File(subFile.parentFile, subFile.name + ".corrupt-" + System.currentTimeMillis())
            val copied = runCatching { subFile.copyTo(backup); true }.getOrDefault(false)
            pendingLoadWarning = if (copied) "订阅配置损坏，已备份为 ${backup.name}，请重新导入" else "订阅配置损坏，备份失败；原文件已保留"
            LoadState.CORRUPT
        }
    }

    private fun readKnownRules(root: JSONObject?): List<ManagedRule> = SubscriptionBackupCodec.readKnownRules(root)

    private fun readRemoteMeta(root: JSONObject?): Map<String, RemoteMeta> =
        SubscriptionBackupCodec.readMeta(root).mapValues { (_, value) -> RemoteMeta(value.first, value.second) }

    @Synchronized private fun loadOverrideState() {
        if (overrideFile.exists() || File(overrideFile.path + ".bak").exists()) {
            try {
                val root = JSONObject(readText(overrideFile))
                val parsedPrefs = RulePreferences.fromJson(root)
                val parsedKnown = readKnownRules(root)
                preferences = parsedPrefs
                parsedKnown.forEach { knownRules[it.identity.key] = it }
                reconcileWarnings = readMigrationWarnings(root)
                if (reconcileWarnings.isNotEmpty()) pendingLoadWarning = migrationWarningSummary(reconcileWarnings)
            } catch (t: Throwable) {
                AdHookLog.e("[SubscriptionManager] Failed to load rule overrides", t)
                overrideStateHealthy = false
                pendingLoadWarning = "订阅覆盖偏好损坏，原文件已保留；已暂停订阅同步，请恢复备份"
                runCatching { overrideFile.copyTo(File(overrideFile.path + ".corrupt-" + System.currentTimeMillis())) }
            }
        }
        if (remoteMetaFile.exists() || File(remoteMetaFile.path + ".bak").exists()) {
            try { remoteMeta.putAll(readRemoteMeta(JSONObject(readText(remoteMetaFile)))) }
            catch (t: Throwable) { AdHookLog.e("[SubscriptionManager] Failed to load remote meta", t) }
        }
    }

    private fun overrideJson(): JSONObject = preferences.toJson().apply {
        put("knownRules", JSONArray().also { arr -> knownRules.values.forEach { arr.put(it.toJson()) } })
        put("migrationWarnings", JSONArray(reconcileWarnings))
    }

    private fun migrationWarningSummary(warnings: List<String>): String =
        "订阅同步发现 ${warnings.size} 个历史归属/规则问题：\n" + warnings.take(3).joinToString("\n") +
            "\n请在对应应用详情页核对旧本地规则；确认不需要则删除，需要则保留为本地规则。远程规则已使用独立身份。"

    private fun readMigrationWarnings(root: JSONObject?): List<String> = SubscriptionBackupCodec.readWarnings(root)

    private fun metaJson(): JSONObject = JSONObject().put("entries", JSONObject().also { entries ->
        remoteMeta.forEach { (url, meta) -> entries.put(url,
            JSONObject().put("version", meta.version).put("updatedAt", meta.updatedAt)) }
    })

    @Synchronized fun save(): Boolean = sourceTransaction {
        val arr = JSONArray().also { array -> subscriptions.forEach { array.put(it.toJson()) } }
        val saved = writeTextAtomically(subFile, arr.toString(2)) && persistOverrideState()
        saved
    }

    private fun persistOverrideState(): Boolean {
        if (!overrideStateHealthy) return false
        if (!overrideStateDirty) return true
        return sourceTransaction {
            val saved = writeTextAtomically(overrideFile, overrideJson().toString(2)) &&
                writeTextAtomically(remoteMetaFile, metaJson().toString(2))
            if (saved) overrideStateDirty = false
            saved
        }
    }

    /** Call outside the ConfigManager monitor, before publishing the matching UI edit. */
    @Synchronized fun markRuleToggled(pkg: String, rule: CustomRule, enabled: Boolean): Boolean {
        if (preferences.isProtectedLocal(pkg, rule)) return true
        val identity = RuleReconciler.resolveLegacy(pkg, rule, knownRules.values + remoteDefinitions()) ?: return true
        return sourceTransaction {
            preferences.setEnabled(identity.key, enabled)
            overrideStateDirty = true
            persistOverrideState()
        }
    }

    @Synchronized fun markRuleRemoved(pkg: String, rule: CustomRule): Boolean {
        if (preferences.isProtectedLocal(pkg, rule)) return true
        val identity = RuleReconciler.resolveLegacy(pkg, rule, knownRules.values + remoteDefinitions()) ?: return true
        return sourceTransaction {
            preferences.remove(identity.key)
            overrideStateDirty = true
            persistOverrideState()
        }
    }

    /** UI edits commit preference and projection together; no optimistic second ConfigManager save. */
    @Synchronized fun setRuleEnabled(pkg: String, id: String, enabled: Boolean): Boolean = editRule(pkg, id, enabled)
    @Synchronized fun removeRule(pkg: String, id: String): Boolean = editRule(pkg, id, null)

    private fun editRule(pkg: String, id: String, enabled: Boolean?): Boolean = sourceTransaction {
        val cm = ConfigManager.getInstance(appContext)
        synchronized(cm) {
            if (!cm.awaitWriteQueueIdle()) return@synchronized false
            val configs = cm.allAppConfigs
            val snapshot = cm.snapshotAppConfigs()
            var committed = false
            try {
                val config = configs[pkg] ?: return@synchronized false
                val rule = config.customRules.firstOrNull { it.id == id } ?: return@synchronized false
                if (!preferences.isProtectedLocal(pkg, rule)) {
                    val identity = RuleReconciler.resolveLegacy(pkg, rule, knownRules.values + remoteDefinitions())
                    if (identity != null) {
                        if (enabled == null) preferences.remove(identity.key) else preferences.setEnabled(identity.key, enabled)
                        overrideStateDirty = true
                    }
                }
                if (!persistOverrideState()) return@synchronized false
                if (enabled == null) config.customRules.remove(rule) else rule.isEnabled = enabled
                committed = cm.saveSynchronously()
                committed
            } finally {
                if (!committed) synchronized(configs) { configs.clear(); configs.putAll(snapshot) }
            }
        }
    }

    /** Compatibility for old callers: resolve only a globally unique ID, never write a local ID tombstone. */
    @Synchronized fun markRuleToggled(ruleId: String?, enabled: Boolean): Boolean {
        val identity = resolveUniqueId(ruleId) ?: return false
        return sourceTransaction {
            preferences.setEnabled(identity.key, enabled)
            overrideStateDirty = true
            persistOverrideState()
        }
    }

    @Synchronized fun markRuleRemoved(ruleId: String?): Boolean {
        val identity = resolveUniqueId(ruleId) ?: return false
        return sourceTransaction {
            preferences.remove(identity.key)
            overrideStateDirty = true
            persistOverrideState()
        }
    }

    private fun resolveUniqueId(id: String?): RuleIdentity? {
        RuleIdentity.fromKey(id)?.let { return it }
        if (id.isNullOrBlank()) return null
        return (knownRules.values + remoteDefinitions()).filter { id in it.aliases }
            .map { it.identity }.distinct().singleOrNull()
    }

    @Synchronized fun exportBackupJson(): JSONObject = JSONObject().apply {
        check(overrideStateHealthy) { "订阅偏好损坏，无法导出完整备份；原文件已保留" }
        put("subscriptions", JSONArray().also { arr -> subscriptions.forEach { arr.put(it.toJson()) } })
        put("subscription_overrides", overrideJson())
        put("subscription_meta", metaJson())
    }

    /** Compatible keys; absent old override blocks mean empty preferences. Do not apply until config restore. */
    @Synchronized fun restoreBackupJson(subsArr: JSONArray?, overrideObj: JSONObject?, metaObj: JSONObject?): Boolean {
        try {
            // Validate the entire snapshot before writing any of its files or touching live state.
            val parsedSubs = parseSubscriptions(subsArr ?: JSONArray())
            val parsedPrefs = RulePreferences.fromJson(overrideObj)
            val parsedKnown = readKnownRules(overrideObj)
            val parsedWarnings = readMigrationWarnings(overrideObj)
            val parsedMeta = readRemoteMeta(metaObj)
            val files = listOf(subFile, overrideFile, remoteMetaFile)
            val previousFiles = captureFiles(files)
            val texts = listOf((subsArr ?: JSONArray()).toString(2),
                (overrideObj ?: JSONObject()).toString(2), (metaObj ?: JSONObject()).toString(2))
            for (i in files.indices) {
                if (!writeTextAtomically(files[i], texts[i])) {
                    if (!rollbackFiles(previousFiles)) {
                        overrideStateHealthy = false
                        pendingLoadWarning = "订阅恢复失败且磁盘回滚未完整成功；旧内存保留，已暂停订阅修改，请恢复完整备份"
                        AdHookLog.w("[SubscriptionManager] Backup source rollback incomplete")
                    }
                    return false
                }
            }
            subscriptions.clear(); subscriptions.addAll(parsedSubs)
            preferences = parsedPrefs
            knownRules.clear(); parsedKnown.forEach { knownRules[it.identity.key] = it }
            remoteMeta.clear(); remoteMeta.putAll(parsedMeta)
            overrideStateDirty = false
            overrideStateHealthy = true
            reconcileWarnings = parsedWarnings
            pendingLoadWarning = parsedWarnings.takeIf { it.isNotEmpty() }?.let { migrationWarningSummary(it) }
            return true
        } catch (t: Throwable) {
            AdHookLog.e("[SubscriptionManager] Failed to restore backup json", t)
            return false
        }
    }

    /** Full replacement by ownership handles source updates, withdrawals, disabled groups and empty packages. */
    @Synchronized fun applyToConfigManager(): Boolean = reconcile(CustomRule.SOURCE_REMOTE, remoteDefinitions())

    private fun reconcile(namespace: String, incoming: List<ManagedRule>): Boolean = sourceTransaction {
        val cm = ConfigManager.getInstance(appContext)
        synchronized(cm) {
            // A timed-out old queued write is not cancelled. Refuse before changing the projection.
            if (!cm.awaitWriteQueueIdle()) return@synchronized false
            val configs = cm.allAppConfigs
            val snapshot = cm.snapshotAppConfigs()
            val rulesBefore = snapshot.mapValues { (_, config) -> config.customRules.map { it.copy() } }
            var committed = false
            try {
                val result = RuleReconciler.reconcile(namespace, rulesBefore, incoming, knownRules.values.toList(), preferences)
                preferences = result.preferences
                knownRules.clear(); result.knownRules.forEach { knownRules[it.identity.key] = it }
                overrideStateDirty = true
                if (result.diagnostics.isNotEmpty() && result.diagnostics != reconcileWarnings) {
                    pendingLoadWarning = migrationWarningSummary(result.diagnostics)
                }
                reconcileWarnings = result.diagnostics
                result.diagnostics.forEach { AdHookLog.w("[SubscriptionManager] $it") }
                // All source files precede the LAST, synchronous local config commit.
                if (!persistOverrideState()) return@synchronized false
                synchronized(configs) {
                    for ((pkg, rules) in result.rulesByPackage) {
                        val config = cm.getAppConfig(pkg) ?: error("Invalid reconciled package: $pkg")
                        config.customRules.clear()
                        config.customRules.addAll(rules)
                    }
                }
                committed = cm.saveSynchronously()
                committed
            } finally {
                if (!committed) synchronized(configs) {
                    configs.clear(); configs.putAll(snapshot)
                }
            }
        }
    }

    private fun readText(file: File): String = AtomicFile(file).openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    private fun writeTextAtomically(target: File, text: String): Boolean {
        val atomic = AtomicFile(target)
        var stream: FileOutputStream? = null
        return try {
            beforeFileWrite?.invoke(target)
            stream = atomic.startWrite()
            stream.write(text.toByteArray(StandardCharsets.UTF_8))
            stream.fd.sync()
            try { atomic.finishWrite(stream) } finally { stream = null }
            // finishWrite is void and may only log a failed rename. Verify the committed payload.
            readText(target) == text
        } catch (t: Throwable) {
            // Never hand a stream already closed by finishWrite back to failWrite.
            if (stream != null) runCatching { atomic.failWrite(stream) }
            AdHookLog.e("[SubscriptionManager] Failed to write ${target.name}", t)
            false
        }
    }

    suspend fun updateFromUrl(urlStr: String): Result<Subscription> {
        val requestUrl = urlStr.trim()
        if (!isSupportedUrl(requestUrl)) return Result.failure(IllegalArgumentException("仅支持 http/https 订阅地址"))
        if (!inFlightUrls.add(requestUrl)) return Result.failure(IllegalStateException("该订阅正在更新中，请稍候"))
        return try { withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            try {
                val downloaded = runInterruptible { downloader.download(requestUrl) { context.isActive } }
                context.ensureActive()
                applyDownloadedSubscription(requestUrl, downloaded) { context.ensureActive() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
        } }
        finally { inFlightUrls.remove(requestUrl) }
    }

    /** Same downloaded-payload commit used by HTTP and offline persistence regressions. */
    @Synchronized internal fun applyDownloadedSubscription(requestUrl: String, downloaded: String,
        checkCurrent: () -> Unit = {}): Result<Subscription> = try {
        checkCurrent()
        require(isSupportedUrl(requestUrl)) { "仅支持 http/https 订阅地址" }
        val root = com.adhook.config.security.ConfigImportPolicy.parseObject(downloaded)
        val finalSub = SubscriptionBackupCodec.parseSubscription(root).copy(updateUrl = requestUrl)
        evaluateRemoteFreshness(finalSub, root)?.let { error(it) }
        finalSub.updatedAt = System.currentTimeMillis()
        check(sourceTransaction(checkCurrent) {
            // Freshness metadata is part of the SAME source/config commit, not a best-effort tail write.
            rememberRemoteMeta(finalSub, root)
            addSubscription(finalSub)
        }) { "订阅已下载但本地提交失败；未报告更新成功，请检查提示并重试" }
        Result.success(finalSub.detachedCopy())
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { Result.failure(failure) }

    private fun evaluateRemoteFreshness(incoming: Subscription, root: JSONObject): String? {
        val existing = subscriptions.find { it.id == incoming.id }
        if (existing != null && incoming.version < existing.version) return "远端版本 v${incoming.version} 低于本地 v${existing.version}，已忽略旧规则"
        val updatedAt = root.optLong("updatedAt", 0L)
        val previous = remoteMeta[incoming.updateUrl] ?: return null
        if (updatedAt > 0L && incoming.version <= previous.version && updatedAt < previous.updatedAt) return "远端规则时间戳早于上次更新，已忽略旧响应"
        return null
    }

    private fun rememberRemoteMeta(sub: Subscription, root: JSONObject) {
        val updatedAt = root.optLong("updatedAt", 0L)
        remoteMeta[sub.updateUrl] = RemoteMeta(sub.version, if (updatedAt > 0L) updatedAt else remoteMeta[sub.updateUrl]?.updatedAt ?: 0L)
        overrideStateDirty = true
    }

    private fun isSupportedUrl(value: String): Boolean = SubscriptionDownloader.isSupportedUrl(value)
}
