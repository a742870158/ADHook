package com.adhook.config.subscription

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the production transport with fake HTTP; never opens an actual network connection. */
class SubscriptionDownloaderTest {
    private class Connection(url: URL, val body: InputStream, val length: Long = -1,
        val status: Int = 200, val location: String? = null) : HttpURLConnection(url) {
        var disconnected = false
        var inputReads = 0
        var onDisconnect: (() -> Unit)? = null
        override fun connect() { }
        override fun disconnect() { disconnected = true; onDisconnect?.invoke() }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getInputStream(): InputStream { inputReads++; return body }
        override fun getHeaderField(name: String?) = if (name.equals("Location", true)) location else null
    }

    @Test fun boundedUtf8ResponseKeepsTheChosenSourceAndClosesItsConnection() {
        val text = "{\"name\":\"规则[文本]\"}"
        val bytes = text.toByteArray(Charsets.UTF_8)
        lateinit var connection: Connection
        val downloader = SubscriptionDownloader { url ->
            Connection(url, ByteArrayInputStream(bytes), bytes.size.toLong()).also { connection = it }
        }
        assertEquals(text, downloader.download("https://example.test/rules.json"))
        assertTrue(connection.disconnected)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
    }

    @Test fun declaredOversizeIsRejectedBeforeReadingTheBody() {
        lateinit var connection: Connection
        val downloader = SubscriptionDownloader(maxBytes = 32) { url ->
            Connection(url, ByteArrayInputStream(ByteArray(33)), 33).also { connection = it }
        }
        assertThrows(IOException::class.java) { downloader.download("https://example.test/sub") }
        assertEquals(0, connection.inputReads)
        assertTrue(connection.disconnected)
    }

    @Test fun streamedOversizeTruncationAndInvalidUtf8AreRejectedAndClosed() {
        for ((bytes, expected) in listOf(ByteArray(33) to -1L, byteArrayOf(1, 2) to 3L,
            byteArrayOf(0xc3.toByte(), 0x28) to 2L)) {
            lateinit var connection: Connection
            var closed = false
            val body = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
            val downloader = SubscriptionDownloader(maxBytes = 32) { url ->
                Connection(url, body, expected).also { connection = it }
            }
            assertThrows(IOException::class.java) { downloader.download("https://example.test/sub") }
            assertTrue(connection.disconnected); assertTrue(closed)
        }
    }

    @Test fun unsafeUrlsNeverReachTheConnectionFactory() {
        var opens = 0
        val downloader = SubscriptionDownloader { opens++; error("must not open network") }
        for (url in listOf("file:///etc/config", "https://user:password@example.test/sub",
            "https://example.test/sub#fragment", "https:///missing-host")) {
            assertFalse(SubscriptionDownloader.isSupportedUrl(url))
            assertThrows(Exception::class.java) { downloader.download(url) }
        }
        assertEquals(0, opens)
    }

    @Test fun sameOriginRedirectsWorkButCrossOriginAndDowngradeNeverOpenTheTarget() {
        val connections = mutableListOf<Connection>()
        val downloader = SubscriptionDownloader { url ->
            Connection(url, ByteArrayInputStream("{}".toByteArray()), 2,
                if (url.path == "/first") 302 else 200, "/final").also { connections += it }
        }
        assertEquals("{}", downloader.download("https://example.test/first"))
        assertEquals(listOf("/first", "/final"), connections.map { it.url.path })
        assertTrue(connections.all { it.disconnected })
        for (target in listOf("https://mirror.test/final", "http://example.test/final", "https://example.test:8443/final")) {
            var opens = 0
            val refused = SubscriptionDownloader { url ->
                opens++; Connection(url, ByteArrayInputStream(byteArrayOf()), 0, 302, target)
            }
            assertThrows(IOException::class.java) { refused.download("https://example.test/first") }
            assertEquals(1, opens)
        }
    }

    @Test fun redirectLoopIsBoundedAndGithubFailureDoesNotSelectAMirror() {
        var opens = 0
        val loop = SubscriptionDownloader { url ->
            opens++; Connection(url, ByteArrayInputStream(byteArrayOf()), 0, 302, "/loop")
        }
        assertThrows(IOException::class.java) { loop.download("https://example.test/loop") }
        assertEquals(6, opens)
        val urls = mutableListOf<String>()
        val failure = SubscriptionDownloader { url ->
            urls += url.toString(); Connection(url, ByteArrayInputStream(byteArrayOf()), 0, 503)
        }
        val original = "https://raw.githubusercontent.com/example/rules/main/rules.json"
        assertThrows(IOException::class.java) { failure.download(original) }
        assertEquals(listOf(original), urls)
    }

    @Test fun slowDripAndCancellationAreCheckedAfterEachReadBeforeReturningAnyPayload() {
        for (cancel in listOf(false, true)) {
            var now = 0L; var active = true; lateinit var connection: Connection
            val body = object : ByteArrayInputStream("{\"id\":42}".toByteArray()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (cancel) active = false else now += 1000
                    return super.read(buffer, offset, minOf(length, 1))
                }
            }
            val downloader = SubscriptionDownloader(totalMs = 1500, clock = { now }) { url ->
                Connection(url, body).also { connection = it }
            }
            if (cancel) assertThrows(CancellationException::class.java) { downloader.download("https://example.test/sub") { active } }
            else assertThrows(IOException::class.java) { downloader.download("https://example.test/sub") }
            assertTrue(connection.disconnected)
        }
    }

    @Test fun deadlineWatchdogDisconnectsAnInFlightBlockedRead() {
        val disconnected = CountDownLatch(1)
        val body = object : InputStream() {
            override fun read() = -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue("watchdog must release the actual HTTP handle", disconnected.await(2, TimeUnit.SECONDS))
                return -1
            }
        }
        val downloader = SubscriptionDownloader(totalMs = 250) { url ->
            Connection(url, body).also { it.onDisconnect = { disconnected.countDown() } }
        }
        assertThrows(IOException::class.java) { downloader.download("https://example.test/sub") }
        assertEquals(0L, disconnected.count)
    }
}
