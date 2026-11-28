package com.adhook.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhook.plugin.PluginSourceStore
import com.adhook.ui.components.*
import com.adhook.ui.data.*

/**
 * 插件设置子页：开发者模式与市场镜像源。所有磁盘/网络操作仍由 PluginViewModel/Repository
 * 在 IO 线程完成；本页只负责展示和回调。
 */
@Composable
fun PluginSettingsScreen(
    modifier: Modifier = Modifier,
    developerMode: Boolean,
    busy: Boolean,
    refreshing: Boolean,
    sources: List<MarketSourceUi>,
    onBack: () -> Unit,
    onSetDeveloperMode: (Boolean) -> Unit,
    onAddSource: (String, String) -> Unit,
    onRemoveSource: (Long) -> Unit,
    onSetSourceEnabled: (Long, Boolean) -> Unit,
    onRefreshSource: (Long) -> Unit
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
            title = { Text("删除市场镜像源") },
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

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "插件设置",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    "开发者选项与市场镜像源",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { AdSectionHeader(title = "开发者选项") }
            item {
                AdCard {
                    AdSwitchRow(
                        title = "开发者模式",
                        subtitle = "仅允许导入本地未签名插件；市场安装始终要求签名。",
                        checked = developerMode,
                        onCheckedChange = onSetDeveloperMode,
                        enabled = !busy
                    )
                    if (developerMode) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已开启：未签名插件无法验证发布者身份，仅用于本地测试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            item {
                AdSectionHeader(
                    title = "市场镜像源",
                    trailingContent = {
                        if (sources.isNotEmpty()) {
                            Text(
                                "${sources.count { it.enabled }}/${sources.size} 启用",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showAddSource = true }, enabled = !busy) {
                            Icon(Icons.Default.Add, "添加镜像源", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }
            if (sources.isEmpty()) {
                item {
                    AdCard {
                        Text("尚未添加市场镜像源", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "添加 http/https 索引地址后即可在市场页浏览并安装插件；不联网时也可以直接导入本地 zip。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { showAddSource = true }, enabled = !busy) {
                            Text("添加镜像源")
                        }
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
            item { Spacer(Modifier.height(32.dp)) }
        }
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
    AdCard(contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 10.dp)) {
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
        Spacer(Modifier.height(2.dp))
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
            SourceActionButton(
                icon = Icons.Default.Refresh,
                contentDescription = "刷新来源",
                tint = MaterialTheme.colorScheme.primary,
                enabled = !refreshing && !busy,
                onClick = onRefresh
            )
            SourceActionButton(
                icon = Icons.Default.Delete,
                contentDescription = "删除来源",
                tint = MaterialTheme.colorScheme.error,
                enabled = !busy,
                onClick = onDelete
            )
            // Switch 的轨道在本行内容右缘内仍有视觉内缩，补同宽尾部间距才能让删除图标右缘对齐。
            Spacer(Modifier.width(18.dp))
        }
        if (source.lastError.isNotBlank()) {
            Text("最近错误：${source.lastError}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SourceActionButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.CenterEnd
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
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
        title = { Text("添加市场镜像源") },
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
