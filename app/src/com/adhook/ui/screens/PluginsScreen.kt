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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
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
    initialPluginId: String? = null,
    onInitialPluginConsumed: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(TAB_INSTALLED) }
    var selectedPluginId by remember { mutableStateOf<String?>(null) }

    // An external navigation intent opens the requested plugin exactly once; returning to the
    // list must not re-open it on the next recomposition or tab switch.
    LaunchedEffect(initialPluginId) {
        if (initialPluginId != null && selectedPluginId == null) {
            selectedPluginId = initialPluginId
            onInitialPluginConsumed()
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

    val selectedPlugin = selectedPluginId?.let { id -> state.installed.firstOrNull { it.id == id } }
    LaunchedEffect(state.loading, selectedPluginId) {
        if (!state.loading && selectedPluginId != null
            && state.installed.none { it.id == selectedPluginId }) {
            selectedPluginId = null
        }
    }
    BackHandler(enabled = selectedPluginId != null) { selectedPluginId = null }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            if (state.loading || state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (selectedPluginId != null) {
                if (selectedPlugin != null) {
                    PluginDetailScreen(
                        modifier = Modifier.weight(1f),
                        plugin = selectedPlugin,
                        sourceLabel = pluginSourceLabel(selectedPlugin, state.sources),
                        update = state.market.entries.firstOrNull {
                            it.id == selectedPlugin.id && it.updateAvailable
                        },
                        latestEntry = state.market.entries.firstOrNull { it.id == selectedPlugin.id },
                        publicationState = state.publicationState,
                        busy = state.busy || state.refreshing,
                        onBack = { selectedPluginId = null },
                        onSetEnabled = { viewModel.setEnabled(selectedPlugin.id, it) },
                        onUpdate = { viewModel.installFromMarket(it) },
                        onUninstall = { viewModel.uninstall(selectedPlugin.id) },
                        onForgetPublisher = { viewModel.forgetPublisher(selectedPlugin.id) }
                    )
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (state.loading) {
                            CircularProgressIndicator()
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("插件安装记录不存在或已卸载",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { selectedPluginId = null }) { Text("返回插件列表") }
                            }
                        }
                    }
                }
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
                        sources = state.sources,
                        storeWarnings = state.storeWarnings,
                        publicationState = state.publicationState,
                        developerMode = state.developerMode,
                        busy = state.busy || state.refreshing,
                        loading = state.loading,
                        onRefresh = viewModel::refreshAll,
                        onImport = launchImport,
                        onOpenDetail = { selectedPluginId = it },
                        onSetEnabled = viewModel::setEnabled,
                        onUninstall = viewModel::uninstall,
                        onDeveloperMode = viewModel::setDeveloperMode,
                        onOpenMarket = { tab = TAB_MARKET }
                    )
                    else -> PluginMarketTab(
                        modifier = Modifier.weight(1f),
                        sources = state.sources,
                        market = state.market,
                        refreshing = state.refreshing,
                        busy = state.busy,
                        onRefreshMarket = { viewModel.refreshMarket(force = true) },
                        onAddSource = viewModel::addSource,
                        onRemoveSource = viewModel::removeSource,
                        onSetSourceEnabled = viewModel::setSourceEnabled,
                        onRefreshSource = viewModel::refreshSource,
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
    sources: List<MarketSourceUi>,
    storeWarnings: List<String>,
    publicationState: PluginArtifactPublisher.State?,
    developerMode: Boolean,
    busy: Boolean,
    loading: Boolean,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onDeveloperMode: (Boolean) -> Unit,
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
        if (installed.isNotEmpty()) {
            item { PublicationCard(publicationState) }
        }
        if (installed.isEmpty()) {
            item {
                AdCard {
                    Text("底座不含第三方深度 Hook，安装插件后生效",
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "插件声明目标应用与功能开关；安装不会自动授权宿主、创建应用配置或开启 Hook。" +
                            "可在本页导入本地 zip，或前往市场安装。",
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
                    sourceLabel = pluginSourceLabel(plugin, sources),
                    publicationState = publicationState,
                    busy = busy,
                    onClick = { onOpenDetail(plugin.id) },
                    onSetEnabled = { onSetEnabled(plugin.id, it) },
                    onUninstall = { uninstallTarget = plugin }
                )
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            AdSectionHeader(title = "开发者选项")
        }
        item {
            AdCard {
                AdSwitchRow(
                    title = "开发者模式",
                    subtitle = "仅允许导入本地未签名插件；市场安装始终要求签名。",
                    checked = developerMode,
                    onCheckedChange = onDeveloperMode,
                    enabled = !busy
                )
                if (developerMode) {
                    Spacer(Modifier.height(4.dp))
                    Text("已开启：未签名插件无法验证发布者身份，仅用于本地测试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun PublicationCard(publicationState: PluginArtifactPublisher.State?) {
    val state = publicationState
    AdCard {
        Text("插件路由发布", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (state == null) {
            Text("等待发布状态…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (!state.serviceAvailable) {
            Text(
                "未检测到可用的 LSPosed 服务。插件需在 LSPosed 中启用模块并勾选目标应用作用域后才会加载。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else if (state.isConfirmed) {
            Text(
                "已确认发布（generation ${state.publishedGeneration}）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "发布待确认：${state.error ?: "PENDING"}。" +
                    "已运行的目标应用需强退重开才会读取最新插件路由。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun InstalledPluginCard(
    plugin: PluginInstallUi,
    sourceLabel: String,
    publicationState: PluginArtifactPublisher.State?,
    busy: Boolean,
    onClick: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onUninstall: () -> Unit
) {
    val status = installedPluginStatus(plugin, publicationState)
    AdCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AdIconBox(icon = Icons.Default.Extension)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plugin.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold, fontSize = 15.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    AdBadge(text = "v${plugin.version}",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        textColor = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    plugin.targets.joinToString("、") { it.label.ifBlank { it.packageName } },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "发布者 ${shortPublisherFingerprint(plugin.publisherFingerprint).ifBlank { "未签名" }} · $sourceLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(status.first, style = MaterialTheme.typography.labelSmall, color = status.second)
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = plugin.enabled,
                onCheckedChange = onSetEnabled,
                enabled = !busy && plugin.storeHealthy
            )
            IconButton(onClick = onUninstall, enabled = !busy) {
                Icon(Icons.Default.Delete, "卸载插件", tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Status plus its display colour; never implies a running host already uses the new state. */
@Composable
private fun installedPluginStatus(
    plugin: PluginInstallUi,
    publicationState: PluginArtifactPublisher.State?
): Pair<String, androidx.compose.ui.graphics.Color> {
    if (!plugin.storeHealthy) {
        return "安装记录异常 · 写入已暂停" to MaterialTheme.colorScheme.error
    }
    if (!plugin.filesPresent) {
        return "插件文件缺失 · 请重新安装" to MaterialTheme.colorScheme.error
    }
    if (!plugin.enabled) {
        return "已停用 · 启用后需强退目标应用" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val state = publicationState
        ?: return "已启用 · 等待路由发布" to MaterialTheme.colorScheme.onSurfaceVariant
    return if (state.isConfirmed) {
        "已启用 · 路由已发布" to MaterialTheme.colorScheme.primary
    } else {
        "已启用 · 发布待确认，需强退目标应用" to MaterialTheme.colorScheme.error
    }
}

@Composable
private fun PluginInstallConfirmDialog(
    preview: PluginPreview,
    requireTrust: Boolean,
    sourceLabel: String,
    busy: Boolean,
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
                        Text("如确认发布者已更换，请先在插件详情中遗忘该插件的发布者后再重新安装。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        "插件将在目标应用进程内执行代码。安装不会自动授权宿主、创建应用配置或开启 Hook。",
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
