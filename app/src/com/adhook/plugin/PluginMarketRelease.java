package com.adhook.plugin;

import org.json.JSONObject;

import java.net.URL;

/**
 * One published plugin release inside a market index.
 *
 * <p>{@code zipUrl} may be absolute or relative to the index URL; it is kept verbatim and
 * resolved by {@link #resolvedZipUrl(String)} at download time so the parsed index stays
 * address-independent. Publisher fields are optional here — an index without a publisher is
 * parsed but can never satisfy {@link PluginMarketInstallPolicy}.</p>
 */
public final class PluginMarketRelease {

    private final String version;
    private final int versionCode;
    private final int abi;
    private final int minBaseVersionCode;
    private final String zipUrl;
    private final String sha256;
    private final long size;
    private final String publisherKeyId;
    private final String publisherPublicKeyBase64;
    private final long publishedAt;
    private final String changelog;

    public PluginMarketRelease(String version, int versionCode, int abi, int minBaseVersionCode,
                               String zipUrl, String sha256, long size, String publisherKeyId,
                               String publisherPublicKeyBase64, long publishedAt, String changelog) {
        this.version = version;
        this.versionCode = versionCode;
        this.abi = abi;
        this.minBaseVersionCode = minBaseVersionCode;
        this.zipUrl = zipUrl != null ? zipUrl : "";
        this.sha256 = sha256 != null ? sha256.toLowerCase(java.util.Locale.ROOT) : "";
        this.size = size;
        this.publisherKeyId = publisherKeyId != null ? publisherKeyId : "";
        this.publisherPublicKeyBase64 = publisherPublicKeyBase64 != null ? publisherPublicKeyBase64 : "";
        this.publishedAt = publishedAt;
        this.changelog = changelog != null ? changelog : "";
    }

    /** Returns null for any invalid release; a partially valid release is never accepted. */
    public static PluginMarketRelease fromJson(JSONObject json) {
        if (json == null) return null;
        try {
            requireOnlyKeys(json, "version", "versionCode", "abi", "minBaseVersionCode", "zipUrl",
                    "sha256", "size", "publisher", "publishedAt", "changelog");
            Object rawVersion = json.opt("version");
            if (!(rawVersion instanceof String) || !PluginConstants.isValidVersion((String) rawVersion)) {
                return null;
            }
            Object rawVersionCode = json.opt("versionCode");
            if (!(rawVersionCode instanceof Number)) return null;
            int versionCode = ((Number) rawVersionCode).intValue();
            if (versionCode <= 0) return null;
            Object rawAbi = json.opt("abi");
            if (!(rawAbi instanceof Number)) return null;
            int abi = ((Number) rawAbi).intValue();
            if (abi < 1 || abi > 1024) return null;
            Object rawMinBase = json.opt("minBaseVersionCode");
            if (!(rawMinBase instanceof Number)) return null;
            int minBaseVersionCode = ((Number) rawMinBase).intValue();
            if (minBaseVersionCode < 0) return null;
            Object rawZip = json.opt("zipUrl");
            if (!(rawZip instanceof String)) return null;
            String zipUrl = (String) rawZip;
            if (!PluginConstants.isValidHttpUrl(zipUrl)
                    && !PluginConstants.isValidRelativeReference(zipUrl)) return null;
            Object rawSha = json.opt("sha256");
            if (!(rawSha instanceof String) || !PluginConstants.isHexSha256((String) rawSha)) return null;
            Object rawSize = json.opt("size");
            if (!(rawSize instanceof Number)) return null;
            long size = ((Number) rawSize).longValue();
            if (size <= 0L || size > PluginConstants.MAX_ZIP_BYTES) return null;
            String publisherKeyId = "";
            String publisherPublicKey = "";
            Object rawPublisher = json.opt("publisher");
            if (rawPublisher != null) {
                if (!(rawPublisher instanceof JSONObject)) return null;
                JSONObject publisher = (JSONObject) rawPublisher;
                requireOnlyKeys(publisher, "keyId", "publicKey");
                Object rawKeyId = publisher.opt("keyId");
                Object rawPublicKey = publisher.opt("publicKey");
                if (rawKeyId != null && !(rawKeyId instanceof String)) return null;
                if (rawPublicKey != null && !(rawPublicKey instanceof String)) return null;
                publisherKeyId = rawKeyId instanceof String ? (String) rawKeyId : "";
                publisherPublicKey = rawPublicKey instanceof String ? (String) rawPublicKey : "";
                if (publisherKeyId.isEmpty() != publisherPublicKey.isEmpty()) return null;
                if (!publisherKeyId.isEmpty()) {
                    if (!PluginConstants.isHexKeyId(publisherKeyId)) return null;
                    byte[] der = PluginFiles.decodeBase64(publisherPublicKey);
                    if (der == null || der.length < 32
                            || der.length > PluginConstants.MAX_PUBLISHER_KEY_LENGTH
                            || der[0] != 0x30) return null;
                }
            }
            long publishedAt = 0L;
            Object rawPublishedAt = json.opt("publishedAt");
            if (rawPublishedAt != null) {
                if (!(rawPublishedAt instanceof Number)) return null;
                publishedAt = ((Number) rawPublishedAt).longValue();
                if (publishedAt < 0L) return null;
            }
            Object rawChangelog = json.opt("changelog");
            if (rawChangelog != null && !(rawChangelog instanceof String)) return null;
            String changelog = rawChangelog instanceof String ? (String) rawChangelog : "";
            if (changelog.length() > 4096 || PluginConstants.hasControlCharacters(changelog)) return null;
            return new PluginMarketRelease((String) rawVersion, versionCode, abi, minBaseVersionCode,
                    zipUrl, (String) rawSha, size, publisherKeyId, publisherPublicKey, publishedAt,
                    changelog);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("version", version);
            json.put("versionCode", versionCode);
            json.put("abi", abi);
            json.put("minBaseVersionCode", minBaseVersionCode);
            json.put("zipUrl", zipUrl);
            json.put("sha256", sha256);
            json.put("size", size);
            if (!publisherKeyId.isEmpty()) {
                JSONObject publisher = new JSONObject();
                publisher.put("keyId", publisherKeyId);
                publisher.put("publicKey", publisherPublicKeyBase64);
                json.put("publisher", publisher);
            }
            json.put("publishedAt", publishedAt);
            json.put("changelog", changelog);
        } catch (org.json.JSONException ignored) {
            // All values are pre-validated; org.json rejects only null names/values.
        }
        return json;
    }

    /**
     * Resolves {@link #zipUrl} against the index URL; returns "" for an unusable result.
     * Relative URLs are resolved with {@link URL#URL(URL, String)} semantics.
     */
    public String resolvedZipUrl(String indexUrl) {
        if (PluginConstants.isValidHttpUrl(zipUrl)) return zipUrl;
        try {
            URL resolved = new URL(new URL(indexUrl), zipUrl);
            String value = resolved.toString();
            return PluginConstants.isValidHttpUrl(value) ? value : "";
        } catch (Exception malformed) {
            return "";
        }
    }

    public boolean hasPublisher() {
        return !publisherKeyId.isEmpty() && !publisherPublicKeyBase64.isEmpty();
    }

    public byte[] publisherPublicKeyDer() {
        byte[] der = PluginFiles.decodeBase64(publisherPublicKeyBase64);
        return der != null ? der : new byte[0];
    }

    public String getVersion() { return version; }
    public int getVersionCode() { return versionCode; }
    public int getAbi() { return abi; }
    public int getMinBaseVersionCode() { return minBaseVersionCode; }
    public String getZipUrl() { return zipUrl; }
    public String getSha256() { return sha256; }
    public long getSize() { return size; }
    public String getPublisherKeyId() { return publisherKeyId; }
    public String getPublisherPublicKeyBase64() { return publisherPublicKeyBase64; }
    public long getPublishedAt() { return publishedAt; }
    public String getChangelog() { return changelog; }

    private static void requireOnlyKeys(JSONObject json, String... allowed) {
        java.util.Set<String> allow = new java.util.LinkedHashSet<>(java.util.Arrays.asList(allowed));
        java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            if (!allow.contains(keys.next())) throw new IllegalArgumentException("unknown");
        }
    }
}
