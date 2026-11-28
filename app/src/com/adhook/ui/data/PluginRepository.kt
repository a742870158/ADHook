package com.adhook.ui.data

import android.content.Context
import android.net.Uri
import com.adhook.config.model.FeatureMeta
import com.adhook.plugin.InstallError
import com.adhook.plugin.InstalledPlugin
import com.adhook.plugin.PluginArtifactPublisher
import com.adhook.plugin.PluginConstants
import com.adhook.plugin.PluginFeature
import com.adhook.plugin.PluginInstaller
import com.adhook.plugin.PluginManifest
import com.adhook.plugin.PluginMarketCache
import com.adhook.plugin.PluginMarketClient
import com.adhook.plugin.PluginMarketEntry
import com.adhook.plugin.PluginMarketIndex
import com.adhook.plugin.PluginMarketInstallPolicy
import com.adhook.plugin.PluginMarketRelease
import com.adhook.plugin.PluginMarketSource
import com.adhook.plugin.PluginPreflight
import com.adhook.plugin.PluginSignature
import com.adhook.plugin.PluginSourceStore
import com.adhook.plugin.PluginStore
import com.adhook.plugin.PluginTarget
import com.adhook.plugin.PluginTrustStore
import com.adhook.plugin.PluginZip
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Read/install facade for the host-plugin subsystem.
 *
 * <p>Every disk, network and installer call runs on [io]; mutating operations are serialized by a
 * [Mutex] so a preview can never race an install or a source rewrite. Nothing here reports success
 * before the installer returns [InstallError.OK], and no method writes host configuration, grants a
 * host authorization or opens a Hook switch.</p>
 */
data class PluginInstallUi(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    val description: String,
    val targets: List<PluginTarget>,
    val features: List<PluginFeature>,
    val sourceId: String,
    val publisherKeyId: String,
    val publisherFingerprint: String,
    val installedAt: Long,
    val abi: Int,
    val minBaseVersionCode: Int,
    /** Installation files (dex) are still present; false means the record must be reinstalled. */
    val filesPresent: Boolean = true,
    /** Catalogue could not be read safely; every write is frozen until recovered. */
    val storeHealthy: Boolean = true
)

data class PluginDetailUi(
    val plugin: PluginInstallUi,
    val authorUrl: String,
    val readme: String,
    val readmeError: String?
)

data class MarketSourceUi(
    val id: Long,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val lastUpdated: Long,
    val lastError: String
)

data class MarketEntryUi(
    val id: String,
    val name: String,
    val summary: String,
    val author: String,
    val targets: List<String>,
    val version: String,
    val versionCode: Int,
    val zipUrl: String,
    val sha256: String,
    val size: Long,
    val publisherKeyId: String,
    val publisherFingerprint: String,
    val changelog: String,
    val sourceId: Long,
    val sourceName: String,
    val installedVersionCode: Int?,
    val updateAvailable: Boolean,
    /** Release ABI/minBaseVersionCode fits this base; incompatible entries can never be installed. */
    val compatible: Boolean = true,
    /** Installed version name for display; null when not installed. */
    val installedVersion: String? = null,
    /** Parsed listing retained for exact release/publisher policy verification at install time. */
    val marketEntry: PluginMarketEntry? = null,
    val release: PluginMarketRelease? = null
)

data class PluginPreview(
    val manifest: PluginManifest?,
    val zipSha256: String,
    /** One of TRUSTED / FIRST_USE / KEY_CHANGED. */
    val trustState: String,
    val trustFingerprint: String,
    val pinnedFingerprint: String,
    val conflict: InstallError?,
    val error: InstallError?,
    val message: String?,
    val alreadyInstalled: Boolean,
    val installedVersionCode: Int
)

/** A preview plus the private cache file that must be installed; deleting it is the caller's job. */
data class PluginImportFile(val preview: PluginPreview, val file: File)

data class MarketSnapshot(
    val sources: List<MarketSourceUi> = emptyList(),
    val entries: List<MarketEntryUi> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

/** Stable, user-readable failure carrying the underlying plugin error code. */
class PluginOperationException(val code: String, message: String) : Exception(message)

/** Chinese explanation for every rejection code; the code itself is kept in the exception. */
fun InstallError.userMessage(): String = when (this) {
    InstallError.OK -> "操作成功"
    InstallError.INVALID_ZIP -> "插件包无效、已损坏或超过体积上限"
    InstallError.INVALID_MANIFEST -> "插件清单无效"
    InstallError.UNSIGNED -> "插件未签名；默认拒绝安装，仅开发者模式可导入本地未签名插件"
    InstallError.BAD_SIGNATURE -> "插件签名校验失败"
    InstallError.UNTRUSTED_PUBLISHER -> "首次使用的发布者需要你明确确认信任"
    InstallError.PUBLISHER_CHANGED -> "该插件的发布者密钥已变化，已阻止安装；如确认更换发布者请先遗忘旧发布者"
    InstallError.ABI_MISMATCH -> "插件 ABI 与当前 ADHook 底座不兼容"
    InstallError.BASE_TOO_OLD -> "插件要求更高版本的 ADHook 底座"
    InstallError.CONFLICT -> "与已安装插件的目标应用冲突"
    InstallError.DOWNGRADE -> "不能降级安装：已安装更高版本"
    InstallError.SAME_VERSION_DIFFERENT_HASH -> "版本号相同但插件内容不同，已阻止覆盖"
    InstallError.STORAGE_FAILURE -> "本地存储写入失败，请检查可用空间后重试"
    InstallError.STORE_UNHEALTHY -> "插件安装记录异常，已暂停写入，请恢复安装记录后重试"
    InstallError.LIMIT_EXCEEDED -> "插件数量超过上限"
    InstallError.MARKET_INDEX_INVALID -> "市场索引无效或包含非法条目"
    InstallError.MARKET_RELEASE_MISSING -> "市场条目缺少发布信息"
    InstallError.MARKET_HASH_MISMATCH -> "下载的插件包与该发布版本摘要不一致"
    InstallError.MARKET_VERSION_MISMATCH -> "插件包版本与市场索引不一致"
    InstallError.MARKET_PUBLISHER_MISMATCH -> "插件包发布者与市场索引不一致，已阻止安装"
    InstallError.MARKET_TARGET_MISMATCH -> "插件包目标应用与市场索引不一致"
    InstallError.MARKET_ID_MISMATCH -> "插件包 ID 与市场索引不一致"
}

val PluginPreview.installable: Boolean get() = blockingReason == null

val PluginPreview.needsTrustConfirmation: Boolean
    get() = installable && manifest?.isSigned == true && trustState == "FIRST_USE"

val PluginPreview.unsigned: Boolean
    get() {
        val current = manifest ?: return false
        return !current.isSigned
    }

val PluginPreview.publisherChanged: Boolean get() = trustState == "KEY_CHANGED"

/** Null when the package could be installed; otherwise the exact user-facing blocker. */
val PluginPreview.blockingReason: String?
    get() = when {
        manifest == null -> message?.takeIf { it.isNotBlank() } ?: "插件包结构无效，无法读取清单"
        conflict != null -> conflict.userMessage()
        error != null -> error.userMessage()
        alreadyInstalled -> "已安装相同版本（versionCode $installedVersionCode），无需重复安装"
        else -> null
    }

/** First [length] hex characters of a publisher fingerprint, for compact list display. */
fun shortPublisherFingerprint(fingerprint: String, length: Int = 16): String {
    if (fingerprint.isBlank()) return ""
    return if (fingerprint.length <= length) fingerprint else fingerprint.take(length) + "…"
}

fun formatPluginTimestamp(millis: Long): String {
    if (millis <= 0L) return ""
    return try {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(millis))
    } catch (_: RuntimeException) {
        ""
    }
}

class PluginRepository(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val appContext = context.applicationContext ?: context
    private val pluginStore by lazy { PluginStore.get(appContext) }
    private val trustStore by lazy { PluginTrustStore.get(appContext) }
    private val sourceStore by lazy { PluginSourceStore.get(appContext) }
    private val marketCache by lazy { PluginMarketCache(appContext) }
    private val marketClient by lazy { PluginMarketClient() }
    private val installer by lazy { PluginInstaller(appContext, com.adhook.BuildConfig.VERSION_CODE) }
    private val writes = Mutex()
    private val importDirectory by lazy {
        File(appContext.cacheDir, "plugin-import").apply { mkdirs() }
    }

    // ----- installed plugins -------------------------------------------------

    suspend fun installed(): List<PluginInstallUi> = withContext(io) {
        val store = pluginStore
        val healthy = store.isHealthy()
        store.list().map { it.toUi(healthy) }
    }

    /** Load warnings for catalogue/trust/source stores; empty when all stores are healthy. */
    /** Detail view data; README is read on IO and bounded before it reaches Compose. */
    suspend fun detail(id: String): PluginDetailUi? = withContext(io) {
        val installed = pluginStore.find(id) ?: return@withContext null
        val healthy = pluginStore.isHealthy()
        val manifest = try {
            PluginManifest.parse(JSONObject(installed.manifestJson))
        } catch (_: RuntimeException) {
            null
        }
        val readme = readReadme(installed, manifest)
        PluginDetailUi(
            plugin = installed.toUi(healthy),
            authorUrl = manifest?.authorUrl.orEmpty(),
            readme = readme.first,
            readmeError = readme.second
        )
    }

    suspend fun storeWarnings(): List<String> = withContext(io) {
        val warnings = ArrayList<String>()
        addWarning(warnings, pluginStore.loadWarning)
        addWarning(warnings, trustStore.loadWarning)
        addWarning(warnings, sourceStore.loadWarning)
        if (!pluginStore.isHealthy()) addWarning(warnings, "插件安装记录异常：写入已暂停，已安装插件不会执行")
        if (!trustStore.isHealthy()) addWarning(warnings, "发布者信任记录异常：信任写入已暂停")
        if (!sourceStore.isHealthy()) addWarning(warnings, "市场来源记录异常：来源写入已暂停")
        warnings.distinct()
    }

    suspend fun uninstall(id: String): Boolean = withContext(io) {
        val removed = writes.withLock { installer.uninstall(id) }
        if (removed) PluginArtifactPublisher.requestPublish()
        removed
    }

    suspend fun forgetPublisher(id: String): Boolean = withContext(io) {
        writes.withLock { installer.forgetPublisher(id) }
    }

    suspend fun publicationState(): PluginArtifactPublisher.State = withContext(io) {
        PluginArtifactPublisher.getState()
    }

    // ----- market sources ----------------------------------------------------

    suspend fun sources(): List<MarketSourceUi> = withContext(io) {
        sourceStore.list().map { it.toUi() }
    }

    suspend fun addSource(url: String, name: String): Result<Long> = withContext(io) {
        val trimmedUrl = url.trim()
        if (!PluginSourceStore.isValidUrl(trimmedUrl)) {
            return@withContext failureOf("INVALID_URL", "仅支持不含用户信息的 http/https 市场地址")
        }
        val sourceName = name.trim().ifBlank { defaultSourceName(trimmedUrl) }
        if (sourceName.isEmpty() || sourceName.length > PluginConstants.MAX_NAME_LENGTH
            || PluginConstants.hasControlCharacters(sourceName)) {
            return@withContext failureOf("INVALID_NAME", "来源名称无效")
        }
        writes.withLock {
            val existing = sourceStore.list().firstOrNull { it.url == trimmedUrl }
            if (existing != null) return@withLock Result.success(existing.id)
            if (!sourceStore.isHealthy()) return@withLock failureOf("STORE_UNHEALTHY", "市场来源记录异常，已暂停写入")
            if (sourceStore.count() >= PluginConstants.MAX_SOURCES) {
                return@withLock failureOf("LIMIT_EXCEEDED", "市场来源已达上限（${PluginConstants.MAX_SOURCES} 个）")
            }
            val id = nextSourceId()
            val source = PluginMarketSource(id, sourceName, trimmedUrl, true, 0L, "")
            if (!sourceStore.upsert(source)) {
                return@withLock failureOf("STORAGE_FAILURE", "来源保存失败，请重试")
            }
            Result.success(id)
        }
    }

    suspend fun removeSource(id: Long): Boolean = withContext(io) {
        writes.withLock {
            val removed = sourceStore.remove(id)
            if (removed) marketCache.clear(id)
            removed
        }
    }

    suspend fun setSourceEnabled(id: Long, enabled: Boolean): Boolean = withContext(io) {
        writes.withLock { sourceStore.setEnabled(id, enabled) }
    }

    /** Explicit refresh of one source: download, validate, then cache and record success. */
    suspend fun refreshSource(id: Long): Result<Unit> = withContext(io) {
        val source = sourceStore.get(id) ?: return@withContext failureOf("SOURCE_NOT_FOUND", "市场来源不存在")
        fetchIndex(source).map { }
    }

    // ----- market ------------------------------------------------------------

    /**
     * Enabled sources only. [forceRefresh] downloads each index (falling back to the last successful
     * cache on failure); otherwise only the cache is read so opening the tab never silently uses
     * the network. Per-source failures are independent.
     */
    suspend fun market(forceRefresh: Boolean): MarketSnapshot = withContext(io) {
        val sources = sourceStore.list()
        val warnings = ArrayList<String>()
        val errors = ArrayList<String>()
        addWarning(warnings, pluginStore.loadWarning)
        addWarning(warnings, sourceStore.loadWarning)
        val enabled = sources.filter { it.isEnabled() }
        if (sources.isNotEmpty() && enabled.isEmpty()) warnings += "所有市场来源均已停用"
        val installedById = pluginStore.list().associateBy { it.id }
        val entries = LinkedHashMap<String, MarketEntryUi>()
        for (source in enabled) {
            var index: PluginMarketIndex? = null
            if (forceRefresh) {
                val fetched = fetchIndex(source)
                if (fetched.isSuccess) {
                    index = fetched.getOrNull()
                } else {
                    errors += fetched.exceptionOrNull()?.message ?: "来源「${source.name}」刷新失败"
                    index = readCachedIndex(source)
                    if (index != null) {
                        warnings += "来源「${source.name}」刷新失败，已展示上次成功索引"
                    } else {
                        errors += "来源「${source.name}」没有可用的缓存索引"
                    }
                }
            } else {
                index = readCachedIndex(source)
                if (index == null) warnings += "来源「${source.name}」尚未获取索引，点刷新获取"
            }
            val parsed = index ?: continue
            for (listing in parsed.entryList()) {
                val existing = entries[listing.id]
                if (existing != null) {
                    warnings += "插件「${listing.name}」在多个来源重复，已采用来源「${existing.sourceName}」的条目"
                    continue
                }
                val mapped = mapMarketEntry(listing, source, installedById)
                if (mapped == null) {
                    warnings += "插件「${listing.name}」的发布地址无效，已跳过"
                    continue
                }
                entries[listing.id] = mapped
            }
        }
        MarketSnapshot(
            sources = sources.map { it.toUi() },
            entries = entries.values.sortedWith(
                compareByDescending<MarketEntryUi> { it.updateAvailable }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            ),
            warnings = warnings.distinct(),
            errors = errors.distinct()
        )
    }

    // ----- local zip import --------------------------------------------------

    /**
     * Copies the picked document into the private import cache (bounded by MAX_ZIP_BYTES) and runs
     * the full preflight. The returned file must be installed or discarded by the caller.
     */
    suspend fun previewLocal(uri: Uri, allowUnsigned: Boolean): Result<PluginImportFile> = withContext(io) {
        val effectiveAllowUnsigned = allowUnsigned && developerModeEnabled()
        writes.withLock {
            val file = newImportFile("local")
            try {
                copyUri(uri, file)
            } catch (cancelled: CancellationException) {
                file.delete()
                throw cancelled
            } catch (error: Exception) {
                file.delete()
                return@withLock failureOf("READ_FAILURE", "无法读取所选文件：${readableMessage(error)}")
            }
            val preflight = installer.preflight(null, file, effectiveAllowUnsigned)
            Result.success(PluginImportFile(toPreview(preflight), file))
        }
    }

    /** Downloads the listed release, validates it against the listing, and keeps it for install. */
    suspend fun previewMarket(entry: MarketEntryUi): Result<PluginImportFile> = withContext(io) {
        val release = entry.release
        val listing = entry.marketEntry
        if (release == null || listing == null) {
            return@withContext failureOf("MARKET_RELEASE_MISSING", InstallError.MARKET_RELEASE_MISSING.userMessage())
        }
        if (!PluginConstants.isValidHttpUrl(entry.zipUrl)) {
            return@withContext failureOf("MARKET_RELEASE_MISSING", "市场插件包地址无效")
        }
        val bytes = try {
            marketClient.downloadZip(entry.zipUrl, release.size, release.sha256)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            return@withContext failureOf("NETWORK", "插件包下载失败：${networkMessage(error)}")
        }
        writes.withLock {
            val file = newImportFile("market-${entry.id}")
            try {
                writeBytes(file, bytes)
            } catch (error: IOException) {
                file.delete()
                return@withLock failureOf("STORAGE_FAILURE", "插件包写入失败")
            }
            val preflight = installer.preflight(null, file, false)
            val preview = toPreview(preflight)
            val manifest = preflight.manifest
            if (manifest == null) {
                // Keep the blocked preview: the dialog explains the exact rejection reason.
                return@withLock Result.success(PluginImportFile(preview, file))
            }
            val policy = PluginMarketInstallPolicy.verify(manifest, listing, preflight.zipSha256)
            if (!policy.isOk()) {
                file.delete()
                return@withLock failureOf(policy.name, "市场校验失败：${policy.userMessage()}")
            }
            Result.success(PluginImportFile(preview, file))
        }
    }

    /** Installs a previously downloaded market package; the caller must have confirmed trust. */
    suspend fun installMarketFile(
        file: File,
        preview: PluginPreview,
        entry: MarketEntryUi,
        trustPublisher: Boolean
    ): Result<Unit> = withContext(io) {
        val result = writes.withLock {
            try {
                val listing = entry.marketEntry
                    ?: return@withLock failureOf("MARKET_RELEASE_MISSING", "市场条目缺少发布信息")
                if (!isManagedImport(file)) {
                    return@withLock failureOf("INVALID_FILE", "下载的插件包已失效，请重新刷新市场")
                }
                val fresh = installer.preflight(null, file, false)
                if (fresh.zipSha256 != preview.zipSha256) {
                    return@withLock failureOf("INVALID_ZIP", "插件包在确认期间已变化，请重新下载")
                }
                if (!fresh.isOk()) {
                    return@withLock installFailure(
                        fresh.blockingError() ?: InstallError.INVALID_ZIP,
                        fresh.message.takeIf { it.isNotBlank() }
                    )
                }
                val manifest = fresh.manifest
                    ?: return@withLock failureOf("INVALID_MANIFEST", "插件清单无法读取")
                val policy = PluginMarketInstallPolicy.verify(manifest, listing, fresh.zipSha256)
                if (!policy.isOk()) {
                    return@withLock failureOf(policy.name, "市场校验失败：${policy.userMessage()}")
                }
                val installed = installer.install(fresh, file, false, trustPublisher,
                    entry.sourceId.toString())
                if (installed.isOk()) Result.success(Unit) else installFailure(installed)
            } finally {
                if (isManagedImport(file)) file.delete()
            }
        }
        if (result.isSuccess) PluginArtifactPublisher.requestPublish()
        result
    }

    /** One-shot market install used by callers that already know the trust decision. */
    suspend fun installFromMarket(entry: MarketEntryUi, trustPublisher: Boolean): Result<Unit> =
        withContext(io) {
            val prepared = previewMarket(entry)
            prepared.fold(
                onSuccess = { installMarketFile(it.file, it.preview, entry, trustPublisher) },
                onFailure = { Result.failure(it) }
            )
        }

    suspend fun installLocal(
        file: File,
        preview: PluginPreview,
        trustPublisher: Boolean,
        allowUnsigned: Boolean
    ): Result<Unit> = withContext(io) {
        val effectiveAllowUnsigned = allowUnsigned && developerModeEnabled()
        val result = writes.withLock {
            try {
                if (!isManagedImport(file)) {
                    return@withLock failureOf("INVALID_FILE", "导入文件已失效，请重新选择")
                }
                val fresh = installer.preflight(null, file, effectiveAllowUnsigned)
                if (fresh.zipSha256 != preview.zipSha256) {
                    return@withLock failureOf("INVALID_ZIP", "插件包在确认期间已变化，请重新导入")
                }
                if (!fresh.isOk()) {
                    return@withLock installFailure(
                        fresh.blockingError() ?: InstallError.INVALID_ZIP,
                        fresh.message.takeIf { it.isNotBlank() }
                    )
                }
                val installed = installer.install(fresh, file, effectiveAllowUnsigned,
                    trustPublisher, "local")
                if (installed.isOk()) Result.success(Unit) else installFailure(installed)
            } finally {
                if (isManagedImport(file)) file.delete()
            }
        }
        if (result.isSuccess) PluginArtifactPublisher.requestPublish()
        result
    }

    suspend fun discardImport(file: File) = withContext(io) {
        writes.withLock {
            if (isManagedImport(file)) file.delete()
        }
    }

    // ----- app list / detail integration -------------------------------------

    /** Installed plugin targeting [pkg] (enabled or not), for the app list/detail surfaces. */
    suspend fun pluginForPackage(pkg: String): PluginInstallUi? = withContext(io) {
        val healthy = pluginStore.isHealthy()
        pluginStore.list().firstOrNull { plugin ->
            plugin.targets.any { it.coveredPackages().contains(pkg) }
        }?.toUi(healthy)
    }

    /** Manifest-declared features for the plugin targeting [pkg]; empty without a plugin. */
    suspend fun featuresForPackage(pkg: String): List<FeatureMeta> = withContext(io) {
        pluginStore.list().firstOrNull { plugin ->
            plugin.targets.any { it.coveredPackages().contains(pkg) }
        }?.features?.map { it.toFeatureMeta() } ?: emptyList()
    }

    // ----- developer mode ----------------------------------------------------

    /** Only controls local unsigned zip import; market installs are never unsigned. */
    suspend fun isDeveloperMode(): Boolean = withContext(io) { developerModeEnabled() }

    suspend fun setDeveloperMode(enabled: Boolean): Boolean = withContext(io) {
        writes.withLock {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_DEVELOPER_MODE, enabled)
                .commit()
        }
    }

    // ----- internals ---------------------------------------------------------

    private fun InstalledPlugin.toUi(healthy: Boolean): PluginInstallUi = PluginInstallUi(
        id = id,
        name = name,
        version = version,
        versionCode = versionCode,
        author = author,
        description = description,
        targets = targets,
        features = features,
        sourceId = sourceId,
        publisherKeyId = publisherKeyId,
        publisherFingerprint = publisherFingerprint,
        installedAt = installedAt,
        abi = abi,
        minBaseVersionCode = minBaseVersionCode,
        filesPresent = installFilesPresent(this),
        storeHealthy = healthy
    )

    private fun PluginMarketSource.toUi(): MarketSourceUi = MarketSourceUi(
        id = id,
        name = name,
        url = url,
        enabled = isEnabled(),
        lastUpdated = lastUpdated,
        lastError = lastError
    )

    private fun readReadme(plugin: InstalledPlugin, manifest: PluginManifest?): Pair<String, String?> {
        val relative = manifest?.readme.orEmpty()
        if (relative.isBlank()) return "" to null
        val versionDirectory = File(
            File(File(appContext.filesDir, "plugins"), plugin.id),
            plugin.versionCode.toString()
        )
        val readme = File(versionDirectory, "assets/$relative")
        return try {
            val root = versionDirectory.canonicalPath + File.separator
            val path = readme.canonicalPath
            when {
                !path.startsWith(root) -> "" to "README 路径无效"
                !readme.isFile -> "" to "README 文件缺失"
                readme.length() > PluginConstants.MAX_README_BYTES -> "" to "README 超过大小上限"
                else -> readme.readBytes().toString(StandardCharsets.UTF_8) to null
            }
        } catch (_: IOException) {
            "" to "README 读取失败"
        }
    }

    private fun installFilesPresent(plugin: InstalledPlugin): Boolean {
        val versionDirectory = File(
            File(File(appContext.filesDir, "plugins"), plugin.id),
            plugin.versionCode.toString()
        )
        val dex = File(versionDirectory, PluginZip.DEX_ENTRY)
        return dex.isFile && dex.length() > 0L
    }

    private fun toPreview(preflight: PluginPreflight): PluginPreview {
        val manifest = preflight.manifest
        val trust = preflight.trust
        val state = trust?.state?.name ?: "FIRST_USE"
        val fingerprint = trust?.fingerprint?.takeIf { it.isNotEmpty() }
            ?: PluginSignature.fingerprint(manifest?.publisherPublicKeyDer() ?: ByteArray(0))
        return PluginPreview(
            manifest = manifest,
            zipSha256 = preflight.zipSha256,
            trustState = state,
            trustFingerprint = fingerprint,
            pinnedFingerprint = trust?.pinnedFingerprint ?: "",
            conflict = preflight.conflict,
            error = preflight.error,
            message = preflight.message.takeIf { it.isNotBlank() },
            alreadyInstalled = preflight.alreadyInstalled,
            installedVersionCode = preflight.installedVersionCode
        )
    }

    /** Downloads and validates one source index, writing its cache/meta only on success. */
    private suspend fun fetchIndex(source: PluginMarketSource): Result<PluginMarketIndex> {
        val json = try {
            marketClient.downloadIndex(source.url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            val message = networkMessage(error)
            writes.withLock { sourceStore.updateMeta(source.id, source.lastUpdated, message) }
            return failureOf("NETWORK", "来源「${source.name}」刷新失败：$message")
        }
        val index = PluginMarketIndex.parse(json)
        if (index == null) {
            val message = InstallError.MARKET_INDEX_INVALID.userMessage()
            writes.withLock { sourceStore.updateMeta(source.id, source.lastUpdated, message) }
            return failureOf("MARKET_INDEX_INVALID", "来源「${source.name}」：$message")
        }
        writes.withLock {
            marketCache.write(source.id, json)
            sourceStore.updateMeta(source.id, System.currentTimeMillis(), "")
        }
        return Result.success(index)
    }

    private fun readCachedIndex(source: PluginMarketSource): PluginMarketIndex? {
        val text = marketCache.read(source.id) ?: return null
        return PluginMarketIndex.parse(text)
    }

    private fun mapMarketEntry(
        listing: PluginMarketEntry,
        source: PluginMarketSource,
        installedById: Map<String, InstalledPlugin>
    ): MarketEntryUi? {
        val release = listing.release ?: return null
        val zipUrl = release.resolvedZipUrl(source.url)
        if (zipUrl.isBlank()) return null
        val existing = installedById[listing.id]
        // ABI/base-version mismatches are never offered as installable or as an update.
        val compatible = release.abi == PluginConstants.PLUGIN_ABI
            && release.minBaseVersionCode <= com.adhook.BuildConfig.VERSION_CODE
        return MarketEntryUi(
            id = listing.id,
            name = listing.name,
            summary = listing.summary,
            author = listing.author,
            targets = listing.targets,
            version = release.version,
            versionCode = release.versionCode,
            zipUrl = zipUrl,
            sha256 = release.sha256,
            size = release.size,
            publisherKeyId = release.publisherKeyId,
            publisherFingerprint = PluginSignature.fingerprint(release.publisherPublicKeyDer()),
            changelog = release.changelog,
            sourceId = source.id,
            sourceName = source.name,
            installedVersionCode = existing?.versionCode,
            updateAvailable = compatible && existing != null && release.versionCode > existing.versionCode,
            compatible = compatible,
            installedVersion = existing?.version,
            marketEntry = listing,
            release = release
        )
    }

    private fun defaultSourceName(url: String): String = try {
        val host = URI(url).host.orEmpty()
        host.ifBlank { "自定义市场" }.take(PluginConstants.MAX_NAME_LENGTH)
    } catch (_: Exception) {
        "自定义市场"
    }

    private fun developerModeEnabled(): Boolean =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEVELOPER_MODE, false)

    private fun nextSourceId(): Long {
        val used = sourceStore.list().map { it.id }.toHashSet()
        val random = SecureRandom()
        repeat(32) {
            val candidate = (System.currentTimeMillis() shl 10) or random.nextInt(1024).toLong()
            if (candidate > 0L && !used.contains(candidate)) return candidate
        }
        return System.currentTimeMillis()
    }

    private fun newImportFile(prefix: String): File {
        importDirectory.mkdirs()
        pruneImportFiles()
        return File(importDirectory, "$prefix-${System.currentTimeMillis()}-${UUID.randomUUID()}.zip")
    }

    /** Best-effort cleanup of abandoned previews; older than a day is never a live confirmation. */
    private fun pruneImportFiles() {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        importDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun isManagedImport(file: File): Boolean = try {
        val root = importDirectory.canonicalFile
        val target = file.canonicalFile
        target.isFile && target.parentFile == root
    } catch (_: IOException) {
        false
    }

    private fun copyUri(uri: Uri, target: File) {
        importDirectory.mkdirs()
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开所选文件")
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > PluginConstants.MAX_ZIP_BYTES) {
                        throw IOException("文件超过 ${PluginConstants.MAX_ZIP_BYTES / (1024 * 1024)} MB 上限")
                    }
                    output.write(buffer, 0, read)
                }
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun writeBytes(target: File, bytes: ByteArray) {
        FileOutputStream(target).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun networkMessage(error: IOException): String {
        val detail = error.message
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.trim()
            .orEmpty()
        return if (detail.isEmpty()) "网络请求失败，请检查网络连接" else detail.take(160)
    }

    private fun readableMessage(error: Exception): String {
        val detail = error.message?.replace('\n', ' ')?.trim().orEmpty()
        return if (detail.isEmpty()) "读取失败" else detail.take(160)
    }

    private fun addWarning(target: MutableList<String>, warning: String?) {
        if (!warning.isNullOrBlank()) target.add(warning)
    }

    private fun failureOf(code: String, message: String): Result<Nothing> =
        Result.failure(PluginOperationException(code, message))

    private fun installFailure(error: InstallError, message: String? = null): Result<Nothing> =
        failureOf(error.name, message?.takeIf { it.isNotBlank() } ?: error.userMessage())

    private companion object {
        const val PREFS = "adhook_plugin_dev"
        const val KEY_DEVELOPER_MODE = "developer_mode"
    }
}
