package com.adhook.util

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class UpdateDownloadSafetyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = byteArrayOf(0x50, 0x4b, 3, 4) + ByteArray(256) { it.toByte() }
    private val installed = UpdateApkIdentity("com.adhook", "1.0.1", 1, setOf("aa".repeat(32)))
    private val candidate = installed.copy(versionName = "1.0.2", versionCode = 2)
    private fun release() = ReleaseInfo("v1.0.2", "1.0.2", "Update", "", "${UpdateDownloadPolicy.REPO}/releases/download/v1.0.2/ADHook.apk",
        bytes.size.toLong(), "ADHook.apk", "${UpdateDownloadPolicy.REPO}/releases/tag/v1.0.2",
        UpdateApkVerifier.hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
    private class Connection(url: URL, val body: InputStream, val length: Long, val status: Int = 200, val location: String? = null) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() { }
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = body
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
    }
    @Test fun validOfficialDigestProducesOnlyAnOwnedCompletedFileAfterVerification() {
        val cache = temporary.newFolder(); var connection: Connection? = null; val progress = mutableListOf<Float>()
        val downloader = UpdateDownloader { url -> Connection(url, ByteArrayInputStream(bytes), bytes.size.toLong()).also { connection = it } }
        val update = UpdateRequestSession(20_000).use { session ->
            downloader.download(cache, release(), installed, session, { candidate }) { progress.add(it) }
        }
        assertTrue(UpdateDownloadPolicy.owned(cache, update.file)); assertFalse(update.file.name == release().apkName)
        assertArrayEquals(bytes, Files.readAllBytes(update.file.toPath())); assertEquals(1f, progress.last())
        assertTrue(connection!!.disconnected); assertEquals(1, File(cache, "updates").listFiles()!!.size)
    }
    @Test fun missingDigestAndUnsafeNameOrSourceFailBeforeOpeningNetworkOrWritingFiles() {
        val cache = temporary.newFolder(); var opens = 0
        val downloader = UpdateDownloader { opens++; error("must not open") }
        for (bad in listOf(release().copy(sha256 = null), release().copy(apkName = "../outside.apk"),
            release().copy(downloadUrl = "https://github.com.evil/ADHook.apk"))) {
            UpdateRequestSession(20_000).use { session -> assertThrows(IOException::class.java) { downloader.download(cache, bad, installed, session, { candidate }) { } } }
        }
        assertEquals(0, opens); assertFalse(File(cache, "updates").exists())
    }
    @Test fun shortLongOrWrongHashTransfersNeverPromoteAndRemoveTheirTemporaryFile() {
        for (kind in 0..2) {
            val cache = temporary.newFolder()
            val data = if (kind == 0) bytes.copyOf(80) else if (kind == 1) bytes + byteArrayOf(1) else bytes
            val expected = if (kind == 2) release().copy(sha256 = "00".repeat(32)) else release()
            val downloader = UpdateDownloader { url -> Connection(url, ByteArrayInputStream(data), -1) }
            UpdateRequestSession(20_000).use { session -> assertThrows(IOException::class.java) { downloader.download(cache, expected, installed, session, { candidate }) { } } }
            assertEquals(0, File(cache, "updates").listFiles()!!.size)
        }
    }
    @Test fun foreignCertificateMetadataOrVersionDoesNotPromoteEvenWithCorrectDigest() {
        val cache = temporary.newFolder(); val downloader = UpdateDownloader { url -> Connection(url, ByteArrayInputStream(bytes), bytes.size.toLong()) }
        UpdateRequestSession(20_000).use { session -> assertThrows(IOException::class.java) {
            downloader.download(cache, release(), installed, session, { candidate.copy(currentSigners = setOf("bb".repeat(32))) }) { }
        } }
        assertEquals(0, File(cache, "updates").listFiles()!!.size)
    }
    @Test fun redirectsAllowOnlyExplicitGithubAssetHostsAndNeverThirdPartyFallback() {
        val cache = temporary.newFolder(); val urls = mutableListOf<String>()
        val downloader = UpdateDownloader { url ->
            urls.add(url.host)
            if (url.host == "github.com") Connection(url, ByteArrayInputStream(byteArrayOf()), 0, 302,
                "https://release-assets.githubusercontent.com/github-production-release-asset/123/id?token=fixture")
            else Connection(url, ByteArrayInputStream(bytes), bytes.size.toLong())
        }
        UpdateRequestSession(20_000).use { session -> downloader.download(cache, release(), installed, session, { candidate }) { } }
        assertEquals(listOf("github.com", "release-assets.githubusercontent.com"), urls)
        assertThrows(IOException::class.java) { UpdateDownloadPolicy.redirect(URL(release().downloadUrl), "https://ghproxy.net/other") }
        assertThrows(IOException::class.java) { UpdateDownloadPolicy.redirect(URL(release().downloadUrl), "http://release-assets.githubusercontent.com/path") }
    }
    @Test fun slowDripExceedingTotalBudgetOrCancellationCannotFinishOrRetainPart() {
        for (cancel in listOf(false, true)) {
            val cache = temporary.newFolder(); var time = 0L; var active = true
            val stream = object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (cancel) active = false else time = 30_000L
                    return super.read(buffer, offset, minOf(length, 20))
                }
            }
            val downloader = UpdateDownloader { url -> Connection(url, stream, bytes.size.toLong()) }
            UpdateRequestSession(20_000, { time }, { active }).use { session ->
                if (cancel) assertThrows(CancellationException::class.java) { downloader.download(cache, release(), installed, session, { candidate }) { } }
                else assertThrows(IOException::class.java) { downloader.download(cache, release(), installed, session, { candidate }) { } }
            }
            assertEquals(0, File(cache, "updates").listFiles()!!.size)
        }
    }
    @Test fun boundedCacheRejectsAnotherDownloadWithoutDeletingUnknownOrInstallerFiles() {
        val cache = temporary.newFolder(); val directory = UpdateDownloadPolicy.directory(cache)
        repeat(8) { Files.write(File(directory, "preserve-$it.apk").toPath(), bytes) }
        var opens = 0; val downloader = UpdateDownloader { opens++; error("must not open") }
        UpdateRequestSession(20_000).use { session -> assertThrows(IOException::class.java) { downloader.download(cache, release(), installed, session, { candidate }) { } } }
        assertEquals(0, opens); assertEquals(8, directory.listFiles()!!.size)
    }
}
