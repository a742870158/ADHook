package com.adhook.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhook.ui.components.*
import com.adhook.ui.data.*

/**
 * 市场子页：只负责浏览/刷新/安装。镜像源管理已移到插件设置页。
 */
@Composable
fun PluginMarketTab(
    modifier: Modifier = Modifier,
    sources: List<MarketSourceUi>,
    market: MarketSnapshot,
    refreshing: Boolean,
    busy: Boolean,
    onRefreshMarket: () -> Unit,
    onOpenSettings: () -> Unit,
    onInstall: (MarketEntryUi) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            AdSectionHeader(
                title = "市场插件",
                trailingContent = {
                    Text(
                        when {
                            refreshing -> "刷新中…"
                            market.entries.isEmpty() -> ""
                            else -> "${market.entries.size} 个"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = onRefreshMarket, enabled = !refreshing && !busy) {
                        Icon(Icons.Default.Refresh, "刷新市场", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
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
                            sources.isEmpty() -> "尚未添加市场镜像源。请先在插件设置中添加镜像索引，再刷新市场。"
                            market.errors.isNotEmpty() -> "暂无可展示的插件；请检查网络后刷新，或确认来源索引包含插件条目。"
                            else -> "暂无插件；点击右上角刷新获取最新索引。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (sources.isEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onOpenSettings, enabled = !busy) {
                            Text("打开插件设置")
                        }
                    }
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
                        Spacer(modifier = Modifier.width(4.dp))
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
