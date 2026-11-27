package com.adhook.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhook.ui.components.*
import com.adhook.ui.data.*

/**
 * Tab 2: 应用页面（合并单一列表，适配优先）
 */
@Composable
fun AppRulesPage(
    paddingValues: PaddingValues,
    appList: List<AppHookItemState>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onClearFocus: () -> Unit,
    onOpenAppDetail: (String, String) -> Unit,
    repository: ConsoleRepository,
    pluginCount: Int = 0
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            AdSectionHeader(
                title = "本机应用",
                trailingText = "插件 $pluginCount · 共 ${appList.size} 个应用"
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 现代精致微圆角填充搜索栏
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "搜索应用名称或包名...",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { onClearFocus() })
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = onClearSearch,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "清空",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // 单一合并应用列表（适配应用优先置顶，其余紧跟在下方）
        items(appList, key = { it.pkg }) { appItem ->
            AppListItemCard(
                item = appItem,
                repository = repository,
                onClick = {
                    onOpenAppDetail(appItem.pkg, appItem.appName)
                }
            )
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
/**
 * 应用项卡片
 */
@Composable
fun AppListItemCard(
    item: AppHookItemState,
    repository: ConsoleRepository,
    onClick: () -> Unit
) {
    var iconBitmap by remember(item.pkg) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.pkg) { iconBitmap = repository.loadIcon(item.pkg) }

    AdCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标容器
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap!!,
                    contentDescription = item.appName,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // 文本信息
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.appName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (item.pluginId != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        AdBadge(
                            text = "插件 v${item.pluginVersion.orEmpty()}",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            textColor = MaterialTheme.colorScheme.primary
                        )
                        if (!item.pluginEnabled) {
                            Spacer(modifier = Modifier.width(6.dp))
                            AdBadge(
                                text = "插件已禁用",
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                textColor = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (item.isHookOn) {
                        Spacer(modifier = Modifier.width(6.dp))
                        AdBadge(text = "Hook")
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                val subtitle = buildString {
                    when {
                        item.pluginId != null -> {
                            append("插件「${item.pluginName ?: item.pluginId}」")
                            if (item.pluginEnabled) {
                                append(" · 已启用 ${item.enabledNative}/${item.totalNative} 项功能")
                            } else {
                                append(" · 插件已禁用，启用后生效")
                            }
                        }
                        else -> append("通用联盟断源托管")
                    }
                    if (item.isHookOn) {
                        append(" · 含 Hook")
                    }
                    if (item.customCount > 0) {
                        append(" · ${item.customCount} 条自定义")
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 拾取开启胶囊（使用精致矮版微标签）
            if (item.isInspectorOn) {
                Spacer(modifier = Modifier.width(8.dp))
                AdBadge(text = "拾取开启")
            }

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
