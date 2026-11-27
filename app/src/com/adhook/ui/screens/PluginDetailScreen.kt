package com.adhook.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adhook.plugin.PluginArtifactPublisher
import com.adhook.ui.components.*
import com.adhook.ui.data.*

/**
 * 单个已安装插件的详情。功能开关在对应目标应用详情页调整；此处提供启停、更新、卸载与
 * 遗忘发布者。所有危险操作都需要二次确认，且不会删除用户规则。
 */
@Composable
fun PluginDetailScreen(
    modifier: Modifier = Modifier,
    plugin: PluginInstallUi,
    sourceLabel: String,
    update: MarketEntryUi?,
    latestEntry: MarketEntryUi?,
    publicationState: PluginArtifactPublisher.State?,
    busy: Boolean,
    onBack: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onUpdate: (MarketEntryUi) -> Unit,
    onUninstall: () -> Unit,
    onForgetPublisher: () -> Unit
) {
    var showUninstallConfirm by remember(plugin.id) { mutableStateOf(false) }
    var showForgetConfirm by remember(plugin.id) { mutableStateOf(false) }

    if (showUninstallConfirm) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text("卸载插件") },
            text = {
                Text(
                    "确定卸载「${plugin.name}」吗？已保存的用户规则、订阅与授权不会被删除；" +
                        "已运行的目标应用需强退重开才会停止加载该插件。"
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        showUninstallConfirm = false
                        onUninstall()
                    }
                ) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = false }) { Text("取消") }
            }
        )
    }
    if (showForgetConfirm) {
        AlertDialog(
            onDismissRequest = { showForgetConfirm = false },
            title = { Text("遗忘发布者") },
            text = {
                Text(
                    "将删除「${plugin.name}」已固定的发布者信任记录。下次安装同一插件会重新要求确认发布者指纹，" +
                        "当前已安装版本不受影响。"
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        showForgetConfirm = false
                        onForgetPublisher()
                    }
                ) { Text("遗忘", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showForgetConfirm = false }) { Text("取消") }
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
                    plugin.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "v${plugin.version} · ${plugin.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AdCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AdIconBox(icon = Icons.Default.Extension)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("插件状态", style = MaterialTheme.typography.titleSmall)
                            Text(
                                when {
                                    !plugin.storeHealthy -> "安装记录异常，写入已暂停"
                                    !plugin.filesPresent -> "插件文件缺失，请重新安装"
                                    plugin.enabled -> "已启用 · 目标应用需强退重开后才读取新状态"
                                    else -> "已停用 · 启用后需强退目标应用"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = plugin.enabled,
                            onCheckedChange = onSetEnabled,
                            enabled = !busy && plugin.storeHealthy
                        )
                    }
                    if (plugin.author.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("作者：${plugin.author}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (plugin.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(plugin.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("来源：$sourceLabel", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "安装时间：${formatPluginTimestamp(plugin.installedAt).ifBlank { "未知" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "ABI ${plugin.abi} · 最低底座 versionCode ${plugin.minBaseVersionCode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                AdSectionHeader(title = "发布者")
            }
            item {
                AdCard {
                    if (plugin.publisherFingerprint.isBlank()) {
                        Text("未签名插件 · 无法验证发布者身份",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("完整指纹（SHA-256 公钥）", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            plugin.publisherFingerprint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (plugin.publisherKeyId.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Key ID：${plugin.publisherKeyId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showForgetConfirm = true },
                        enabled = !busy && plugin.publisherFingerprint.isNotBlank()
                    ) { Text("遗忘发布者") }
                }
            }

            item { AdSectionHeader(title = "目标应用") }
            item {
                AdCard {
                    plugin.targets.forEachIndexed { index, target ->
                        Text(
                            target.label.ifBlank { target.packageName },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(target.packageName, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (target.primary.isNotBlank() && target.primary != target.packageName) {
                            Text("运行标识：${target.primary}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (target.processes.isNotEmpty()) {
                            Text("进程：${target.processes.joinToString("、")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (index < plugin.targets.lastIndex) {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            }

            item { AdSectionHeader(title = "插件功能", trailingText = "${plugin.features.size} 项") }
            item {
                AdCard {
                    if (plugin.features.isEmpty()) {
                        Text("该插件未在清单中声明独立功能项。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        plugin.features.forEachIndexed { index, feature ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(feature.name, style = MaterialTheme.typography.bodyMedium)
                                    if (feature.description.isNotBlank()) {
                                        Text(feature.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                AdBadge(
                                    text = when {
                                        !feature.isImplemented -> "暂未适配"
                                        feature.isDefaultEnabled -> "默认开启"
                                        else -> "默认关闭"
                                    },
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (index < plugin.features.lastIndex) {
                                HorizontalDivider(Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("功能开关在对应目标应用详情页调整。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item { AdSectionHeader(title = "更新") }
            item {
                AdCard {
                    when {
                        update != null -> {
                            Text("可更新到 v${update.version}",
                                style = MaterialTheme.typography.titleSmall)
                            if (update.changelog.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(update.changelog, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { onUpdate(update) }, enabled = !busy) {
                                Text("更新到 v${update.version}")
                            }
                        }
                        latestEntry != null -> Text(
                            if (!latestEntry.compatible) {
                                "市场索引中的 v${latestEntry.version} 与当前底座不兼容，无法更新。"
                            } else {
                                "已是最新版本（市场索引 v${latestEntry.version}）。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> Text(
                            "暂无市场索引信息。可在市场页刷新后检查更新，或卸载后重新导入本地 zip。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { AdSectionHeader(title = "发布状态") }
            item {
                AdCard {
                    val state = publicationState
                    if (state == null) {
                        Text("等待插件路由发布状态…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val failed = !state.isConfirmed
                        Text(
                            when {
                                !state.serviceAvailable ->
                                    "未检测到可用的 LSPosed 服务：插件需在 LSPosed 中启用模块并勾选目标应用作用域。"
                                state.isConfirmed -> "插件路由已发布（generation ${state.publishedGeneration}）。"
                                else -> "发布待确认：${state.error ?: "PENDING"}；已运行的目标应用需强退重开。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (failed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { AdSectionHeader(title = "危险操作") }
            item {
                AdCard {
                    Text("卸载插件", style = MaterialTheme.typography.titleSmall)
                    Text("保留用户规则与订阅；已运行的目标应用需强退重开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showUninstallConfirm = true },
                        enabled = !busy,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("卸载插件") }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}
