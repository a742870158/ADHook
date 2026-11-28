package com.adhook.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhook.R
import com.adhook.config.backup.BackupManager
import com.adhook.config.model.GlobalConfig
import com.adhook.ui.components.*
import com.adhook.ui.data.*
import com.adhook.util.DownloadedUpdate
import com.adhook.util.ReleaseInfo
import com.adhook.util.UpdateChecker
import com.adhook.util.UpdateInstallRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 待恢复备份数据承载体
 */
private data class PendingRestore(
    val jsonStr: String,
    val summary: BackupManager.BackupSummary
)

/**
 * Tab 4: 关于页面
 */
@Composable
fun AboutPage(
    paddingValues: PaddingValues,
    repository: ConsoleRepository,
    appVersion: String,
    pluginCount: Int,
    totalAppsCount: Int,
    globalConfig: GlobalConfig,
    onSdkToggle: (String, Boolean) -> Unit,
    saving: Boolean = false,
    onDataRestored: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateDialogInfo by remember { mutableStateOf<ReleaseInfo?>(null) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadedUpdate by remember { mutableStateOf<DownloadedUpdate?>(null) }

    var isBackingUp by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }
    var restoreWarnings by remember { mutableStateOf<List<String>>(emptyList()) }

    val restoreFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val backup = repository.readBackup(uri)
                if (backup != null) pendingRestore = PendingRestore(backup.first, backup.second)
                else Toast.makeText(context, "文件为空、无法读取或不是兼容的 ADHook 全量备份", Toast.LENGTH_LONG).show()
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = "ADHook Logo",
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(18.dp))
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "ADHook",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "通用广告拦截与规则中心",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            AdSectionHeader(title = "交流与开源")
            AdCard {
                AboutActionRow(
                    title = "Telegram 交流群",
                    subtitle = "t.me/ADHook (点击直接跳转)",
                    badgeText = "加入",
                    onClick = {
                        openTelegramGroup(context)
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                AboutActionRow(
                    title = "GitHub 开源仓库",
                    subtitle = "a742870158/ADHook",
                    badgeText = "访问",
                    onClick = {
                        openBrowser(context, "https://github.com/a742870158/ADHook")
                    }
                )
            }
        }

        item {
            AdSectionHeader(title = "架构与特性")
            AdCard {
                AboutInfoRow(
                    title = "插件方案",
                    value = "$pluginCount 个插件"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                AboutInfoRow(
                    title = "本机扫描应用",
                    value = "$totalAppsCount 个应用托管"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                AboutInfoRow(
                    title = "底层框架支持",
                    value = "LSPosed / LibXposed API101"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                AboutInfoRow(
                    title = "开源许可证",
                    value = "Apache-2.0"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                AboutInfoRow(
                    title = "安全保护隔离",
                    value = "LSPosed 作用域决定注入 · 插件功能默认关闭"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                AboutActionRow(
                    title = "检测更新",
                    subtitle = "当前版本: $appVersion",
                    badgeText = if (isCheckingUpdate) "检查中..." else "检查更新",
                    onClick = {
                        if (isCheckingUpdate) return@AboutActionRow
                        isCheckingUpdate = true
                        Toast.makeText(context, "正在查询 GitHub 最新 Release...", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            val result = try { UpdateChecker.checkUpdate(appVersion) }
                                finally { isCheckingUpdate = false }
                            result.onSuccess { release ->
                                if (release != null) {
                                    updateDialogInfo = release
                                    downloadedUpdate = null
                                } else {
                                    Toast.makeText(context, "已是最新版本 (v$appVersion)", Toast.LENGTH_SHORT).show()
                                }
                            }.onFailure { e ->
                                Toast.makeText(context, "检测更新失败: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }
        }

        item {
            AdSectionHeader(title = "应用与后台", trailingText = "Toast · 近期任务")
            AdCard {
                AdSwitchRow(
                    title = "规则触发 Toast 提示",
                    subtitle = "开启后，应用主进程触发 Hook 规则执行成功时弹出系统 Toast 提示（同一应用 2 秒内冷却防刷屏）。",
                    checked = globalConfig.isRuleToastEnabled,
                    enabled = !saving,
                    onCheckedChange = { onSdkToggle("rule_toast", it) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                AdSwitchRow(
                    title = "在近期任务中隐藏",
                    subtitle = "开启后 ADHook 不在系统近期任务列表中显示，可随时关闭恢复显示。",
                    checked = globalConfig.isExcludeFromRecents,
                    enabled = !saving,
                    onCheckedChange = { onSdkToggle("exclude_from_recents", it) }
                )
            }
        }

        item {
            AdSectionHeader(title = "配置备份与恢复")
            AdCard {
                AboutActionRow(
                    title = "备份全部配置",
                    subtitle = "导出订阅、本地规则、全局与单应用设置至 Download/ADHook",
                    badgeText = if (isBackingUp) "备份中..." else "备份",
                    onClick = {
                        if (isBackingUp) return@AboutActionRow
                        isBackingUp = true
                        Toast.makeText(context, "正在导出全量配置...", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            val path = repository.exportBackup()
                            isBackingUp = false
                            if (path != null) {
                                Toast.makeText(context, "✅ 配置已备份至:\n$path", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "❌ 备份失败，请检查存储写入权限", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                AboutActionRow(
                    title = "从备份恢复",
                    subtitle = "选择 adhook_backup_*.json 覆盖当前配置",
                    badgeText = if (isRestoring) "恢复中..." else "恢复",
                    onClick = {
                        if (isRestoring) return@AboutActionRow
                        restoreFilePickerLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    }
                )
            }
        }

        item {
            AdSectionHeader(title = "使用说明")
            AdCard {
                Text(
                    text = "1. 在 LSPosed 管理器中启用 ADHook 模块，勾选需要去广告的目标应用作用域；\n2. 本项目声明包名的应用拥有专属的深度定制去广告实现，但去广告能力不仅限于这几个应用；\n3. 在 LSPosed 勾选第三方应用后可挂载通用拦截；额外宿主需在本模块应用详情页主动授权，才能经配置服务保存拾取规则与上报状态；\n4. 支持从规则中心订阅导入远程规则库，随时保持云端最新去广告能力。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 升级检测弹窗
    if (updateDialogInfo != null) {
        val release = updateDialogInfo!!
        AlertDialog(
            onDismissRequest = {
                if (!isDownloading) {
                    updateDialogInfo = null
                    downloadProgress = null
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "发现新版本 ${release.tagName}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "当前版本: v$appVersion  ➜  最新: ${release.tagName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    if (release.apkSize > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        val sizeMb = String.format(java.util.Locale.CHINA, "%.2f MB", release.apkSize / (1024.0 * 1024.0))
                        Text(
                            text = "安装包大小: $sizeMb",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "更新说明：",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = (if (release.sha256 == null) "官方API未提供SHA-256，受管下载已停用；仅可手动打开官方发布页。\n\n" else "下载将核对官方SHA-256、包名/版本及当前证书元数据；系统安装器仍负责最终验签与用户确认。\n\n") + release.changelog.ifBlank { "发布未提供更新说明" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(16.dp))
                        val p = downloadProgress ?: 0f
                        LinearProgressIndicator(
                            progress = { p },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (p >= 1f) "下载及SHA-256核对完成，正在准备系统安装入口..." else "正在下载并核对新版本... ${(p * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isDownloading && release.sha256 != null,
                    onClick = {
                        isDownloading = true
                        downloadProgress = 0f
                        scope.launch {
                            try {
                                val update = downloadedUpdate?.takeIf { it.releaseTag == release.tagName }
                                    ?: UpdateChecker.downloadApk(context, release) { progress ->
                                        if (isDownloading && updateDialogInfo?.tagName == release.tagName) downloadProgress = progress
                                    }.getOrThrow()
                                downloadedUpdate = update
                                when (UpdateChecker.installApk(context, update).getOrThrow()) {
                                    UpdateInstallRequest.INSTALLER_OPENED -> {
                                        updateDialogInfo = null
                                        downloadedUpdate = null
                                        downloadProgress = null
                                        Toast.makeText(context, "已打开系统安装器，请自行确认；系统仍会验签", Toast.LENGTH_LONG).show()
                                    }
                                    UpdateInstallRequest.PERMISSION_REQUIRED ->
                                        Toast.makeText(context, "请授权后返回再次准备安装，无需重新下载", Toast.LENGTH_LONG).show()
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                downloadedUpdate = null
                                Toast.makeText(context, "更新下载或校验失败: ${failure.message}；可自行打开官方发布页", Toast.LENGTH_LONG).show()
                            } finally { isDownloading = false }
                        }
                    }
                ) {
                    Text(if (isDownloading) "下载/核对中..." else if (release.sha256 == null) "缺少官方SHA-256" else if (downloadedUpdate != null) "准备系统安装" else "下载并核对更新")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !isDownloading,
                        onClick = {
                            openBrowser(context, release.htmlUrl)
                        }
                    ) {
                        Text("官方发布页")
                    }
                    if (!isDownloading) {
                        TextButton(
                            onClick = {
                                updateDialogInfo = null
                                downloadProgress = null
                            }
                        ) {
                            Text("取消")
                        }
                    }
                }
            }
        )
    }

    // 备份恢复确认弹窗
    if (pendingRestore != null) {
        val restoreItem = pendingRestore!!
        val sum = restoreItem.summary
        val timeStr = if (sum.createdAt > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(sum.createdAt))
        } else {
            "未知"
        }
        AlertDialog(
            onDismissRequest = {
                if (!isRestoring) {
                    pendingRestore = null
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "确认恢复配置备份？",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "备份详情：",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• 备份时间: $timeStr\n• 应用版本: v${sum.appVersion}\n• 托管应用: ${sum.appCount} 个\n• 自定义规则: ${sum.customRuleCount} 条\n• 规则订阅: ${sum.subscriptionCount} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(modifier = Modifier.height(10.dp))
                    if (sum.warnings.isNotEmpty()) {
                        Text(
                            text = sum.warnings.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = "⚠️ 恢复操作将完全覆盖当前的全局开关、应用规则与订阅源。\n系统会在覆盖前自动为当前数据创建快照保护。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val currentPending = pendingRestore ?: return@Button
                        isRestoring = true
                        scope.launch {
                            val result = repository.restoreBackup(currentPending.jsonStr)
                            isRestoring = false
                            pendingRestore = null
                            result.onSuccess { r ->
                                restoreWarnings = r.warnings
                                Toast.makeText(
                                    context,
                                    "✅ 配置已恢复：${r.appCount} 个应用、${r.customRuleCount} 条规则、${r.subscriptionCount} 个订阅",
                                    Toast.LENGTH_LONG
                                ).show()
                                onDataRestored()
                            }.onFailure { e ->
                                Toast.makeText(context, "❌ 恢复失败: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isRestoring
                ) {
                    Text(if (isRestoring) "恢复中..." else "确认覆盖恢复")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingRestore = null },
                    enabled = !isRestoring
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (restoreWarnings.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { restoreWarnings = emptyList() },
            title = { Text("配置已恢复，请注意") },
            text = {
                Text(
                    text = restoreWarnings.joinToString("\n"),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { restoreWarnings = emptyList() }) { Text("知道了") }
            }
        )
    }
}

@Composable
fun AboutActionRow(
    title: String,
    subtitle: String? = null,
    badgeText: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (badgeText != null) {
            AdBadge(
                text = badgeText,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                textColor = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun openTelegramGroup(context: Context) {
    val tgUrl = "https://t.me/ADHook"
    var opened = false
    try {
        val tgIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=ADHook")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(tgIntent)
        opened = true
    } catch (_: Throwable) {}

    if (!opened) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(tgUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            opened = true
        } catch (_: Throwable) {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("tg_group", tgUrl))
                Toast.makeText(context, "已复制 Telegram 群链接: $tgUrl", Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {
                Toast.makeText(context, "Telegram: $tgUrl", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun openBrowser(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
        Toast.makeText(context, "无法调用系统浏览器打开: $url", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun AboutInfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
