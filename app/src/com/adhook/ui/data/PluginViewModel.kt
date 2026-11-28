package com.adhook.ui.data

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adhook.plugin.PluginArtifactPublisher
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Console-plugin state. Every mutating action is single-flight ([PluginUiState.busy] or
 * [PluginUiState.refreshing]) and only refreshes after the repository confirms success.
 */
data class PluginUiState(
    val installed: List<PluginInstallUi> = emptyList(),
    val storeWarnings: List<String> = emptyList(),
    val sources: List<MarketSourceUi> = emptyList(),
    val market: MarketSnapshot = MarketSnapshot(),
    val publicationState: PluginArtifactPublisher.State? = null,
    val developerMode: Boolean = false,
    val loading: Boolean = true,
    /** Market refresh is running; other source/install actions stay disabled meanwhile. */
    val refreshing: Boolean = false,
    /** A source or install action is running. */
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /** Preview awaiting user confirmation (local zip or market download). */
    val pendingPreview: PluginPreview? = null,
    /** The pending signed publisher has never been trusted; confirm must explicitly trust it. */
    val pendingTrust: Boolean = false,
    /** Human-readable origin of [pendingPreview] (local file or market source). */
    val pendingSourceLabel: String = "",
    /** Currently opened installed-plugin detail; README is loaded lazily. */
    val detail: PluginDetailUi? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null
)

class PluginViewModel(
    private val repository: PluginRepository,
    private val testScope: CoroutineScope? = null
) : ViewModel() {
    private val scope get() = testScope ?: viewModelScope
    private val mutableState = MutableStateFlow(PluginUiState())
    val state: StateFlow<PluginUiState> = mutableState
    private var refreshJob: Job? = null
    private var detailJob: Job? = null
    private var generation = 0L
    private var pendingFile: File? = null
    private var pendingMarket: MarketEntryUi? = null

    init { refreshAll() }

    fun refreshAll() {
        val request = ++generation
        refreshJob?.cancel()
        update { it.copy(loading = true, error = null) }
        refreshJob = scope.launch {
            try {
                val installed = repository.installed()
                val warnings = repository.storeWarnings()
                val sources = repository.sources()
                val market = repository.market(forceRefresh = false)
                val publication = repository.publicationState()
                val developerMode = repository.isDeveloperMode()
                if (request == generation) {
                    update {
                        it.copy(
                            loading = false,
                            installed = installed,
                            storeWarnings = warnings,
                            sources = sources,
                            market = market,
                            publicationState = publication,
                            developerMode = developerMode
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (request == generation) {
                    update { it.copy(loading = false, error = failure.message ?: "插件数据加载失败，请重试") }
                }
            }
        }
    }

    /** Loads the detail page for one installed plugin, including its optional Markdown README. */
    fun openDetail(id: String) {
        detailJob?.cancel()
        update { it.copy(detail = null, detailLoading = true, detailError = null) }
        detailJob = scope.launch {
            try {
                val detail = repository.detail(id)
                if (detail == null) {
                    update { it.copy(detailLoading = false, detailError = "插件安装记录不存在或已卸载") }
                } else {
                    update { it.copy(detail = detail, detailLoading = false, detailError = null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                update { it.copy(detailLoading = false, detailError = failure.message ?: "插件详情加载失败") }
            }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        update { it.copy(detail = null, detailLoading = false, detailError = null) }
    }

    /** [force] downloads every enabled source; without it only the last successful cache is read. */
    fun refreshMarket(force: Boolean) {
        if (mutableState.value.refreshing) return
        update { it.copy(refreshing = true, error = null) }
        scope.launch {
            try {
                val market = repository.market(force)
                val sources = repository.sources()
                update { it.copy(refreshing = false, market = market, sources = sources) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                update { it.copy(refreshing = false, error = failure.message ?: "市场刷新失败，请重试") }
            }
        }
    }

    fun addSource(url: String, name: String) = action {
        repository.addSource(url, name).fold(
            onSuccess = { id ->
                val refreshed = repository.refreshSource(id)
                if (refreshed.isFailure) {
                    update {
                        it.copy(error = refreshed.exceptionOrNull()?.message ?: "来源已添加，但市场索引获取失败")
                    }
                } else {
                    update { it.copy(message = "市场来源已添加并获取索引") }
                }
                reloadSourcesAndMarket()
            },
            onFailure = { failure -> update { it.copy(error = failure.message ?: "来源添加失败") } }
        )
    }

    fun removeSource(id: Long) = action {
        if (repository.removeSource(id)) {
            update { it.copy(message = "市场来源已删除；已安装插件与规则不受影响") }
            reloadSourcesAndMarket()
        } else {
            update { it.copy(error = "来源删除失败：来源不存在或记录异常") }
        }
    }

    fun setSourceEnabled(id: Long, enabled: Boolean) = action {
        if (repository.setSourceEnabled(id, enabled)) {
            if (!enabled) {
                update { it.copy(message = "来源已停用；市场列表将不再展示该来源") }
            }
            reloadSourcesAndMarket()
        } else {
            update { it.copy(error = "来源开关保存失败，请重试") }
        }
    }

    fun refreshSource(id: Long) = action {
        val result = repository.refreshSource(id)
        if (result.isSuccess) {
            update { it.copy(message = "来源索引已刷新") }
        } else {
            update { it.copy(error = result.exceptionOrNull()?.message ?: "来源刷新失败，已保留上次成功索引") }
        }
        reloadSourcesAndMarket()
    }

    fun previewLocal(uri: Uri, allowUnsigned: Boolean) = action {
        repository.previewLocal(uri, allowUnsigned).fold(
            onSuccess = { prepared ->
                pendingFile = prepared.file
                pendingMarket = null
                update {
                    it.copy(
                        pendingPreview = prepared.preview,
                        pendingTrust = prepared.preview.needsTrustConfirmation,
                        pendingSourceLabel = "本地文件"
                    )
                }
            },
            onFailure = { failure -> update { it.copy(error = failure.message ?: "无法读取所选插件包") } }
        )
    }

    fun installFromMarket(entry: MarketEntryUi) = action {
        repository.previewMarket(entry).fold(
            onSuccess = { prepared ->
                pendingFile = prepared.file
                pendingMarket = entry
                update {
                    it.copy(
                        pendingPreview = prepared.preview,
                        pendingTrust = prepared.preview.needsTrustConfirmation,
                        pendingSourceLabel = "市场来源「${entry.sourceName}」"
                    )
                }
            },
            onFailure = { failure -> update { it.copy(error = failure.message ?: "插件包下载或校验失败") } }
        )
    }

    fun confirmInstall(trust: Boolean) {
        if (mutableState.value.busy) return
        val preview = mutableState.value.pendingPreview ?: return
        if (!preview.installable) {
            cancelInstall()
            return
        }
        if (preview.needsTrustConfirmation && !trust) {
            update { it.copy(error = "需要确认发布者信任后才能安装") }
            return
        }
        val file = pendingFile ?: run {
            cancelInstall()
            return
        }
        val market = pendingMarket
        update { it.copy(busy = true, error = null, message = null) }
        scope.launch {
            try {
                val result = if (market != null) {
                    repository.installMarketFile(file, preview, market, trust)
                } else {
                    repository.installLocal(file, preview, trust, mutableState.value.developerMode)
                }
                result.fold(
                    onSuccess = {
                        clearPending()
                        update { it.copy(message = "插件安装成功；已运行的目标应用需强退重开生效") }
                        refreshAll()
                    },
                    onFailure = { failure ->
                        clearPending()
                        update { it.copy(error = failure.message ?: "插件安装失败") }
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                clearPending()
                update { it.copy(error = failure.message ?: "插件安装失败") }
            } finally {
                update { it.copy(busy = false) }
            }
        }
    }

    fun cancelInstall() {
        val file = pendingFile
        pendingFile = null
        pendingMarket = null
        update { it.copy(pendingPreview = null, pendingTrust = false, pendingSourceLabel = "") }
        if (file != null) scope.launch { repository.discardImport(file) }
    }

    fun uninstall(id: String) = action {
        if (repository.uninstall(id)) {
            update { it.copy(message = "插件已卸载；已运行的目标应用需强退重开，已保存的用户规则不受影响") }
            refreshAll()
        } else {
            update { it.copy(error = "卸载失败：安装记录异常或插件文件无法删除") }
        }
    }

    fun forgetPublisher(id: String) = action {
        if (repository.forgetPublisher(id)) {
            update { it.copy(message = "已遗忘该插件的发布者信任；下次安装会重新要求确认") }
            refreshAll()
        } else {
            update { it.copy(error = "遗忘发布者失败：信任记录异常") }
        }
    }

    fun setDeveloperMode(enabled: Boolean) = action {
        if (repository.setDeveloperMode(enabled)) {
            update {
                it.copy(
                    developerMode = enabled,
                    message = if (enabled) {
                        "开发者模式已开启：允许导入本地未签名插件；市场安装始终要求签名"
                    } else {
                        "开发者模式已关闭"
                    }
                )
            }
        } else {
            update { it.copy(error = "开发者模式设置保存失败，请重试") }
        }
    }

    fun dismissError() = update { it.copy(error = null) }

    fun dismissMessage() = update { it.copy(message = null) }

    private suspend fun reloadSourcesAndMarket() {
        val sources = repository.sources()
        val market = repository.market(forceRefresh = false)
        update { it.copy(sources = sources, market = market) }
    }

    private fun clearPending() {
        pendingFile = null
        pendingMarket = null
        update { it.copy(pendingPreview = null, pendingTrust = false, pendingSourceLabel = "") }
    }

    private fun action(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        update { it.copy(busy = true, error = null, message = null) }
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                update { it.copy(error = failure.message ?: "操作失败，请重试") }
            } finally {
                update { it.copy(busy = false) }
            }
        }
    }

    private fun update(transform: (PluginUiState) -> PluginUiState) {
        mutableState.value = transform(mutableState.value)
    }

    override fun onCleared() {
        ++generation
        refreshJob?.cancel()
        detailJob?.cancel()
        super.onCleared()
    }
}
