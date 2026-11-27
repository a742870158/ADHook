package com.adhook.config.subscription

import com.adhook.config.security.ConfigImportPolicy
import com.adhook.util.UpdateRequestSession
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/** Bounded rule-subscription transport; never substitutes a third-party mirror. */
internal class SubscriptionDownloader(
    private val maxBytes: Int = ConfigImportPolicy.MAX_JSON_LENGTH,
    private val totalMs: Long = 30_000L,
    private val connectTimeoutMs: Int = 7000,
    private val readTimeoutMs: Int = 7000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val connections: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    companion object {
        private val inFlight = AtomicInteger()
        fun isSupportedUrl(value: String): Boolean = runCatching { checkedUrl(value); true }.getOrDefault(false)
        private fun checkedUrl(value: String): URL {
            val uri = URI(value)
            if (value.isBlank() || value.length > 2048 || uri.scheme?.lowercase() !in setOf("http", "https") ||
                uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null ||
                (uri.port != -1 && uri.port !in 1..65535)) throw IOException("仅支持无用户信息的http/https订阅地址")
            return uri.toURL()
        }
        private fun port(url: URL) = if (url.port == -1) url.defaultPort else url.port
        private fun redirect(from: URL, location: String): URL {
            val next = checkedUrl(URL(from, location).toString())
            val sameOrigin = next.host.equals(from.host, true) && next.protocol.equals(from.protocol, true) && port(next) == port(from)
            val upgrade = next.host.equals(from.host, true) && from.protocol.equals("http", true) &&
                next.protocol.equals("https", true) && port(from) == 80 && port(next) == 443
            if (!sameOrigin && !upgrade) throw IOException("订阅重定向改变来源；请核对并明确选择目标地址")
            return next
        }
    }

    fun download(value: String, active: () -> Boolean = { true }): String {
        require(maxBytes > 0 && totalMs > 0 && connectTimeoutMs > 0 && readTimeoutMs > 0)
        var url = checkedUrl(value)
        if (inFlight.incrementAndGet() > 4) {
            inFlight.decrementAndGet()
            throw IOException("订阅并发下载超过预算，请稍后重试")
        }
        try {
            UpdateRequestSession(totalMs, clock, active, "订阅下载").use { session ->
                var redirects = 0
                while (true) {
                    session.check()
                    val connection = connections(url)
                    session.attach(connection)
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.requestMethod = "GET"
                    connection.connectTimeout = session.timeout(connectTimeoutMs)
                    connection.readTimeout = session.timeout(readTimeoutMs)
                    connection.setRequestProperty("User-Agent", "ADHook-Android")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    val status = connection.responseCode
                    session.check()
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        if (++redirects > 5) throw IOException("订阅重定向次数超过预算")
                        val location = connection.getHeaderField("Location") ?: throw IOException("订阅重定向缺少目标")
                        url = redirect(url, location)
                        session.disconnect()
                        continue
                    }
                    if (status !in 200..299) throw IOException("订阅请求失败：HTTP $status")
                    val expected = connection.contentLengthLong
                    if (expected > maxBytes) throw IOException("订阅响应超过体积预算")
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        session.stream(input)
                        val buffer = ByteArray(8192)
                        while (true) {
                            session.check()
                            connection.readTimeout = session.timeout(readTimeoutMs)
                            val count = input.read(buffer)
                            session.check()
                            if (count < 0) break
                            if (output.size().toLong() + count > maxBytes) throw IOException("订阅响应超过体积预算")
                            output.write(buffer, 0, count)
                        }
                    }
                    if (expected >= 0 && output.size().toLong() != expected) throw IOException("订阅响应长度不完整或不一致")
                    session.check()
                    val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    val text = decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
                    session.check()
                    return text
                }
            }
        } finally { inFlight.decrementAndGet() }
    }
}
