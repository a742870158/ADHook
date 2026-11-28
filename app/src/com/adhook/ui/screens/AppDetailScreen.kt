package com.adhook.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adhook.config.model.CustomRule
import com.adhook.ui.components.*
import com.adhook.ui.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    snapshot: AppDetailSnapshot,
    loading: Boolean,
    onBack: () -> Unit,
    onInspector: (Boolean) -> Unit,
    onFeature: (String, Boolean) -> Unit,
    onRule: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    val config = snapshot.config
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column {
                    Text(snapshot.appName, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(snapshot.packageName, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }, containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                AdCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AdIconBox(icon = Icons.Default.FilterCenterFocus,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            iconTint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("悬浮窗元素拾取器", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text(if (config.isInspectorEnabled) "已开启 · 进入目标应用自动悬浮" else "未开启 · 开启后可抓取界面广告",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(config.isInspectorEnabled, onInspector)
                    }
                }
            }
            if (snapshot.features.isNotEmpty()) {
                val nativeHits = snapshot.features.sumOf { snapshot.stats?.hitCounts?.get(it.id) ?: 0 }
                item {
                    AdSectionHeader(
                        title = if (snapshot.plugin != null) "插件特性方案" else "特性方案",
                        trailingText = if (nativeHits > 0) "${snapshot.features.size} 项规则 · 已拦截 $nativeHits" else "${snapshot.features.size} 项规则"
                    )
                }
                item {
                    AdCard {
                        snapshot.features.forEachIndexed { index, feature ->
                            val enabled = com.adhook.core.FeatureGuard.configuredEnabled(
                                config, snapshot.packageName, feature.id, false)
                            val hits = snapshot.stats?.hitCounts?.get(feature.id)
                            val mount = snapshot.stats?.featureStates?.get(feature.id)
                            val badge = featureBadge(enabled, hits, mount, feature.isImplemented,
                                snapshot.stats?.processCount == 0)
                            val neutral = !enabled || !feature.isImplemented || hits == null || hits == 0 || snapshot.stats?.processCount == 0
                            AdSwitchRow(feature.name ?: feature.id, feature.description, enabled && feature.isImplemented,
                                enabled = feature.isImplemented,
                                badgeText = badge.text,
                                badgeContainerColor = when {
                                    badge.error -> MaterialTheme.colorScheme.errorContainer
                                    neutral -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> null
                                },
                                badgeTextColor = when {
                                    badge.error -> MaterialTheme.colorScheme.error
                                    neutral -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> null
                                },
                                onCheckedChange = { onFeature(feature.id, it) })
                            if (index < snapshot.features.lastIndex) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            } else {
                item {
                    AdSectionHeader(title = if (snapshot.plugin != null) "插件未声明功能开关" else "通用广告拦截已就绪")
                }
                item {
                    AdCard {
                        Text(
                            if (snapshot.plugin != null) {
                                "该插件未在清单中声明独立功能项；插件加载后仍按通用 SDK 断源、拾取规则与订阅规则运行。"
                            } else {
                                "LSPosed 作用域内默认执行通用 SDK 断源与折叠；拾取器规则可直接保存。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            snapshot.stats?.hitCounts?.get("sdk-block")?.let { sharedHits ->
                item {
                    AdCard {
                        Text("通用广告 SDK · 已拦截 $sharedHits", style = MaterialTheme.typography.titleSmall)
                        Text("独立通用层的命中记录；作用域内默认执行，不计入某个专有开关。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val hitCounts = snapshot.stats?.hitCounts
            ruleGroup("拾取生成的自定义规则", config.pickerRules, hitCounts, "暂无手动拾取规则；开启拾取器后可在目标应用录入。", onRule, onDelete)
            ruleGroup("ADHook 远程订阅规则", config.remoteRules, hitCounts, "暂无启用的远程订阅规则。", onRule, onDelete)
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private fun LazyListScope.ruleGroup(
    title: String, rules: List<CustomRule>, hitCounts: Map<String, Int>?, emptyText: String,
    onRule: (String, Boolean) -> Unit, onDelete: (String) -> Unit
) {
    val groupHits = rules.sumOf { hitCounts?.get(it.id) ?: 0 }
    val trailing = when {
        rules.isEmpty() -> null
        groupHits > 0 -> "${rules.size} 条规则 · 已拦截 $groupHits"
        else -> "${rules.size} 条规则"
    }
    item { AdSectionHeader(title = title, trailingText = trailing) }
    if (rules.isEmpty()) {
        item { AdCard { Text(emptyText, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) } }
    } else {
        items(rules, key = { "$title:${it.id}" }) { rule ->
            val ruleHits = hitCounts?.get(rule.id) ?: 0
            SwipeToRevealRuleItem(rule, { onRule(rule.id, it) }, { onDelete(rule.id) }, hitCount = ruleHits)
        }
    }
}
