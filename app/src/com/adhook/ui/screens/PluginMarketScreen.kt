package com.adhook.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhook.plugin.PluginSourceStore
import com.adhook.ui.components.*
import com.adhook.ui.data.*

/**
 * 市场子页：来源管理与插件条目列表。
 *
 * <p>纯展示 + 回调，不直接访问 Repository；网络与磁盘由 PluginViewModel/PluginRepository
 * 在 IO 线程完成。来源刷新失败时条目来自上次成功缓存，并明确提示。</p>
 */
@Composable
fun PluginMarketTab(
    modifier: Modifier = Modifier,
    sources: List<MarketSourceUi>,
    market: MarketSnapshot,
    refreshing: Boolean,
    busy: Boolean,
    onRefreshMarket: () -> Unit,
    onAddSource: (String, String) -> Unit,
    onRemoveSource: (Long) -> Unit,
    onSetSourceEnabled: (Long, Boolean) -> Unit,
    onRefreshSource: (Long) -> Unit,
    onInstall: (MarketEntryUi) -> Unit
) {
    var showAddSource by remember { mutableStateOf(false) }
    var sourceToDelete by remember { mutableStateOf<MarketSourceUi?>(null) }

    if (showAddSource) {
        AddSourceDialog(
            busy = busy,
            onDismiss = { showAddSource = false },
            onConfirm = { url, name ->
                showAddSource = false
                onAddSource(url, name)
            }
        )
    }
    sourceToDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { sourceToDelete = null },
            title = { Text("删除市场来源") },
            text = {
                Text("确定删除「${source.name}」吗？已安装插件与规则不受影响，该来源的本地缓存索引会一并清除。")
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        sourceToDelete = null
                        onRemoveSource(source.id)
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { sourceToDelete = null }) { Text("取消") }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            AdSectionHeader(
                title = "市场来源",
                trailingText = if (sources.isEmpty()) null else "${sources.count { it.enabled }}/${sources.size} 启用",
                trailingContent = {
                    IconButton(onClick = onRefreshMarket, enabled = !refreshing && !busy) {
                        Icon(Icons.Default.Refresh, "刷新市场", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showAddSource = true }, enabled = !busy) {
                        Icon(Icons.Default.Add, "添加来源", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
        if (sources.isEmpty()) {
            item {
                AdCard {
                    Text("尚未添加市场来源", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "添加镜像索引地址后即可浏览并安装插件；不联网时也可以直接导入本地 zip。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(sources, key = { "source-${it.id}" }) { source ->
                MarketSourceCard(
                    source = source,
                    busy = busy,
                    refreshing = refreshing,
                    onToggle = { onSetSourceEnabled(source.id, it) },
                    onRefresh = { onRefreshSource(source.id) },
                    onDelete = { sourceToDelete = source }
                )
            }
        }

        if (market.errors.isNotEmpty()) {
            item {
                AdCard {
                    Text("部分来源刷新失败", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    market.errors.forEach { error ->
                        Text("· $error", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("已尽量展示上次成功缓存的索引；插件安装仍会重新校验签名与摘要。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (market.warnings.isNotEmpty()) {
            item {
                AdCard {
                    Text("市场提示", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    market.warnings.forEach { warning ->
                        Text("· $warning", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            AdSectionHeader(
                title = "市场插件",
                trailingText = when {
                    refreshing -> "刷新中…"
                    market.entries.isEmpty() -> null
                    else -> "${market.entries.size} 个"
                }
            )
        }
        when {
            refreshing && market.entries.isEmpty() -> item {
                Row(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在获取市场索引…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            market.entries.isEmpty() -> item {
                AdCard {
                    Text(
                        when {
                            sources.isEmpty() -> "添加市场来源后，这里会显示可安装插件。"
                            market.errors.isNotEmpty() -> "暂无可展示的插件；请检查网络后刷新，或确认来源索引包含插件条目。"
                            else -> "暂无插件；点击右上角刷新获取最新索引。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> items(market.entries, key = { "entry-${it.id}" }) { entry ->
                MarketEntryCard(
                    entry = entry,
                    busy = busy || refreshing,
                    onInstall = { onInstall(entry) }
                )
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun MarketSourceCard(
    source: MarketSourceUi,
    busy: Boolean,
    refreshing: Boolean,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit
) {
    AdCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AdIconBox(icon = Icons.Default.Storefront)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(source.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(source.url, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Switch(checked = source.enabled, onCheckedChange = onToggle, enabled = !busy)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    source.lastUpdated > 0L -> "更新于 ${formatPluginTimestamp(source.lastUpdated)}"
                    source.enabled -> "尚未刷新索引"
                    else -> "已停用"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh, enabled = !refreshing && !busy) {
                Icon(Icons.Default.Refresh, "刷新来源", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Default.Delete, "删除来源", tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp))
            }
        }
        if (source.lastError.isNotBlank()) {
            Text("最近错误：${source.lastError}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MarketEntryCard(
    entry: MarketEntryUi,
    busy: Boolean,
    onInstall: () -> Unit
) {
    val installed = entry.installedVersionCode != null
    val fingerprint = shortPublisherFingerprint(entry.publisherFingerprint)
    AdCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold, fontSize = 15.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    AdBadge(text = "v${entry.version}",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        textColor = MaterialTheme.colorScheme.primary)
                    if (entry.updateAvailable) {
                        Spacer(Modifier.width(4.dp))
                        AdBadge(text = "可更新")
                    }
                    if (!entry.compatible) {
                        Spacer(Modifier.width(4.dp))
                        AdBadge(text = "不兼容",
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            textColor = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(3.dp))
                if (entry.summary.isNotBlank()) {
                    Text(entry.summary, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        if (entry.author.isNotBlank()) append("作者 ${entry.author}")
                        if (entry.sourceName.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append("来源 ${entry.sourceName}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("目标：${entry.targets.joinToString("、")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (fingerprint.isBlank()) "无发布者签名 · 市场安装会被阻止" else "发布者 $fingerprint",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (fingerprint.isBlank()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entry.updateAvailable && entry.changelog.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text("更新说明：${entry.changelog}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                when {
                    !entry.compatible -> {
                        AdBadge(text = "不可安装",
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            textColor = MaterialTheme.colorScheme.error)
                    }
                    installed && !entry.updateAvailable -> {
                        AdBadge(text = "已安装")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "v${entry.installedVersion ?: entry.installedVersionCode}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> Button(
                        onClick = onInstall,
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(if (installed) "更新" else "安装", fontSize = 13.sp)
                    }
                }
            }
        }
        if (!entry.compatible) {
            Spacer(Modifier.height(4.dp))
            Text("该版本需要更高的底座版本或插件 ABI 不匹配，当前不可安装。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error)
        }
        if (installed && entry.updateAvailable) {
            Spacer(Modifier.height(4.dp))
            Text("当前已安装 v${entry.installedVersion ?: entry.installedVersionCode}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddSourceDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val validUrl = PluginSourceStore.isValidUrl(url.trim())
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("添加市场来源") },
        text = {
            Column {
                Text(
                    "输入插件市场索引 JSON 的 http/https 直链。来源添加后会立即尝试获取一次索引。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://example.com/adhook/market.json", fontSize = 12.sp) },
                    singleLine = true,
                    isError = url.isNotBlank() && !validUrl
                )
                if (url.isNotBlank() && !validUrl) {
                    Text("地址无效：仅支持无用户信息的 http/https 地址",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(128) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("来源名称（可选）", fontSize = 12.sp) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = validUrl && !busy,
                onClick = { onConfirm(url.trim(), name.trim()) }
            ) { Text("添加并获取索引") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        }
    )
}
