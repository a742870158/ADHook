package com.adhook.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Immutable publications only; stale/cancelled loads cannot overwrite a newer result. */
data class ConsoleUiState(
    val loading: Boolean = true,
    val snapshot: ConsoleSnapshot = ConsoleSnapshot(),
    val error: String? = null,
    val saving: Boolean = false,
    val notice: String? = null
)

class ConsoleViewModel(
    private val repository: ConsoleDataSource,
    private val testScope: CoroutineScope? = null
) : ViewModel() {
    private val scope get() = testScope ?: viewModelScope
    private val mutableState = MutableStateFlow(ConsoleUiState())
    val state: StateFlow<ConsoleUiState> = mutableState
    private var refreshJob: Job? = null
    private var generation = 0L

    fun refresh() {
        val request = ++generation
        refreshJob?.cancel()
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        refreshJob = scope.launch {
            try {
                val loaded = repository.load()
                if (request == generation) mutableState.value = ConsoleUiState(false, loaded,
                    saving = mutableState.value.saving, notice = mutableState.value.notice)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (request == generation) mutableState.value = mutableState.value.copy(loading = false,
                    error = failure.message ?: "数据加载失败，请重试")
            }
        }
    }

    fun setSdkEnabled(sdk: String, enabled: Boolean) {
        if (mutableState.value.saving) return
        ++generation
        refreshJob?.cancel()
        mutableState.value = mutableState.value.copy(loading = false, saving = true, error = null)
        scope.launch {
            try {
                if (repository.setSdkEnabled(sdk, enabled)) refresh()
                else mutableState.value = mutableState.value.copy(error = "配置保存失败，请重试")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(error = failure.message ?: "配置保存失败")
            } finally {
                mutableState.value = mutableState.value.copy(saving = false)
            }
        }
    }

    fun dismissError() { mutableState.value = mutableState.value.copy(error = null) }
    fun dismissNotice() { mutableState.value = mutableState.value.copy(notice = null) }

    override fun onCleared() {
        ++generation
        refreshJob?.cancel()
        super.onCleared()
    }
}
