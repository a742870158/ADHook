package com.adhook.ui

import android.os.Looper
import com.adhook.config.ConfigProvider
import com.adhook.ui.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppDetailStatsIntegrationTest {
    @Test fun lateStatsReportIsObservedAndLeavingTheScreenUnregistersObserver() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repository = ConsoleRepository(context)
        val pkg = "com.observe.host"
        val uri = ConfigProvider.CONTENT_URI.buildUpon().appendPath("runtime-stats").appendPath(pkg).build()
        var changes = 0
        val job = launch(Dispatchers.Unconfined) { repository.observeRuntimeStats(pkg).collect { changes++ } }
        yield()
        context.contentResolver.notifyChange(uri, null)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        yield()
        assertEquals(1, changes)
        job.cancelAndJoin()
        context.contentResolver.notifyChange(uri, null)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        yield()
        assertEquals(1, changes)
    }
}
