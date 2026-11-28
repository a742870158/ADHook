package com.adhook.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.adhook.R
import com.adhook.ui.data.*
import com.adhook.ui.screens.*
import com.adhook.ui.theme.ADHookTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ConsoleViewModel
    private lateinit var repository: ConsoleRepository
    private lateinit var pluginViewModel: PluginViewModel
    private val currentTab = mutableIntStateOf(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        repository = ConsoleRepository(applicationContext) // Lazy; no IO in constructor.
        viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ConsoleViewModel(repository) as T
        })[ConsoleViewModel::class.java]
        pluginViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PluginViewModel(PluginRepository(applicationContext)) as T
        })[PluginViewModel::class.java]
        setContent {
            ADHookTheme {
                val state by viewModel.state.collectAsState()
                var pluginSettingsOpen by remember { mutableStateOf(false) }
                var pluginDetailOpen by remember { mutableStateOf(false) }
                LaunchedEffect(state.snapshot.globalConfig.isExcludeFromRecents) {
                    applyExcludeFromRecents(this@MainActivity, state.snapshot.globalConfig.isExcludeFromRecents)
                }
                MainScreen(repository, state, viewModel::refresh, viewModel::setSdkEnabled,
                    viewModel::dismissError, viewModel::dismissNotice, currentTab.intValue,
                    { currentTab.intValue = it }, pluginViewModel,
                    pluginSettingsOpen, pluginDetailOpen,
                    { pluginDetailOpen = it }, { pluginSettingsOpen = true },
                    { pluginSettingsOpen = false }) { pkg, _ ->
                    startActivity(Intent(this, AppDetailActivity::class.java).putExtra("package_name", pkg))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        if (::pluginViewModel.isInitialized) pluginViewModel.refreshAll()
    }
}

private const val PLUGIN_TAB = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    repository: ConsoleRepository,
    state: ConsoleUiState,
    onRefresh: () -> Unit,
    onSdkToggle: (String, Boolean) -> Unit,
    onDismissError: () -> Unit,
    onDismissNotice: () -> Unit,
    currentTab: Int,
    onTabSelected: (Int) -> Unit,
    pluginViewModel: PluginViewModel,
    pluginSettingsOpen: Boolean,
    pluginDetailOpen: Boolean,
    onPluginDetailVisibilityChanged: (Boolean) -> Unit,
    onOpenPluginSettings: () -> Unit,
    onClosePluginSettings: () -> Unit,
    onOpenAppDetail: (String, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var search by remember { mutableStateOf("") }
    val snapshot = state.snapshot
    val filtered = remember(search, snapshot.apps) {
        snapshot.apps.filter { search.isBlank() || it.appName.contains(search, true) || it.pkg.contains(search, true) }
    }
    LaunchedEffect(state.error) {
        state.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); onDismissError() }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); onDismissNotice() }
    }
    LaunchedEffect(snapshot.warning) { snapshot.warning?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                Toast.makeText(context, repository.importRules(uri), Toast.LENGTH_LONG).show()
                onRefresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Toast.makeText(context, error.message ?: "导入失败", Toast.LENGTH_LONG).show() }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.app_logo), "ADHook", Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text("ADHook", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp))
                }
            }, actions = {
                if (currentTab == PLUGIN_TAB && !pluginSettingsOpen && !pluginDetailOpen) {
                    IconButton(onClick = onOpenPluginSettings) {
                        Icon(Icons.Default.Settings, "插件设置")
                    }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                listOf("全局", "规则", "应用", "插件", "关于").forEachIndexed { index, label ->
                    val icon = when (index) {
                        0 -> if (currentTab == index) Icons.Default.Security else Icons.Outlined.Security
                        1 -> if (currentTab == index) Icons.Default.Dns else Icons.Outlined.Dns
                        2 -> if (currentTab == index) Icons.Default.Apps else Icons.Outlined.Apps
                        3 -> if (currentTab == index) Icons.Default.Extension else Icons.Outlined.Extension
                        else -> if (currentTab == index) Icons.Default.Info else Icons.Outlined.Info
                    }
                    NavigationBarItem(currentTab == index, {
                        if (index != currentTab) onClosePluginSettings()
                        onTabSelected(index)
                    }, { Icon(icon, label) }, label = { Text(label, fontSize = 12.sp) })
                }
            }
        }, containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            when (currentTab) {
                0 -> GlobalRulesPage(padding)
                1 -> RulesPage(padding, repository, snapshot.subscriptions, snapshot.migrationWarnings, onRefresh,
                    onExportLocalRules = {
                        scope.launch {
                            val path = repository.exportLocalRules()
                            Toast.makeText(context, path?.let { "已导出本地规则至:\n$it" } ?: "导出失败", Toast.LENGTH_LONG).show()
                        }
                    }, onImportLocalRules = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) })
                2 -> AppRulesPage(padding, filtered, search, { search = it },
                    { search = "" }, { focus.clearFocus() }, onOpenAppDetail, repository, snapshot.pluginCount)
                3 -> PluginsPage(padding, pluginViewModel, pluginSettingsOpen,
                    onPluginDetailVisibilityChanged, onOpenPluginSettings, onClosePluginSettings)
                4 -> AboutPage(padding, repository, snapshot.appVersion, snapshot.pluginCount, snapshot.apps.size,
                    snapshot.globalConfig, onSdkToggle, state.saving, onRefresh)
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding()))
        }
    }
}
