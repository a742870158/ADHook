package com.adhook.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class UpdateCheckerFlowTest {
    @get:Rule val temporary = TemporaryFolder()
    private class QueuedDispatcher : CoroutineDispatcher() {
        val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun next(): Runnable = queue.poll(5, TimeUnit.SECONDS) ?: error("caller dispatch missing")
    }
    @Test fun onlyVerifiedMetadataAndReceiptOverloadsRemainInTheCompiledApi() {
        // Kotlin Result is an inline class: public JVM names carry a compiler-generated '-' suffix.
        val downloads = UpdateChecker::class.java.declaredMethods.filter { it.name.substringBefore('-') == "downloadApk" }
        val installs = UpdateChecker::class.java.declaredMethods.filter { it.name.substringBefore('-') == "installApk" }
        assertEquals(1, downloads.size); assertEquals(1, installs.size)
        assertTrue(ReleaseInfo::class.java in downloads.single().parameterTypes)
        assertFalse(String::class.java in downloads.single().parameterTypes)
        assertTrue(DownloadedUpdate::class.java in installs.single().parameterTypes)
        assertFalse(File::class.java in installs.single().parameterTypes)
    }
    @Test fun promptCancellationAfterResumeButBeforeCallerDeliveryDiscardsReceipt() = runBlocking {
        val file = temporary.newFile(); Files.write(file.toPath(), byteArrayOf(1))
        val dispatcher = QueuedDispatcher(); val consumed = AtomicBoolean(); val discarded = CompletableDeferred<Unit>()
        val job = launch(dispatcher) {
            UpdateChecker.request(20_000, discard = { value: File -> value.delete(); discarded.complete(Unit) }) { file }.getOrThrow()
            consumed.set(true)
        }
        dispatcher.next().run() // Starts production request; IO performs the operation independently.
        val delivery = dispatcher.next() // Result is resumed, but the caller has not received it.
        job.cancel(); delivery.run()
        withTimeout(5000) { discarded.await() }
        assertFalse(consumed.get()); assertFalse(file.exists()); assertTrue(job.isCancelled)
    }
    @Test fun tamperedOwnedFileFailsBeforeAnyInstallerOrPermissionIntentCanBeOpened() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val bytes = byteArrayOf(0x50, 0x4b, 3, 4, 1, 2, 3, 4)
        val hash = UpdateApkVerifier.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val release = ReleaseInfo("v1.0.2", "1.0.2", "", "", "${UpdateDownloadPolicy.REPO}/releases/download/v1.0.2/ADHook.apk",
            bytes.size.toLong(), "ADHook.apk", "${UpdateDownloadPolicy.REPO}/releases/tag/v1.0.2", hash)
        val file = File(UpdateDownloadPolicy.directory(app.cacheDir), "adhook-update-fixture.apk")
        val receipt = DownloadedUpdate(file, release, hash)
        Files.write(file.toPath(), bytes.copyOf().apply { this[4] = 9 })
        try { assertTrue(UpdateChecker.installApk(app, receipt).isFailure) }
        finally { file.delete() }
    }
}
