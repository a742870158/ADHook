package com.adhook.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.EmptyCoroutineContext

/** SHA-256 is supplied by the official GitHub API, never manufactured from untrusted downloaded bytes. */
data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String,
    val apkSize: Long,
    val apkName: String,
    val htmlUrl: String,
    val sha256: String? = null
)

object UpdateChecker {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal suspend fun <T> request(limit: Long, discard: ((T) -> Unit)? = null, operation: (UpdateRequestSession) -> T): Result<T> =
        suspendCancellableCoroutine { continuation ->
            val session = UpdateRequestSession(limit, active = { continuation.isActive })
            continuation.invokeOnCancellation { session.cancel() }
            fun dispose(value: T) {
                val cleanup = discard ?: return
                Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { try { cleanup(value) } catch (_: Exception) { } })
            }
            // The continuation belongs to the caller, not an inner withContext(IO) coroutine.
            // Its onCancellation hook therefore covers prompt cancellation before dispatcher delivery.
            Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable {
                var produced: T? = null
                try {
                    session.check()
                    val value = operation(session)
                    produced = value
                    session.check()
                    produced = null
                    continuation.resume(Result.success(value), onCancellation = { _: Throwable -> dispose(value) })
                } catch (cancelled: CancellationException) {
                    continuation.cancel(cancelled)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resume(Result.failure(failure))
                } finally {
                    try { produced?.let(::dispose) } finally { session.close() }
                }
            })
        }

    suspend fun checkUpdate(currentVersion: String): Result<ReleaseInfo?> = request(UpdateDownloadPolicy.METADATA_TIMEOUT_MS) { session ->
        if (UpdateDownloadPolicy.version(currentVersion) == null) throw IOException("当前版本格式无法比较，请打开官方发布页")
        val release = UpdateDownloadPolicy.parse(UpdateDownloader().metadata(session))
        if (isNewerVersion(release.tagName, currentVersion)) release else null
    }

    fun isNewerVersion(remoteTag: String, localVersion: String): Boolean {
        val remote = UpdateDownloadPolicy.version(remoteTag.trim()) ?: return false
        val local = UpdateDownloadPolicy.version(localVersion.trim()) ?: return false
        for (index in 0 until maxOf(remote.size, local.size)) {
            val r = remote.getOrElse(index) { 0L }; val l = local.getOrElse(index) { 0L }
            if (r != l) return r > l
        }
        return false
    }

    suspend fun downloadApk(context: Context, release: ReleaseInfo, onProgress: (Float) -> Unit): Result<DownloadedUpdate> {
        val app = context.applicationContext
        val owner = currentCoroutineContext()[Job]
        val main = Handler(Looper.getMainLooper())
        return request(UpdateDownloadPolicy.APK_TIMEOUT_MS, discard = { update: DownloadedUpdate -> update.file.delete() }) { session ->
            UpdateDownloadPolicy.validateRelease(release)
            val installed = UpdateApkVerifier.installed(app)
            session.check()
            UpdateDownloader().download(app.cacheDir, release, installed, session,
                { file -> UpdateApkVerifier.archive(app, file) },
                { progress -> main.post { if (owner?.isActive != false) onProgress(progress) } })
        }
    }

    /** Installer opened is not installation completed. The OS still verifies APK signatures and asks the user. */
    suspend fun installApk(context: Context, update: DownloadedUpdate): Result<UpdateInstallRequest> {
        val app = context.applicationContext
        val checked = request(20_000L) { session ->
            UpdateDownloadPolicy.validateRelease(update.release)
            if (!UpdateDownloadPolicy.owned(app.cacheDir, update.file)) throw IOException("安装包不在本应用受管缓存目录")
            val hash = UpdateDownloadPolicy.hash(update.file, update.release.apkSize, session::check)
            if (hash != update.release.sha256 || hash != update.localSha256) throw IOException("安装包在下载后发生变化")
            UpdateApkVerifier.verify(UpdateApkVerifier.archive(app, update.file), UpdateApkVerifier.installed(app), update.release)
            session.check()
        }
        if (checked.isFailure) return Result.failure(checked.exceptionOrNull()!!)
        return withContext(Dispatchers.Main) {
            currentCoroutineContext().ensureActive()
            try {
                if (!app.packageManager.canRequestPackageInstalls()) {
                    app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${app.packageName}"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    Result.success(UpdateInstallRequest.PERMISSION_REQUIRED)
                } else {
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", update.file)
                    currentCoroutineContext().ensureActive()
                    app.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                    Result.success(UpdateInstallRequest.INSTALLER_OPENED)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(IOException("无法打开系统安装入口", failure)) }
        }
    }
}
