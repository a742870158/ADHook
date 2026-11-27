package com.adhook.plugin;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Market index parsing, binding policy and bounded HTTP client regressions (no real network). */
public class PluginMarketTest {

    private static final String ID = "com.example.cleaner";
    private static final String TARGET = "com.example.target";
    private static final String INDEX_URL = "https://market.example/dir/index.json";

    // ------------------------------------------------------------------ index parsing

    private JSONObject releaseJson(PluginTestFixtures.Built built) throws Exception {
        JSONObject release = new JSONObject();
        release.put("version", built.manifest.getVersion());
        release.put("versionCode", built.manifest.getVersionCode());
        release.put("abi", built.manifest.getAbi());
        release.put("minBaseVersionCode", built.manifest.getMinBaseVersionCode());
        release.put("zipUrl", "plugins/cleaner.zip");
        release.put("sha256", PluginTestFixtures.sha256(built.zipBytes));
        release.put("size", built.zipBytes.length);
        JSONObject publisher = new JSONObject();
        publisher.put("keyId", built.manifest.getPublisherKeyId());
        publisher.put("publicKey", built.manifest.getPublisherPublicKeyBase64());
        release.put("publisher", publisher);
        release.put("publishedAt", 1_700_000_000_000L);
        release.put("changelog", "fix");
        return release;
    }

    private JSONObject indexJson(PluginTestFixtures.Built built) throws Exception {
        JSONObject entry = new JSONObject();
        entry.put("id", built.manifest.getId());
        entry.put("name", "Cleaner");
        entry.put("summary", "summary");
        entry.put("author", "Tester");
        entry.put("targets", new JSONArray().put(TARGET));
        entry.put("release", releaseJson(built));
        JSONObject index = new JSONObject();
        index.put("schema", 1);
        index.put("name", "Test Market");
        index.put("updatedAt", 1_700_000_000_000L);
        index.put("entries", new JSONArray().put(entry));
        return index;
    }

    private PluginTestFixtures.Built signedPackage() throws Exception {
        return PluginTestFixtures.build(ID, 1, TARGET, PluginTestFixtures.keyPair(), true);
    }

    @Test public void parsesIndexAndResolvesRelativeAndAbsoluteZipUrls() throws Exception {
        PluginTestFixtures.Built built = signedPackage();
        PluginMarketIndex index = PluginMarketIndex.parse(indexJson(built).toString());
        assertNotNull(index);
        assertEquals("Test Market", index.name());
        assertEquals(1_700_000_000_000L, index.updatedAt());
        assertEquals(1, index.size());
        PluginMarketEntry entry = index.find(ID);
        assertNotNull(entry);
        assertEquals(TARGET, entry.getTargets().get(0));
        PluginMarketRelease release = index.releaseFor(ID);
        assertEquals(PluginTestFixtures.sha256(built.zipBytes), release.getSha256());
        assertEquals("plugins/cleaner.zip", release.getZipUrl());
        assertEquals("https://market.example/dir/plugins/cleaner.zip",
                PluginMarketIndex.resolveZipUrl(INDEX_URL, release));
        assertNull(index.find("com.example.missing"));
        assertNull(index.releaseFor("com.example.missing"));

        JSONObject absolute = indexJson(built);
        absolute.getJSONArray("entries").getJSONObject(0).getJSONObject("release")
                .put("zipUrl", "https://cdn.example/cleaner.zip");
        PluginMarketRelease absoluteRelease = PluginMarketIndex.parse(absolute.toString())
                .releaseFor(ID);
        assertEquals("https://cdn.example/cleaner.zip",
                PluginMarketIndex.resolveZipUrl(INDEX_URL, absoluteRelease));
    }

    @Test public void rejectsIndexesWithMissingOrIllegalFields() throws Exception {
        PluginTestFixtures.Built built = signedPackage();
        JSONObject valid = indexJson(built);
        for (String field : new String[]{"version", "versionCode", "abi", "minBaseVersionCode",
                "zipUrl", "sha256", "size"}) {
            JSONObject broken = new JSONObject(valid.toString());
            broken.getJSONArray("entries").getJSONObject(0).getJSONObject("release")
                    .remove(field);
            assertNull("missing " + field, PluginMarketIndex.parse(broken.toString()));
        }
        for (String badUrl : new String[]{"ftp://market.example/x.zip", "javascript:alert(1)",
                "https://user:pass@market.example/x.zip", "https://market.example/x.zip#frag",
                "//market.example/x.zip", "../escape.zip"}) {
            JSONObject broken = new JSONObject(valid.toString());
            broken.getJSONArray("entries").getJSONObject(0).getJSONObject("release")
                    .put("zipUrl", badUrl);
            assertNull("bad url " + badUrl, PluginMarketIndex.parse(broken.toString()));
        }
        JSONObject canonical = new JSONObject(valid.toString());
        canonical.getJSONArray("entries").getJSONObject(0).getJSONObject("release")
                .put("sha256", "not-a-hash");
        assertNull(PluginMarketIndex.parse(canonical.toString()));

        JSONObject unknownTop = new JSONObject(valid.toString());
        unknownTop.put("extra", true);
        assertNull(PluginMarketIndex.parse(unknownTop.toString()));
        JSONObject unknownEntry = new JSONObject(valid.toString());
        unknownEntry.getJSONArray("entries").getJSONObject(0).put("extra", true);
        assertNull(PluginMarketIndex.parse(unknownEntry.toString()));
        String duplicateTop = "{\"schema\":1," + valid.toString().substring(1);
        assertNull(PluginMarketIndex.parse(duplicateTop));
    }

    @Test public void rejectsOversizedAndDuplicateIndexDocuments() throws Exception {
        PluginTestFixtures.Built built = signedPackage();
        assertNull(PluginMarketIndex.parse("{\"pad\":\""
                + "a".repeat(PluginConstants.MAX_INDEX_BYTES + 16) + "\"}"));
        JSONObject duplicated = indexJson(built);
        duplicated.getJSONArray("entries").put(
                duplicated.getJSONArray("entries").getJSONObject(0));
        assertNull(PluginMarketIndex.parse(duplicated.toString()));

        JSONObject tooMany = indexJson(built);
        JSONArray entries = new JSONArray();
        for (int i = 0; i < PluginConstants.MAX_PLUGINS + 1; i++) {
            entries.put(new JSONObject().put("id", "com.example.p" + i));
        }
        tooMany.put("entries", entries);
        assertNull(PluginMarketIndex.parse(tooMany.toString()));
    }

    @Test public void indexWithoutPublisherParsesButCannotBeInstalled() throws Exception {
        PluginTestFixtures.Built built = signedPackage();
        JSONObject index = indexJson(built);
        index.getJSONArray("entries").getJSONObject(0).getJSONObject("release")
                .remove("publisher");
        PluginMarketIndex parsed = PluginMarketIndex.parse(index.toString());
        assertNotNull(parsed);
        assertEquals(InstallError.MARKET_PUBLISHER_MISMATCH, PluginMarketInstallPolicy.verify(
                built.manifest, parsed.find(ID), PluginTestFixtures.sha256(built.zipBytes)));
    }

    // ------------------------------------------------------------ install binding policy

    @Test public void bindingPolicyAcceptsMatchingRelease() throws Exception {
        PluginTestFixtures.Built built = signedPackage();
        PluginMarketIndex index = PluginMarketIndex.parse(indexJson(built).toString());
        assertEquals(InstallError.OK, PluginMarketInstallPolicy.verify(built.manifest,
                index.find(ID), PluginTestFixtures.sha256(built.zipBytes)));
        assertEquals(InstallError.OK, PluginMarketInstallPolicy.verify(built.manifest,
                index.releaseFor(ID), PluginTestFixtures.sha256(built.zipBytes)));
    }

    @Test public void bindingPolicyRejectsEveryMismatch() throws Exception {
        PluginTestFixtures.Built built = signedPackage();
        String zipSha = PluginTestFixtures.sha256(built.zipBytes);

        JSONObject hash = indexJson(built);
        hash.getJSONArray("entries").getJSONObject(0).getJSONObject("release")
                .put("sha256", "b".repeat(64));
        assertEquals(InstallError.MARKET_HASH_MISMATCH, PluginMarketInstallPolicy.verify(
                built.manifest, PluginMarketIndex.parse(hash.toString()).find(ID), zipSha));

        JSONObject version = indexJson(built);
        version.getJSONArray("entries").getJSONObject(0).getJSONObject("release")
                .put("versionCode", 99);
        assertEquals(InstallError.MARKET_VERSION_MISMATCH, PluginMarketInstallPolicy.verify(
                built.manifest, PluginMarketIndex.parse(version.toString()).find(ID), zipSha));

        JSONObject published = indexJson(built);
        published.getJSONArray("entries").getJSONObject(0).getJSONObject("release")
                .put("publishedAt", 1L);
        assertEquals(InstallError.MARKET_VERSION_MISMATCH, PluginMarketInstallPolicy.verify(
                built.manifest, PluginMarketIndex.parse(published.toString()).find(ID), zipSha));

        JSONObject abi = indexJson(built);
        abi.getJSONArray("entries").getJSONObject(0).getJSONObject("release").put("abi", 3);
        assertEquals(InstallError.ABI_MISMATCH, PluginMarketInstallPolicy.verify(
                built.manifest, PluginMarketIndex.parse(abi.toString()).find(ID), zipSha));

        JSONObject publisher = indexJson(built);
        publisher.getJSONArray("entries").getJSONObject(0).getJSONObject("release")
                .getJSONObject("publisher").put("keyId", "0".repeat(32));
        assertEquals(InstallError.MARKET_PUBLISHER_MISMATCH, PluginMarketInstallPolicy.verify(
                built.manifest, PluginMarketIndex.parse(publisher.toString()).find(ID), zipSha));

        PluginTestFixtures.Built otherTarget = PluginTestFixtures.build(
                PluginTestFixtures.manifestJson(ID, 1, "com.example.othertarget", built.keyPair),
                built.dex, built.assets, built.keyPair, true);
        JSONObject targets = indexJson(otherTarget);
        assertEquals(InstallError.MARKET_TARGET_MISMATCH, PluginMarketInstallPolicy.verify(
                otherTarget.manifest, PluginMarketIndex.parse(targets.toString()).find(ID),
                PluginTestFixtures.sha256(otherTarget.zipBytes)));

        JSONObject idMismatch = indexJson(built);
        idMismatch.getJSONArray("entries").getJSONObject(0).put("id", "com.example.other");
        PluginMarketIndex idIndex = PluginMarketIndex.parse(idMismatch.toString());
        assertEquals(InstallError.MARKET_ID_MISMATCH, PluginMarketInstallPolicy.verify(
                built.manifest, idIndex.find("com.example.other"), zipSha));

        assertEquals(InstallError.MARKET_RELEASE_MISSING, PluginMarketInstallPolicy.verify(
                built.manifest, (PluginMarketRelease) null, zipSha));
        PluginMarketRelease validRelease = PluginMarketIndex.parse(indexJson(built).toString())
                .releaseFor(ID);
        assertEquals(InstallError.MARKET_INDEX_INVALID, PluginMarketInstallPolicy.verify(
                null, validRelease, zipSha));
    }

    // ------------------------------------------------------------------ HTTP client

    private static final class FakeHttp extends HttpURLConnection {
        private final int status;
        private final byte[] body;
        private final String location;
        private long declaredLength = -1L;

        FakeHttp(URL url, int status, byte[] body, String location) {
            super(url);
            this.status = status;
            this.body = body != null ? body : new byte[0];
            this.location = location;
        }

        FakeHttp declaredLength(long value) {
            this.declaredLength = value;
            return this;
        }

        @Override public void disconnect() {}

        @Override public boolean usingProxy() { return false; }

        @Override public void connect() {}

        @Override public int getResponseCode() { return status; }

        @Override public String getHeaderField(String name) {
            return "Location".equalsIgnoreCase(name) ? location : null;
        }

        @Override public long getContentLengthLong() {
            return declaredLength >= 0L ? declaredLength : body.length;
        }

        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(body);
        }
    }

    private static PluginMarketClient client(PluginMarketClient.ConnectionFactory factory) {
        return new PluginMarketClient(factory);
    }

    @Test public void downloadIndexDecodesUtf8Body() throws Exception {
        String body = indexJson(signedPackage()).toString();
        PluginMarketClient client = client(url -> new FakeHttp(url, 200,
                body.getBytes(StandardCharsets.UTF_8), null));
        assertEquals(body, client.downloadIndex(INDEX_URL));
    }

    @Test public void followsSameOriginRedirectUpToBudget() throws Exception {
        String body = "{\"ok\":true}";
        PluginMarketClient client = client(url -> url.getPath().equals("/dir/index.json")
                ? new FakeHttp(url, 302, new byte[0], "/dir/real.json")
                : new FakeHttp(url, 200, body.getBytes(StandardCharsets.UTF_8), null));
        assertEquals(body, client.downloadIndex(INDEX_URL));
    }

    @Test public void rejectsCrossOriginAndExcessiveRedirects() {
        PluginMarketClient crossOrigin = client(url -> new FakeHttp(url, 302, new byte[0],
                "https://evil.example/index.json"));
        IOException crossed = assertThrows(IOException.class,
                () -> crossOrigin.downloadIndex(INDEX_URL));
        assertTrue(crossed.getMessage().contains("来源"));

        PluginMarketClient loop = client(url -> new FakeHttp(url, 302, new byte[0], "/dir/index.json"));
        assertThrows(IOException.class, () -> loop.downloadIndex(INDEX_URL));
    }

    @Test public void rejectsHttpErrorsAndOversizedResponses() {
        PluginMarketClient failing = client(url -> new FakeHttp(url, 503, new byte[0], null));
        assertThrows(IOException.class, () -> failing.downloadIndex(INDEX_URL));

        byte[] oversized = new byte[PluginConstants.MAX_INDEX_BYTES + 1];
        PluginMarketClient big = client(url -> new FakeHttp(url, 200, oversized, null));
        assertThrows(IOException.class, () -> big.downloadIndex(INDEX_URL));

        PluginMarketClient declaredBig = client(url -> new FakeHttp(url, 200, new byte[0], null)
                .declaredLength(PluginConstants.MAX_INDEX_BYTES + 1L));
        assertThrows(IOException.class, () -> declaredBig.downloadIndex(INDEX_URL));
    }

    @Test public void timeoutBudgetIsEnforcedWithInjectedClock() {
        AtomicLong clock = new AtomicLong();
        LongSupplier advancing = () -> clock.addAndGet(16_000L);
        PluginMarketClient client = new PluginMarketClient(
                url -> new FakeHttp(url, 200, "{}".getBytes(StandardCharsets.UTF_8), null),
                advancing);
        assertThrows(IOException.class, () -> client.downloadIndex(INDEX_URL));
    }

    @Test public void downloadZipVerifiesSizeAndHash() throws Exception {
        byte[] payload = "zip-payload".getBytes(StandardCharsets.UTF_8);
        String sha = PluginTestFixtures.sha256(payload);
        PluginMarketClient client = client(url -> new FakeHttp(url, 200, payload, null));
        assertArrayEquals(payload, client.downloadZip("https://market.example/plugins/a.zip",
                payload.length, sha));
        assertThrows(IOException.class, () -> client.downloadZip(
                "https://market.example/plugins/a.zip", payload.length + 1, sha));
        assertThrows(IOException.class, () -> client.downloadZip(
                "https://market.example/plugins/a.zip", payload.length, "b".repeat(64)));
        assertThrows(IOException.class, () -> client.downloadZip(
                "https://market.example/plugins/a.zip", 0L, sha));
        assertThrows(IOException.class, () -> client.downloadZip(
                "https://market.example/plugins/a.zip", PluginConstants.MAX_ZIP_BYTES + 1L, sha));
        assertThrows(IOException.class, () -> client.downloadZip(
                "https://market.example/plugins/a.zip", payload.length, "not-a-hash"));
    }

    @Test public void rejectsUnsupportedRequestUrls() {
        PluginMarketClient client = client(url -> new FakeHttp(url, 200, new byte[0], null));
        assertThrows(IOException.class, () -> client.downloadIndex("ftp://market.example/index.json"));
        assertThrows(IOException.class,
                () -> client.downloadIndex("https://user:pass@market.example/index.json"));
        assertThrows(IOException.class, () -> client.downloadIndex("not a url"));
    }
}
