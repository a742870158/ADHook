package com.adhook.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class DetailUiState(val loading: Boolean = true, val snapshot: AppDetailSnapshot? = null, val error: String? = null)

class AppDetailViewModel(private val repository: ConsoleRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = mutableState
    private var packageName: String? = null
    private var loadJob: Job? = null
    private var generation = 0L
    private var visible = false
    private var statsJob: Job? = null

    fun setVisible(value: Boolean) {
        visible = value
        observeStats()
    }

    private fun observeStats() {
        statsJob?.cancel()
        statsJob = null
        val requested = packageName ?: return
        val pkg = mutableState.value.snapshot?.packageName ?: requested
        if (!visible) return
        statsJob = viewModelScope.launch {
            repository.observeRuntimeStats(pkg).collect {
                if (packageName == requested) refresh(showLoading = false)
            }
        }
    }

    fun open(pkg: String) {
        val changed = pkg != packageName
        packageName = pkg
        refresh(changed)
        if (changed) observeStats()
    }

    fun refresh(clear: Boolean = false, showLoading: Boolean = true) {
        val pkg = packageName ?: return
        val request = ++generation
        loadJob?.cancel()
        mutableState.value = DetailUiState(showLoading || clear || mutableState.value.snapshot == null,
            if (clear) null else mutableState.value.snapshot)
        loadJob = viewModelScope.launch {
            try {
                val loaded = repository.loadDetail(pkg)
                if (request == generation) {
                    val previousTarget = mutableState.value.snapshot?.packageName ?: pkg
                    mutableState.value = DetailUiState(false, loaded)
                    if (previousTarget != loaded.packageName) observeStats()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (request == generation) mutableState.value = mutableState.value.copy(loading = false, error = error.message ?: "加载失败")
            }
        }
    }

    fun inspector(enabled: Boolean) = change { repository.setInspectorEnabled(it, enabled) }
    fun feature(id: String, enabled: Boolean) = change { repository.setFeatureEnabled(it, id, enabled) }
    fun rule(id: String, enabled: Boolean) = change { repository.setRuleEnabled(it, id, enabled) }
    fun remove(id: String) = change { repository.removeRule(it, id) }

    fun dismissError() { mutableState.value = mutableState.value.copy(error = null) }

    private fun change(action: suspend (String) -> Boolean) {
        val requested = packageName ?: return
        val pkg = mutableState.value.snapshot?.takeIf { !mutableState.value.loading }?.packageName ?: return
        viewModelScope.launch {
            try {
                val ok = action(pkg)
                if (packageName == requested) {
                    if (ok) refresh()
                    else mutableState.value = mutableState.value.copy(error = "保存失败，请重试")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (packageName == requested) mutableState.value = mutableState.value.copy(error = error.message ?: "保存失败")
            }
        }
    }
}
