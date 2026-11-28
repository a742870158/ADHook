package com.adhook.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhook.plugin.PluginArtifactPublisher
import com.adhook.ui.components.*
import com.adhook.ui.data.*
import kotlinx.coroutines.delay

private const val TAB_INSTALLED = 0
private const val TAB_MARKET = 1

/**
 * 插件页：已安装 / 市场。持有 PluginViewModel 的状态收集与导入启动器；
 * 具体列表拆成可独立测试的 Composable。
 */
@Composable
fun PluginsPage(
    paddingValues: PaddingValues,
    viewModel: PluginViewModel,
    showSettings: Boolean,
    onDetailVisibilityChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(TAB_INSTALLED) }
    var selectedPluginId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedPluginId) {
        onDetailVisibilityChanged(selectedPluginId != null)
        selectedPluginId?.let { viewModel.openDetail(it) }
    }
    DisposableEffect(Unit) {
        onDispose { onDetailVisibilityChanged(false) }
    }
    LaunchedEffect(state.installed, selectedPluginId) {
        val selected = selectedPluginId ?: return@LaunchedEffect
        if (!state.loading && state.installed.none { it.id == selected }) {
            selectedPluginId = null
            viewModel.closeDetail()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.previewLocal(uri, allowUnsigned = state.developerMode)
        }
    }
    val launchImport = {
        importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.dismissError()
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.dismissMessage()
        }
    }

    // The publisher usually confirms a moment after the first page load (service bind is async).
    // Retry once so users do not see a stale "no LSPosed service" error without manual refresh.
    LaunchedEffect(state.publicationState?.isConfirmed, state.installed.size) {
        val publication = state.publicationState
        if (publication != null && !publication.isConfirmed && state.installed.isNotEmpty()) {
            delay(2000)
            viewModel.refreshAll()
        }
    }

    BackHandler(enabled = showSettings || selectedPluginId != null) {
        when {
            showSettings -> onCloseSettings()
            selectedPluginId != null -> {
                selectedPluginId = null
                viewModel.closeDetail()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            if (state.loading || state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (showSettings) {
                PluginSettingsScreen(
                    modifier = Modifier.weight(1f),
                    developerMode = state.developerMode,
                    busy = state.busy,
                    refreshing = state.refreshing,
                    sources = state.sources,
                    onBack = onCloseSettings,
                    onSetDeveloperMode = viewModel::setDeveloperMode,
                    onAddSource = viewModel::addSource,
                    onRemoveSource = viewModel::removeSource,
                    onSetSourceEnabled = viewModel::setSourceEnabled,
                    onRefreshSource = viewModel::refreshSource
                )
            } else if (selectedPluginId != null) {
                PluginDetailPage(
                    modifier = Modifier.weight(1f),
                    detail = state.detail?.takeIf { it.plugin.id == selectedPluginId },
                    sources = state.sources,
                    loading = state.detailLoading,
                    error = state.detailError,
                    onBack = {
                        selectedPluginId = null
                        viewModel.closeDetail()
                    }
                )
            } else {
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    Tab(selected = tab == TAB_INSTALLED, onClick = { tab = TAB_INSTALLED },
                        text = { Text("已安装") })
                    Tab(selected = tab == TAB_MARKET, onClick = { tab = TAB_MARKET },
                        text = { Text("市场") })
                }
                when (tab) {
                    TAB_INSTALLED -> InstalledPluginsTab(
                        modifier = Modifier.weight(1f),
                        installed = state.installed,
                        storeWarnings = state.storeWarnings,
                        busy = state.busy || state.refreshing,
                        loading = state.loading,
                        onRefresh = viewModel::refreshAll,
                        onImport = launchImport,
                        onOpenDetail = { selectedPluginId = it },
                        onUninstall = viewModel::uninstall,
                        onOpenMarket = { tab = TAB_MARKET }
                    )
                    else -> PluginMarketTab(
                        modifier = Modifier.weight(1f),
                        sources = state.sources,
                        market = state.market,
                        refreshing = state.refreshing,
                        busy = state.busy,
                        onRefreshMarket = { viewModel.refreshMarket(force = true) },
                        onOpenSettings = onOpenSettings,
                        onInstall = viewModel::installFromMarket
                    )
                }
            }
        }

        state.pendingPreview?.let { preview ->
            PluginInstallConfirmDialog(
                preview = preview,
                requireTrust = state.pendingTrust,
                sourceLabel = state.pendingSourceLabel,
                busy = state.busy,
                onForgetPublisher = { pluginId ->
                    viewModel.cancelInstall()
                    viewModel.forgetPublisher(pluginId)
                },
                onConfirm = { viewModel.confirmInstall(trust = state.pendingTrust) },
                onCancel = viewModel::cancelInstall
            )
        }
    }
}

/** Source display for installed plugins: local import, a known market name, or the raw id. */
internal fun pluginSourceLabel(plugin: PluginInstallUi, sources: List<MarketSourceUi>): String = when {
    plugin.sourceId.isBlank() -> "未知来源"
    plugin.sourceId == "local" -> "本地导入"
    else -> sources.firstOrNull { it.id.toString() == plugin.sourceId }?.name
        ?: "市场来源 ${plugin.sourceId}"
}

@Composable
private fun InstalledPluginsTab(
    modifier: Modifier,
    installed: List<PluginInstallUi>,
    storeWarnings: List<String>,
    busy: Boolean,
    loading: Boolean,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onOpenMarket: () -> Unit
) {
    var uninstallTarget by remember { mutableStateOf<PluginInstallUi?>(null) }
    uninstallTarget?.let { plugin ->
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            title = { Text("卸载插件") },
            text = {
                Text(
                    "确定卸载「${plugin.name}」吗？已保存的用户规则与订阅不会被删除；" +
                        "已运行的目标应用需强退重开才会停止加载该插件。"
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        uninstallTarget = null
                        onUninstall(plugin.id)
                    }
                ) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { uninstallTarget = null }) { Text("取消") }
            }
        )
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            AdSectionHeader(
                title = "已安装插件",
                trailingText = if (installed.isEmpty()) null else "${installed.size} 个",
                trailingContent = {
                    IconButton(onClick = onRefresh, enabled = !busy && !loading) {
                        Icon(Icons.Default.Refresh, "刷新插件", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onImport, enabled = !busy) {
                        Icon(Icons.Default.FileDownload, "导入本地插件",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
        if (storeWarnings.isNotEmpty()) {
            item {
                AdCard {
                    Text("插件记录异常", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    storeWarnings.forEach { warning ->
                        Text("· $warning", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (installed.isEmpty()) {
            item {
                AdCard {
                    Text("底座不含第三方深度 Hook，安装插件后生效",
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "插件声明目标应用与功能开关；安装后默认启用，但功能规则默认关闭，" +
                            "需在应用详情逐个开启。可在本页导入本地 zip，或前往市场安装。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onImport, enabled = !busy) { Text("导入本地插件") }
                        OutlinedButton(onClick = onOpenMarket, enabled = !busy) { Text("前往市场") }
                    }
                }
            }
        } else {
            items(installed, key = { "installed-${it.id}" }) { plugin ->
                InstalledPluginCard(
                    plugin = plugin,
                    onClick = { onOpenDetail(plugin.id) },
                    onUninstall = { uninstallTarget = plugin }
                )
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun InstalledPluginCard(
    plugin: PluginInstallUi,
    onClick: () -> Unit,
    onUninstall: () -> Unit
) {
    SwipeToRevealContainer(onDelete = onUninstall) {
        AdCard(onClick = onClick) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plugin.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold, fontSize = 15.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                AdBadge(text = "v${plugin.version}",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    textColor = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun PluginInstallConfirmDialog(
    preview: PluginPreview,
    requireTrust: Boolean,
    sourceLabel: String,
    busy: Boolean,
    onForgetPublisher: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val manifest = preview.manifest
    val blocked = preview.blockingReason
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(if (blocked != null) "无法安装插件" else "确认安装插件") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (blocked != null) {
                    Text(blocked, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                    if (preview.publisherChanged) {
                        Spacer(Modifier.height(8.dp))
                        Text("本次指纹：${preview.trustFingerprint}",
                            style = MaterialTheme.typography.bodySmall)
                        val currentKeyId = manifest?.publisherKeyId
                        if (!currentKeyId.isNullOrBlank()) {
                            Text("本次 Key ID：$currentKeyId",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (preview.pinnedFingerprint.isNotBlank()) {
                            Text("已固定指纹：${preview.pinnedFingerprint}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text("如确认发布者已更换，可先遗忘该插件的发布者，然后重新导入或下载安装。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val pluginId = manifest?.id
                        if (!pluginId.isNullOrBlank()) {
                            TextButton(
                                enabled = !busy,
                                onClick = { onForgetPublisher(pluginId) }
                            ) { Text("遗忘该插件的发布者", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                } else {
                    Text(
                        "${manifest?.name} v${manifest?.version}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    if (!manifest?.authorName.isNullOrBlank()) {
                        Text("作者：${manifest?.authorName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("来源：$sourceLabel", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text("目标应用", style = MaterialTheme.typography.labelMedium)
                    Text(
                        manifest?.targets?.joinToString("、") {
                            it.label.ifBlank { it.packageName }
                        } ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val features = manifest?.features.orEmpty()
                    if (features.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("功能（${features.size} 项）", style = MaterialTheme.typography.labelMedium)
                        Text(features.take(12).joinToString("、") { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (features.size > 12) {
                            Text("…等 ${features.size} 项", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (preview.unsigned) {
                        Text("该插件未签名，无法验证发布者身份。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("发布者指纹（完整）", style = MaterialTheme.typography.labelMedium)
                        Text(preview.trustFingerprint, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "插件将在目标应用进程内执行代码。安装后默认启用，但功能规则默认关闭，需在应用详情逐项开启。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (requireTrust) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "首次使用该发布者：必须明确信任后才能安装。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (preview.unsigned) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "开发者模式安装未签名插件：仅限本地测试，风险自负。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (blocked == null) {
                TextButton(enabled = !busy, onClick = onConfirm) {
                    Text(
                        when {
                            requireTrust -> "信任并安装"
                            preview.unsigned -> "以开发者模式安装"
                            else -> "安装"
                        }
                    )
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onCancel) {
                Text(if (blocked != null) "关闭" else "取消")
            }
        }
    )
}
