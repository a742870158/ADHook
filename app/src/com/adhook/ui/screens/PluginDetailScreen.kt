package com.adhook.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhook.ui.components.*
import com.adhook.ui.data.*

/**
 * 已安装插件详情：功能清单、作者/来源/目标与插件自带的 Markdown README。
 * 插件启停已移除，插件安装即默认启用；功能规则仍在应用详情逐个开启。
 */
@Composable
fun PluginDetailPage(
    modifier: Modifier = Modifier,
    detail: PluginDetailUi?,
    sources: List<MarketSourceUi>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit
) {
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
            Column(Modifier.weight(1f)) {
                Text("插件详情",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                detail?.let {
                    Text(it.plugin.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        when {
            loading && detail == null -> Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            error != null -> Column(
                Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(error, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onBack) { Text("返回插件列表") }
            }
            detail == null -> Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) { Text("插件安装记录不存在或已卸载", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> PluginDetailContent(detail, sources)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PluginDetailContent(
    detail: PluginDetailUi,
    sources: List<MarketSourceUi>
) {
    val plugin = detail.plugin
    val uriHandler = LocalUriHandler.current
    val sourceLabel = pluginSourceLabel(plugin, sources)
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AdCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AdIconBox(icon = Icons.Default.Extension)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(plugin.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(6.dp))
                            AdBadge(text = "v${plugin.version}",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                textColor = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            if (detail.authorUrl.isNotBlank()) "作者 ${plugin.author} · 点击访问主页"
                            else "作者 ${plugin.author}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (detail.authorUrl.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = if (detail.authorUrl.isNotBlank()) Modifier.clickable {
                                try { uriHandler.openUri(detail.authorUrl) } catch (_: Throwable) { }
                            } else Modifier
                        )
                        Text("来源 $sourceLabel", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (plugin.description.isNotBlank()) {
                    Text(plugin.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    "目标：${plugin.targets.joinToString("、") { it.label.ifBlank { it.packageName } }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (plugin.publisherFingerprint.isNotBlank()) {
                    Text("发布者 ${plugin.publisherFingerprint}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        item {
            AdSectionHeader(
                title = "功能",
                trailingText = if (plugin.features.isEmpty()) null else "${plugin.features.size} 项"
            )
        }
        if (plugin.features.isEmpty()) {
            item {
                AdCard {
                    Text("该插件未声明独立功能项；插件加载后按通用 Hook、拾取规则与订阅规则运行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(plugin.features.size, key = { "detail-feature-${plugin.features[it].id}" }) { index ->
                val feature = plugin.features[index]
                AdCard {
                    Text(feature.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    if (feature.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(feature.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            AdSectionHeader(title = "插件说明")
        }
        item {
            AdCard {
                when {
                    detail.readmeError != null -> Text(detail.readmeError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    detail.readme.isBlank() -> Text("该插件未提供 Markdown README。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> MarkdownText(detail.readme, Modifier.fillMaxWidth())
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}
