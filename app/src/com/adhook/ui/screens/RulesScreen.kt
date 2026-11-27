package com.adhook.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhook.config.model.Subscription
import com.adhook.ui.ImportExportHelper
import com.adhook.ui.components.*
import com.adhook.ui.data.*
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Tab 1: 规则页面（远程 JSON 订阅规则管理）
 */
@Composable
fun RulesPage(
    paddingValues: PaddingValues,
    repository: ConsoleRepository,
    subscriptions: List<Subscription>,
    migrationWarnings: List<String>,
    onRefreshNeeded: () -> Unit,
    onExportLocalRules: () -> Unit,
    onImportLocalRules: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val localSub = remember(subscriptions) {
        subscriptions.firstOrNull { it.isLocal }
    }
    if (localSub == null) return
    val remoteSubs = remember(subscriptions) {
        subscriptions.filter { !it.isLocal }
    }


    var showAddRemoteDialog by remember { mutableStateOf(false) }
    var inputRemoteUrl by remember { mutableStateOf("") }
    var isRemoteUpdating by remember { mutableStateOf(false) }
    var subToDelete by remember { mutableStateOf<Subscription?>(null) }

    // 1. 添加 ADHook 远程规则弹窗
    if (showAddRemoteDialog) {
        AlertDialog(
            onDismissRequest = { showAddRemoteDialog = false },
            title = { Text("添加 ADHook 远程规则") },
            text = {
                Column {
                    Text(
                        text = "输入用户分享或社区维护的规则 JSON 直链地址（如 GitHub/Gitee Raw）：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputRemoteUrl,
                        onValueChange = { inputRemoteUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://raw.githubusercontent.com/.../rules.json", fontSize = 12.sp) },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                inputRemoteUrl = ImportExportHelper.getFromClipboard(context)
                            }) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "粘贴",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (inputRemoteUrl.isNotBlank()) {
                            showAddRemoteDialog = false
                            isRemoteUpdating = true
                            coroutineScope.launch {
                                val res = repository.updateSubscription(inputRemoteUrl.trim())
                                isRemoteUpdating = false
                                if (res.isSuccess) {
                                    Toast.makeText(context, "✅ 远程规则拉取并装载成功", Toast.LENGTH_SHORT).show()
                                    onRefreshNeeded()
                                    inputRemoteUrl = ""
                                } else {
                                    Toast.makeText(context, "❌ 拉取失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                ) {
                    Text("下载并导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddRemoteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 2. 删除 ADHook 远程订阅二次确认弹窗
    subToDelete?.let { sub ->
        AlertDialog(
            onDismissRequest = { subToDelete = null },
            title = { Text("删除规则订阅") },
            text = { Text("确定要删除「${sub.name}」吗？相关自定义规则将一并移除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        subToDelete = null
                        coroutineScope.launch {
                            val deleted = repository.deleteSubscription(sub.id)
                            onRefreshNeeded()
                            Toast.makeText(context, if (deleted) "已删除订阅" else "删除订阅失败，请重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { subToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (migrationWarnings.isNotEmpty()) {
            item {
                AdCard {
                    Text("历史规则需要确认", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error)
                    Text(migrationWarnings.take(8).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                    Text("请在对应应用详情页核对旧本地规则：不需要则删除，需要则保留；远程规则使用独立身份。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // ========== 模块 1：本地规则（放最上面） ==========
        item {
            Spacer(modifier = Modifier.height(4.dp))
            AdSectionHeader(
                title = "本地规则",
                trailingContent = {
                    IconButton(
                        onClick = onExportLocalRules,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = "导出本地规则",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onImportLocalRules,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "导入本地规则",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }

        item {
            SubscriptionItemCard(
                sub = localSub,
                onToggleEnabled = { enabled ->
                    coroutineScope.launch {
                        if (!repository.setSubscriptionEnabled(localSub.id, enabled)) {
                            Toast.makeText(context, "规则开关保存失败，请重试", Toast.LENGTH_SHORT).show()
                        }
                        onRefreshNeeded()
                    }
                }
            )
        }

        // ========== 模块 2：ADHook远程规则 ==========
        item {
            Spacer(modifier = Modifier.height(4.dp))
            AdSectionHeader(
                title = "ADHook远程规则",
                trailingContent = {
                    IconButton(
                        onClick = { showAddRemoteDialog = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "添加远程规则",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (!isRemoteUpdating) {
                                isRemoteUpdating = true
                                coroutineScope.launch {
                                    var successCount = 0
                                    var failureCount = 0
                                    try {
                                        for (s in remoteSubs) {
                                            if (s.updateUrl.isNotBlank()) {
                                                if (repository.updateSubscription(s.updateUrl).isSuccess) {
                                                    successCount++
                                                } else {
                                                    failureCount++
                                                }
                                            }
                                        }
                                    } finally {
                                        isRemoteUpdating = false
                                    }
                                    onRefreshNeeded()
                                    val resultText = if (failureCount == 0) {
                                        "✅ 远程规则已全部更新 (成功 $successCount 个)"
                                    } else {
                                        "远程规则更新完成：成功 $successCount 个，失败 $failureCount 个"
                                    }
                                    Toast.makeText(context, resultText, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        if (isRemoteUpdating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "刷新远程规则",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            )
        }

        if (remoteSubs.isEmpty()) {
            item {
                AdCard {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "暂无 ADHook 远程规则订阅",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        } else {
            items(remoteSubs, key = { sub -> "${sub.isLocal}:${sub.id}:${sub.updateUrl}" }) { sub ->
                SwipeToRevealSubscriptionItem(
                    sub = sub,
                    onToggleEnabled = { enabled ->
                        coroutineScope.launch {
                            if (!repository.setSubscriptionEnabled(sub.id, enabled)) {
                                Toast.makeText(context, "订阅开关保存失败，请重试", Toast.LENGTH_SHORT).show()
                            }
                            onRefreshNeeded()
                        }
                    },
                    onDelete = {
                        subToDelete = sub
                    }
                )
            }
        }


        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}


/**
 * 支持向左滑动露底删除的订阅规则卡片项 (Swipe-to-Reveal)
 */
@Composable
fun SwipeToRevealSubscriptionItem(
    sub: Subscription,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    if (sub.isLocal) {
        SubscriptionItemCard(
            sub = sub,
            onToggleEnabled = onToggleEnabled
        )
        return
    }

    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val actionWidthDp = 76.dp
    val actionWidthPx = with(density) { actionWidthDp.toPx() }
    val offsetX = remember { Animatable(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
    ) {
        // 底层红色删除区域（向左滑动露出）
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.error)
                .clickable {
                    coroutineScope.launch { offsetX.snapTo(0f) }
                    onDelete()
                },
            contentAlignment = Alignment.CenterEnd
        ) {
            Row(
                modifier = Modifier
                    .width(actionWidthDp)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "删除",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 上层主卡片，跟随手势水平位移
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(sub.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            coroutineScope.launch {
                                if (offsetX.value < -actionWidthPx / 2) {
                                    offsetX.animateTo(-actionWidthPx)
                                } else {
                                    offsetX.animateTo(0f)
                                }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            coroutineScope.launch {
                                val newOffset = (offsetX.value + dragAmount).coerceIn(-actionWidthPx, 0f)
                                offsetX.snapTo(newOffset)
                            }
                        }
                    )
                },
            shape = RoundedCornerShape(20.dp),
            color = Color.Transparent
        ) {
            SubscriptionItemCard(
                sub = sub,
                onToggleEnabled = onToggleEnabled
            )
        }
    }
}

/**
 * 订阅项卡片（精简去除了底部冗余操作栏，与全局卡片保持像素级对齐内边距）
 */
@Composable
fun SubscriptionItemCard(
    sub: Subscription,
    onToggleEnabled: (Boolean) -> Unit
) {
    AdCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = sub.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    if (sub.isLocal) {
                        AdBadge(
                            text = "本地",
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            textColor = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                val desc = buildString {
                    if (sub.author.isNotBlank() && !sub.isLocal) append("${sub.author} · ")
                    if (sub.isLocal) {
                        append(sub.description)
                    } else {
                        append("涵盖 ${sub.totalAppsCount} 个应用 · 共 ${sub.totalRulesCount} 条规则")
                    }
                }
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (sub.updateUrl.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = sub.updateUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Switch(
                checked = sub.isEnabled,
                onToggleEnabled
            )
        }
    }
}
