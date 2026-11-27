package com.adhook.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.adhook.ui.data.*
import com.adhook.ui.screens.*
import com.adhook.ui.theme.ADHookTheme

/** Exported for picker return navigation only. No Intent can write rules or grant a host. */
class AppDetailActivity : ComponentActivity() {
    private lateinit var viewModel: AppDetailViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val pkg = DetailIntentPolicy.packageName(intent) ?: run { finish(); return }
        val repository = ConsoleRepository(applicationContext)
        viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppDetailViewModel(repository) as T
        })[AppDetailViewModel::class.java]
        consumeNavigation(intent)
        viewModel.open(pkg)
        setContent {
            ADHookTheme {
                val state by viewModel.state.collectAsState()
                LaunchedEffect(state.error) {
                    state.error?.let { Toast.makeText(this@AppDetailActivity, it, Toast.LENGTH_LONG).show(); viewModel.dismissError() }
                }
                val snapshot = state.snapshot
                LaunchedEffect(snapshot?.excludeFromRecents) {
                    if (snapshot != null) {
                        applyExcludeFromRecents(this@AppDetailActivity, snapshot.excludeFromRecents)
                    }
                }
                if (snapshot == null) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.loading) CircularProgressIndicator()
                        else TextButton(onClick = { viewModel.refresh() }) { Text("重新加载") }
                        TextButton(onClick = { finish() }) { Text("返回") }
                    }
                } else {
                    AppDetailScreen(snapshot, state.loading, { finish() },
                        viewModel::inspector, viewModel::hook, viewModel::authorize,
                        viewModel::feature, viewModel::rule, viewModel::remove,
                        viewModel::pluginEnabled,
                        {
                            snapshot.plugin?.let { plugin ->
                                startActivity(
                                    Intent(this@AppDetailActivity, MainActivity::class.java)
                                        .putExtra(PluginNavigation.EXTRA_PLUGIN_ID, plugin.id)
                                )
                            }
                        })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val pkg = DetailIntentPolicy.packageName(intent) ?: return
        setIntent(intent)
        consumeNavigation(intent)
        viewModel.open(pkg)
    }

    private fun consumeNavigation(intent: Intent) {
        if (DetailIntentPolicy.consumePayloadHint(intent)) {
            Toast.makeText(this, "仅展示已保存规则，回跳附带的数据不会写入配置", Toast.LENGTH_SHORT).show()
        }
        // Neither launchedFromPackage nor any attacker-provided app name / existing rule ID is trusted.
    }

    override fun onStart() {
        super.onStart()
        if (::viewModel.isInitialized) viewModel.setVisible(true)
    }

    override fun onStop() {
        if (::viewModel.isInitialized) viewModel.setVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) viewModel.refresh()
    }
}
