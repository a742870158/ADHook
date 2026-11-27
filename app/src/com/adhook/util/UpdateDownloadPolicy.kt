package com.adhook.util

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object UpdateDownloadPolicy {
    const val API = "https://api.github.com/repos/a742870158/ADHook/releases/latest"
    const val REPO = "https://github.com/a742870158/ADHook"
    const val MAX_METADATA_BYTES = 1024 * 1024L
    const val MAX_APK_BYTES = 256 * 1024 * 1024L
    const val METADATA_TIMEOUT_MS = 20_000L
    const val APK_TIMEOUT_MS = 120_000L
    private const val ASSET_PREFIX = "/a742870158/ADHook/releases/download/"

    fun version(tag: String): List<Long>? {
        if (tag.length > 64 || !tag.matches(Regex("[vV]?[0-9]+(?:\\.[0-9]+){0,5}"))) return null
        return tag.removePrefix("v").removePrefix("V").split('.').map { it.toLongOrNull() ?: return null }
    }
    fun digest(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (!raw.matches(Regex("sha256:[0-9a-fA-F]{64}"))) throw IOException("发布信息SHA-256格式无效")
        return raw.substringAfter(':').lowercase(Locale.ROOT)
    }
    fun validateRelease(release: ReleaseInfo, requireDigest: Boolean = true) {
        if (version(release.tagName) == null || release.versionName != release.tagName.removePrefix("v").removePrefix("V"))
            throw IOException("发布版本格式无效")
        if (!release.apkName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,150}\\.apk", RegexOption.IGNORE_CASE))
            || release.apkName.contains("..")) throw IOException("发布安装包名称无效")
        if (release.apkSize !in 1..MAX_APK_BYTES) throw IOException("发布安装包大小无效或超过256MiB")
        val url = secureUrl(release.downloadUrl)
        if (url.host.lowercase(Locale.ROOT) != "github.com" || url.toURI().rawPath != "$ASSET_PREFIX${release.tagName}/${release.apkName}"
            || url.query != null) throw IOException("安装包不是指定官方仓库附件")
        if (requireDigest && release.sha256 == null) throw IOException("官方发布API未提供SHA-256，请自行打开官方发布页")
        if (release.sha256 != null && !release.sha256.matches(Regex("[0-9a-f]{64}"))) throw IOException("发布SHA-256无效")
    }
    fun secureUrl(raw: String): URL {
        if (raw.length > 8192) throw IOException("更新URL超过预算")
        val url = try { URL(raw).also { it.toURI() } } catch (bad: Exception) { throw IOException("更新URL无效", bad) }
        if (url.protocol != "https" || url.userInfo != null || url.ref != null || url.port !in listOf(-1, 443))
            throw IOException("更新仅允许无用户信息的HTTPS地址")
        return url
    }
    fun redirect(from: URL, location: String): URL {
        val next = secureUrl(URL(from, location).toString())
        val path = next.toURI().rawPath
        val allowed = when (next.host.lowercase(Locale.ROOT)) {
            "github.com" -> path == from.toURI().rawPath && from.host.equals("github.com", true)
            "release-assets.githubusercontent.com" -> path.startsWith("/github-production-release-asset/")
            "objects.githubusercontent.com" -> path.startsWith("/github-production-release-asset-")
            else -> false
        }
        if (!allowed) throw IOException("更新重定向来源不受信任")
        return next
    }
    fun directory(cache: File): File {
        val root = cache.canonicalFile
        val directory = File(root, "updates")
        if (!directory.exists() && !directory.mkdirs()) throw IOException("无法创建更新缓存目录")
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())
            || directory.canonicalFile != directory.absoluteFile) throw IOException("更新缓存目录无效")
        return directory
    }
    fun owned(cache: File, file: File): Boolean {
        val directory = directory(cache)
        return file.isFile && !Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == directory
            && file.name.matches(Regex("adhook-update-[A-Za-z0-9-]+\\.apk"))
    }
    fun hash(file: File, expectedSize: Long, check: () -> Unit): String {
        if (!file.isFile || file.length() != expectedSize || expectedSize !in 1..MAX_APK_BYTES)
            throw IOException("安装包长度与发布信息不一致")
        val digest = MessageDigest.getInstance("SHA-256")
        var readBytes = 0L
        FileInputStream(file).use { input ->
            val header = ByteArray(4)
            if (input.read(header) != 4 || !header.contentEquals(byteArrayOf(0x50, 0x4b, 3, 4))) throw IOException("下载内容不是APK ZIP")
            digest.update(header); readBytes = 4
            val buffer = ByteArray(8192)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                readBytes += count
                if (readBytes > expectedSize) throw IOException("安装包在校验时长度发生变化")
                digest.update(buffer, 0, count)
            }
        }
        check()
        if (readBytes != expectedSize) throw IOException("安装包不完整")
        return UpdateApkVerifier.hex(digest.digest())
    }
    fun parse(json: String): ReleaseInfo {
        if (json.length > MAX_METADATA_BYTES || json.toByteArray(Charsets.UTF_8).size > MAX_METADATA_BYTES)
            throw IOException("发布信息超过体积预算")
        var depth = 0; var quoted = false; var escaped = false
        for (char in json) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { if (++depth > 64) throw IOException("发布信息嵌套超过预算") }
                '}', ']' -> { if (--depth < 0) throw IOException("发布JSON结构无效") }
            }
        }
        if (quoted || depth != 0) throw IOException("发布JSON结构不完整")
        val root = JSONObject(json)
        val tag = root.optString("tag_name", "").trim()
        val assets = root.optJSONArray("assets") ?: throw IOException("发布信息没有安装包附件")
        if (assets.length() > 256) throw IOException("发布附件数量超过预算")
        val apks = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name", "").endsWith(".apk", true) }
        if (apks.size != 1) throw IOException("发布信息需要恰有一个APK附件，请打开官方发布页选择")
        val asset = apks.single()
        val size = asset.opt("size")
        if (size !is Int && size !is Long) throw IOException("发布附件大小格式无效")
        val release = ReleaseInfo(tag, tag.removePrefix("v").removePrefix("V"),
            root.optString("name", "").ifBlank { "ADHook $tag" }, root.optString("body", ""),
            asset.optString("browser_download_url", ""), (size as Number).toLong(), asset.optString("name", ""),
            "$REPO/releases/tag/$tag", digest(asset.optString("digest", "").takeUnless { it == "null" }))
        validateRelease(release, requireDigest = false)
        return release
    }
}

/** Shared bounded HTTP lifetime. Cancellation/OS IO is cooperative, not a hard-interruptible transaction. */
internal class UpdateRequestSession(
    private val limitMs: Long,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val active: () -> Boolean = { true },
    private val operation: String = "更新操作"
) : AutoCloseable {
    private val started = clock()
    private val cancelled = AtomicBoolean()
    private val expired = AtomicBoolean()
    private val connection = AtomicReference<HttpURLConnection?>()
    private val input = AtomicReference<InputStream?>()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "adhook-http-deadline").apply { isDaemon = true }
    }
    private val timeout = watchdog.schedule({ expired.set(true); disconnect() }, limitMs, TimeUnit.MILLISECONDS)
    fun check() {
        if (cancelled.get() || !active() || Thread.currentThread().isInterrupted) throw CancellationException("${operation}已取消")
        if (expired.get() || clock() - started >= limitMs) throw IOException("${operation}超过总时间预算")
    }
    fun timeout(max: Int): Int { check(); return minOf(max.toLong(), limitMs - (clock() - started)).coerceAtLeast(1).toInt() }
    fun attach(value: HttpURLConnection) { connection.set(value); try { check() } catch (failure: Exception) { disconnect(); throw failure } }
    fun stream(value: InputStream) { input.set(value); check() }
    fun cancel() {
        if (cancelled.compareAndSet(false, true)) try { watchdog.execute { disconnect() } } catch (_: RuntimeException) { }
    }
    fun disconnect() {
        try { connection.getAndSet(null)?.disconnect() } catch (_: Exception) { }
        try { input.getAndSet(null)?.close() } catch (_: Exception) { }
    }
    override fun close() { timeout.cancel(false); disconnect(); watchdog.shutdownNow() }
}

/** The production transfer/verification transaction; tests supply fake HTTP and APK metadata, never real downloads. */
internal class UpdateDownloader(private val connections: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    companion object { private val inFlight = AtomicBoolean() }
    private fun capacity(directory: File, expected: Long) {
        val entries = directory.listFiles() ?: throw IOException("无法读取更新缓存目录")
        if (entries.size >= 8) throw IOException("受管更新缓存达到8文件限额，请自行清理应用缓存")
        var used = 0L
        for (entry in entries) {
            if (Files.isSymbolicLink(entry.toPath()) || !entry.isFile) throw IOException("更新缓存包含未知目录或链接")
            val length = entry.length()
            if (length > UpdateDownloadPolicy.MAX_APK_BYTES - used) throw IOException("更新缓存超过体积预算")
            used += length
        }
        if (expected > UpdateDownloadPolicy.MAX_APK_BYTES - used) throw IOException("更新缓存达到256MiB预算，请自行清理应用缓存")
        // Never remove unknown files or files an external installer might still be reading.
    }
    private fun open(url: URL, session: UpdateRequestSession): HttpURLConnection = connections(url).apply {
        instanceFollowRedirects = false; useCaches = false; requestMethod = "GET"
        connectTimeout = session.timeout(8000); readTimeout = session.timeout(15000)
        setRequestProperty("User-Agent", "ADHook-Android"); setRequestProperty("Accept-Encoding", "identity")
        session.attach(this)
    }
    fun metadata(session: UpdateRequestSession): String {
        val connection = open(URL(UpdateDownloadPolicy.API), session)
        if (connection.responseCode != 200) throw IOException("官方更新API请求失败：HTTP ${connection.responseCode}")
        if (connection.contentLengthLong > UpdateDownloadPolicy.MAX_METADATA_BYTES) throw IOException("发布信息超过体积预算")
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            session.stream(input); val buffer = ByteArray(8192)
            while (true) {
                session.check(); val count = input.read(buffer); if (count < 0) break
                if (output.size().toLong() + count > UpdateDownloadPolicy.MAX_METADATA_BYTES) throw IOException("发布信息超过体积预算")
                output.write(buffer, 0, count)
            }
        }
        session.check()
        if (connection.contentLengthLong >= 0 && output.size().toLong() != connection.contentLengthLong) throw IOException("发布信息不完整")
        return output.toString(Charsets.UTF_8.name())
    }
    fun download(cache: File, release: ReleaseInfo, installed: UpdateApkIdentity, session: UpdateRequestSession,
                 inspect: (File) -> UpdateApkIdentity, progress: (Float) -> Unit): DownloadedUpdate {
        UpdateDownloadPolicy.validateRelease(release)
        if (!inFlight.compareAndSet(false, true)) throw IOException("已有受管更新下载正在进行")
        var temporary: File? = null
        var published: File? = null
        try {
            val directory = UpdateDownloadPolicy.directory(cache)
            capacity(directory, release.apkSize)
            var url = UpdateDownloadPolicy.secureUrl(release.downloadUrl)
            var connection = open(url, session)
            var redirects = 0
            while (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                session.check()
                if (++redirects > 5) throw IOException("更新重定向次数超过预算")
                val location = connection.getHeaderField("Location") ?: throw IOException("更新重定向缺少目标")
                url = UpdateDownloadPolicy.redirect(url, location)
                session.disconnect(); connection = open(url, session)
            }
            session.check()
            if (connection.responseCode != 200) throw IOException("安装包请求失败：HTTP ${connection.responseCode}")
            val length = connection.contentLengthLong
            if (length >= 0 && length != release.apkSize) throw IOException("HTTP长度与发布附件大小不一致")
            val part = File.createTempFile("adhook-update-", ".part", directory)
            temporary = part
            var bytes = 0L; var lastProgress = -1f
            connection.inputStream.use { input ->
                session.stream(input)
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        session.check(); connection.readTimeout = session.timeout(15000)
                        val count = input.read(buffer); if (count < 0) break
                        bytes += count
                        if (bytes > release.apkSize || bytes > UpdateDownloadPolicy.MAX_APK_BYTES) throw IOException("安装包超过期望大小")
                        output.write(buffer, 0, count)
                        val current = (bytes.toFloat() / release.apkSize).coerceAtMost(0.99f)
                        if (current - lastProgress >= 0.01f) { progress(current); lastProgress = current }
                    }
                    output.fd.sync()
                }
            }
            if (bytes != release.apkSize) throw IOException("安装包下载不完整")
            val hash = UpdateDownloadPolicy.hash(part, release.apkSize, session::check)
            if (hash != release.sha256) throw IOException("安装包SHA-256与官方发布信息不一致")
            session.check(); UpdateApkVerifier.verify(inspect(part), installed, release); session.check()
            val completed = File(directory, part.name.removeSuffix(".part") + ".apk")
            if (completed.exists() || part.canonicalFile.parentFile != directory) throw IOException("更新临时路径无效")
            Files.move(part.toPath(), completed.toPath(), StandardCopyOption.ATOMIC_MOVE)
            published = completed; temporary = null
            session.check(); progress(1f)
            return DownloadedUpdate(completed, release, hash).also { published = null }
        } finally {
            try { temporary?.delete(); published?.delete() } finally { inFlight.set(false) }
        }
    }
}
