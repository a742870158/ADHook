package com.adhook.ui

import com.adhook.ui.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ConsoleViewModelTest {
    @Test fun cancelledLateLoadCannotOverwriteNewestState() = runBlocking {
        val first = CompletableDeferred<ConsoleSnapshot>()
        val second = CompletableDeferred<ConsoleSnapshot>()
        var calls = 0
        val source = object : ConsoleDataSource {
            override suspend fun load(): ConsoleSnapshot = withContext(NonCancellable) {
                if (++calls == 1) first.await() else second.await()
            }
            override suspend fun setSdkEnabled(sdk: String, enabled: Boolean) = true
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = ConsoleViewModel(source, scope)
            vm.refresh(); vm.refresh()
            second.complete(ConsoleSnapshot(appVersion = "new"))
            assertEquals("new", vm.state.value.snapshot.appVersion)
            first.complete(ConsoleSnapshot(appVersion = "old"))
            assertEquals("new", vm.state.value.snapshot.appVersion)
            assertFalse(vm.state.value.loading)
        } finally { scope.cancel() }
    }

    @Test fun failedWritesDoNotPublishOptimisticConfiguration() = runBlocking {
        val source = object : ConsoleDataSource {
            override suspend fun load() = ConsoleSnapshot()
            override suspend fun setSdkEnabled(sdk: String, enabled: Boolean) = false
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = ConsoleViewModel(source, scope)
            vm.refresh()
            vm.setSdkEnabled("rule_toast", true)
            assertFalse(vm.state.value.snapshot.globalConfig.isRuleToastEnabled)
            assertNotNull(vm.state.value.error)
            vm.dismissError(); assertNull(vm.state.value.error)
        } finally { scope.cancel() }
    }

    @Test fun switchWriteDisablesRepeatedSubmissionsAndPublishesOnlyAfterSuccess() = runBlocking {
        val completion = CompletableDeferred<Boolean>()
        var writes = 0
        var enabled = false
        val source = object : ConsoleDataSource {
            override suspend fun load() = ConsoleSnapshot(globalConfig = com.adhook.config.model.GlobalConfig().apply {
                isRuleToastEnabled = enabled
            })
            override suspend fun setSdkEnabled(sdk: String, value: Boolean): Boolean {
                writes++
                return completion.await().also { if (it) enabled = value }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = ConsoleViewModel(source, scope)
            vm.refresh(); vm.setSdkEnabled("rule_toast", true)
            assertTrue(vm.state.value.saving)
            assertFalse(vm.state.value.snapshot.globalConfig.isRuleToastEnabled)
            vm.setSdkEnabled("rule_toast", false); assertEquals(1, writes)
            completion.complete(true)
            assertFalse(vm.state.value.saving)
            assertTrue(vm.state.value.snapshot.globalConfig.isRuleToastEnabled)
        } finally { scope.cancel() }
    }
}
