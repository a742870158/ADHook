package com.adhook.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Bounded market client. All calls block the caller; the UI must run them on an IO thread.
 *
 * <p>Index responses are capped at 2 MiB and zip payloads at 16 MiB with a 30 s total budget,
 * same-origin redirects (max 5) and at most two concurrent transfers. Failures throw
 * {@link IOException} with a short safe message; payload data and source URLs are never logged
 * or embedded in messages.</p>
 */
public final class PluginMarketClient {

    private static final long TOTAL_TIMEOUT_MS = 30_000L;
    private static final int CONNECT_TIMEOUT_MS = 7_000;
    private static final int READ_TIMEOUT_MS = 7_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_CONCURRENT = 2;
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    /** Injectable connection factory so tests never touch the network. */
    public interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    private final ConnectionFactory connections;
    private final LongSupplier clock;

    public PluginMarketClient() {
        this(url -> (HttpURLConnection) url.openConnection(),
                () -> System.nanoTime() / 1_000_000L);
    }

    public PluginMarketClient(ConnectionFactory connections) {
        this(connections, () -> System.nanoTime() / 1_000_000L);
    }

    public PluginMarketClient(ConnectionFactory connections, LongSupplier clock) {
        if (connections == null || clock == null) throw new IllegalArgumentException("market client dependencies");
        this.connections = connections;
        this.clock = clock;
    }

    public String downloadIndex(String url) throws IOException {
        byte[] bytes = download(url, PluginConstants.MAX_INDEX_BYTES);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("市场索引不是合法 UTF-8");
        }
    }

    public byte[] downloadZip(String url, long expectedSize, String expectedSha256) throws IOException {
        if (expectedSize <= 0L || expectedSize > PluginConstants.MAX_ZIP_BYTES) {
            throw new IOException("插件包大小超出预算");
        }
        if (!PluginConstants.isHexSha256(expectedSha256)) {
            throw new IOException("插件包摘要无效");
        }
        byte[] bytes = download(url, PluginConstants.MAX_ZIP_BYTES);
        if (bytes.length != expectedSize) throw new IOException("插件包大小与索引不一致");
        if (!PluginFiles.sha256Hex(bytes).equalsIgnoreCase(expectedSha256)) {
            throw new IOException("插件包 SHA-256 与索引不一致");
        }
        return bytes;
    }

    private byte[] download(String value, int maxBytes) throws IOException {
        URL url = checkedUrl(value);
        if (IN_FLIGHT.incrementAndGet() > MAX_CONCURRENT) {
            IN_FLIGHT.decrementAndGet();
            throw new IOException("市场下载并发超过预算，请稍后重试");
        }
        long started = clock.getAsLong();
        HttpURLConnection connection = null;
        try {
            int redirects = 0;
            while (true) {
                checkDeadline(started);
                connection = connections.open(url);
                if (connection == null) throw new IOException("无法建立市场连接");
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                int remaining = remainingMs(started);
                connection.setConnectTimeout(Math.max(1, Math.min(CONNECT_TIMEOUT_MS, remaining)));
                connection.setReadTimeout(Math.max(1, Math.min(READ_TIMEOUT_MS, remaining)));
                connection.setRequestProperty("User-Agent", "ADHook-Android");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int status = connection.getResponseCode();
                checkDeadline(started);
                if (isRedirect(status)) {
                    if (++redirects > MAX_REDIRECTS) throw new IOException("市场重定向次数超过预算");
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) throw new IOException("市场重定向缺少目标");
                    URL next = checkedUrl(new URL(url, location).toString());
                    if (!sameOrigin(url, next)) {
                        throw new IOException("市场重定向改变来源，已拒绝");
                    }
                    disconnect(connection);
                    connection = null;
                    url = next;
                    continue;
                }
                if (status < 200 || status > 299) {
                    throw new IOException("市场请求失败：HTTP " + status);
                }
                long declared = connection.getContentLengthLong();
                if (declared > maxBytes) throw new IOException("市场响应超过体积预算");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    while (true) {
                        checkDeadline(started);
                        int count = input.read(buffer);
                        checkDeadline(started);
                        if (count < 0) break;
                        if (count == 0) continue;
                        if (output.size() + count > maxBytes) {
                            throw new IOException("市场响应超过体积预算");
                        }
                        output.write(buffer, 0, count);
                    }
                }
                if (declared >= 0L && output.size() != declared) {
                    throw new IOException("市场响应长度不完整");
                }
                return output.toByteArray();
            }
        } finally {
            disconnect(connection);
            IN_FLIGHT.decrementAndGet();
        }
    }

    private void checkDeadline(long started) throws IOException {
        if (clock.getAsLong() - started >= TOTAL_TIMEOUT_MS) {
            throw new IOException("市场请求超过 30 秒预算");
        }
        if (Thread.currentThread().isInterrupted()) throw new IOException("市场请求已取消");
    }

    private int remainingMs(long started) {
        long elapsed = clock.getAsLong() - started;
        long remaining = TOTAL_TIMEOUT_MS - elapsed;
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, remaining));
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static URL checkedUrl(String value) throws IOException {
        if (!PluginConstants.isValidHttpUrl(value)) {
            throw new IOException("仅支持无用户信息的 http/https 市场地址");
        }
        try {
            return new URL(value);
        } catch (Exception malformed) {
            throw new IOException("市场地址格式无效");
        }
    }

    private static boolean sameOrigin(URL first, URL second) {
        return first.getProtocol().equalsIgnoreCase(second.getProtocol())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URL url) {
        return url.getPort() != -1 ? url.getPort() : url.getDefaultPort();
    }

    private static void disconnect(HttpURLConnection connection) {
        if (connection == null) return;
        try {
            connection.disconnect();
        } catch (RuntimeException ignored) {
            // Disconnect failures cannot change the transfer result.
        }
    }
}
