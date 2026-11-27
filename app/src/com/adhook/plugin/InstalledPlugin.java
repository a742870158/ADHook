package com.adhook.plugin;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Installed plugin metadata persisted in {@code files/plugins/installed.json}.
 *
 * <p>Immutable snapshot: every mutation goes through {@link PluginStore}, which replaces the
 * instance and commits the whole store atomically. {@code manifestJson} keeps the exact parsed
 * manifest text for display/edit surfaces; {@code dexSha256} and {@code minBaseVersionCode} are
 * derived installation facts the publisher and runtime table need without reopening the zip.</p>
 */
public final class InstalledPlugin {

    private final String id;
    private final String name;
    private final String version;
    private final int versionCode;
    private final int abi;
    private final int minBaseVersionCode;
    private final String entry;
    private final String author;
    private final String description;
    private final List<PluginTarget> targets;
    private final List<PluginFeature> features;
    private final String sourceId;
    private final boolean enabled;
    private final long installedAt;
    private final String publisherKeyId;
    private final String publisherFingerprint;
    private final String zipSha256;
    private final String dexSha256;
    private final String manifestJson;

    public InstalledPlugin(String id, String name, String version, int versionCode, int abi,
                           int minBaseVersionCode, String entry, String author, String description,
                           List<PluginTarget> targets, List<PluginFeature> features,
                           String sourceId, boolean enabled, long installedAt,
                           String publisherKeyId, String publisherFingerprint,
                           String zipSha256, String dexSha256, String manifestJson) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.versionCode = versionCode;
        this.abi = abi;
        this.minBaseVersionCode = minBaseVersionCode;
        this.entry = entry;
        this.author = author != null ? author : "";
        this.description = description != null ? description : "";
        this.targets = Collections.unmodifiableList(
                targets != null ? new ArrayList<>(targets) : new ArrayList<>());
        this.features = Collections.unmodifiableList(
                features != null ? new ArrayList<>(features) : new ArrayList<>());
        this.sourceId = sourceId != null ? sourceId : "";
        this.enabled = enabled;
        this.installedAt = installedAt;
        this.publisherKeyId = publisherKeyId != null ? publisherKeyId : "";
        this.publisherFingerprint = publisherFingerprint != null ? publisherFingerprint : "";
        this.zipSha256 = zipSha256 != null ? zipSha256 : "";
        this.dexSha256 = dexSha256 != null ? dexSha256 : "";
        this.manifestJson = manifestJson != null ? manifestJson : "";
    }

    /** Returns null when persisted metadata is malformed; the store treats that as corruption. */
    public static InstalledPlugin fromJson(JSONObject json) {
        if (json == null) return null;
        try {
            String id = requireString(json, "id", 64);
            if (!PluginConstants.isValidPluginId(id)) return null;
            String name = requireString(json, "name", PluginConstants.MAX_NAME_LENGTH);
            String version = requireString(json, "version", 64);
            int versionCode = requireInt(json, "versionCode", 1, Integer.MAX_VALUE);
            int abi = requireInt(json, "abi", 1, 1024);
            int minBaseVersionCode = requireInt(json, "minBaseVersionCode", 0, Integer.MAX_VALUE);
            String entry = requireString(json, "entry", PluginConstants.MAX_ENTRY_LENGTH);
            if (!PluginConstants.isValidClassName(entry)) return null;
            String author = optionalString(json, "author", PluginConstants.MAX_NAME_LENGTH);
            String description = optionalString(json, "description", PluginConstants.MAX_DESCRIPTION_LENGTH);
            String sourceId = optionalString(json, "sourceId", 128);
            String publisherKeyId = optionalString(json, "publisherKeyId", 64);
            if (!publisherKeyId.isEmpty() && !PluginConstants.isHexKeyId(publisherKeyId)) return null;
            String publisherFingerprint = optionalString(json, "publisherFingerprint", 64);
            if (!publisherFingerprint.isEmpty() && !PluginConstants.isHexSha256(publisherFingerprint)) return null;
            String zipSha256 = requireString(json, "zipSha256", 64);
            if (!PluginConstants.isHexSha256(zipSha256)) return null;
            String dexSha256 = optionalString(json, "dexSha256", 64);
            if (!dexSha256.isEmpty() && !PluginConstants.isHexSha256(dexSha256)) return null;
            long installedAt = requireLong(json, "installedAt", 0L);
            Object rawEnabled = json.opt("enabled");
            if (!(rawEnabled instanceof Boolean)) return null;
            boolean enabled = (Boolean) rawEnabled;
            // The stored manifest is raw JSON text and commonly ends with a newline; it is not a
            // display field, so it only needs a length budget and a valid embedded JSON document.
            Object rawManifestJson = json.opt("manifestJson");
            if (!(rawManifestJson instanceof String)) return null;
            String manifestJson = (String) rawManifestJson;
            if (manifestJson.isEmpty() || manifestJson.length() > PluginConstants.MAX_MANIFEST_BYTES * 2) {
                return null;
            }
            JSONObject manifestObject = new JSONObject(manifestJson);
            if (!id.equals(manifestObject.optString("id", ""))) return null;

            Object rawTargets = json.opt("targets");
            if (!(rawTargets instanceof org.json.JSONArray)) return null;
            List<PluginTarget> targets = new ArrayList<>();
            org.json.JSONArray targetArray = (org.json.JSONArray) rawTargets;
            if (targetArray.length() < 1 || targetArray.length() > PluginConstants.MAX_TARGETS) return null;
            for (int i = 0; i < targetArray.length(); i++) {
                Object rawTarget = targetArray.opt(i);
                if (!(rawTarget instanceof JSONObject)) return null;
                PluginTarget target = PluginTarget.fromJson((JSONObject) rawTarget);
                if (target == null) return null;
                targets.add(target);
            }
            List<PluginFeature> features = new ArrayList<>();
            Object rawFeatures = json.opt("features");
            if (rawFeatures != null) {
                if (!(rawFeatures instanceof org.json.JSONArray)) return null;
                org.json.JSONArray featureArray = (org.json.JSONArray) rawFeatures;
                if (featureArray.length() > PluginConstants.MAX_FEATURES) return null;
                for (int i = 0; i < featureArray.length(); i++) {
                    Object rawFeature = featureArray.opt(i);
                    if (!(rawFeature instanceof JSONObject)) return null;
                    PluginFeature feature = PluginFeature.fromJson((JSONObject) rawFeature);
                    if (feature == null) return null;
                    features.add(feature);
                }
            }
            return new InstalledPlugin(id, name, version, versionCode, abi, minBaseVersionCode,
                    entry, author, description, targets, features, sourceId, enabled, installedAt,
                    publisherKeyId, publisherFingerprint, zipSha256, dexSha256, manifestJson);
        } catch (Exception malformed) {
            return null;
        }
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("version", version);
            json.put("versionCode", versionCode);
            json.put("abi", abi);
            json.put("minBaseVersionCode", minBaseVersionCode);
            json.put("entry", entry);
            json.put("author", author);
            json.put("description", description);
            org.json.JSONArray targetArray = new org.json.JSONArray();
            for (PluginTarget target : targets) targetArray.put(target.toJson());
            json.put("targets", targetArray);
            org.json.JSONArray featureArray = new org.json.JSONArray();
            for (PluginFeature feature : features) featureArray.put(feature.toJson());
            json.put("features", featureArray);
            json.put("sourceId", sourceId);
            json.put("enabled", enabled);
            json.put("installedAt", installedAt);
            json.put("publisherKeyId", publisherKeyId);
            json.put("publisherFingerprint", publisherFingerprint);
            json.put("zipSha256", zipSha256);
            json.put("dexSha256", dexSha256);
            json.put("manifestJson", manifestJson);
        } catch (org.json.JSONException ignored) {
            // All values are pre-validated; org.json rejects only null names/values.
        }
        return json;
    }

    /** Parsed manifest view; empty object when the stored text is somehow unreadable. */
    public JSONObject manifestObject() {
        try {
            return new JSONObject(manifestJson);
        } catch (Exception failure) {
            return new JSONObject();
        }
    }

    public InstalledPlugin copyWithEnabled(boolean newEnabled) {
        return new InstalledPlugin(id, name, version, versionCode, abi, minBaseVersionCode, entry,
                author, description, targets, features, sourceId, newEnabled, installedAt,
                publisherKeyId, publisherFingerprint, zipSha256, dexSha256, manifestJson);
    }

    public InstalledPlugin copyWithSourceId(String newSourceId) {
        return new InstalledPlugin(id, name, version, versionCode, abi, minBaseVersionCode, entry,
                author, description, targets, features, newSourceId, enabled, installedAt,
                publisherKeyId, publisherFingerprint, zipSha256, dexSha256, manifestJson);
    }

    /** Package keys owned by this plugin (target packageName and primary). */
    public java.util.Set<String> coveredPackages() {
        java.util.LinkedHashSet<String> covered = new java.util.LinkedHashSet<>();
        for (PluginTarget target : targets) covered.addAll(target.coveredPackages());
        return covered;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public int getVersionCode() { return versionCode; }
    public int getAbi() { return abi; }
    public int getMinBaseVersionCode() { return minBaseVersionCode; }
    public String getEntry() { return entry; }
    public String getAuthor() { return author; }
    public String getDescription() { return description; }
    public List<PluginTarget> getTargets() { return targets; }
    public List<PluginFeature> getFeatures() { return features; }
    public String getSourceId() { return sourceId; }
    public boolean isEnabled() { return enabled; }
    /** Kotlin-visible boolean property alias ({@code plugin.enabled}) without renaming the contract. */
    public boolean getEnabled() { return enabled; }
    public long getInstalledAt() { return installedAt; }
    public String getPublisherKeyId() { return publisherKeyId; }
    public String getPublisherFingerprint() { return publisherFingerprint; }
    public String getZipSha256() { return zipSha256; }
    public String getDexSha256() { return dexSha256; }
    public String getManifestJson() { return manifestJson; }

    private static String requireString(JSONObject json, String key, int maxLength) {
        Object value = json.opt(key);
        if (!(value instanceof String)) throw new IllegalArgumentException(key);
        String text = (String) value;
        if (text.isEmpty() || text.length() > maxLength) throw new IllegalArgumentException(key);
        if (PluginConstants.hasControlCharacters(text)) throw new IllegalArgumentException(key);
        return text;
    }

    private static String optionalString(JSONObject json, String key, int maxLength) {
        Object value = json.opt(key);
        if (value == null) return "";
        if (!(value instanceof String)) throw new IllegalArgumentException(key);
        String text = (String) value;
        if (text.length() > maxLength || PluginConstants.hasControlCharacters(text)) {
            throw new IllegalArgumentException(key);
        }
        return text;
    }

    private static int requireInt(JSONObject json, String key, int min, int max) {
        Object value = json.opt(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException(key);
        Number number = (Number) value;
        if (number instanceof Double || number instanceof Float) throw new IllegalArgumentException(key);
        long longValue = number.longValue();
        if (longValue < min || longValue > max) throw new IllegalArgumentException(key);
        return (int) longValue;
    }

    private static long requireLong(JSONObject json, String key, long min) {
        Object value = json.opt(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException(key);
        Number number = (Number) value;
        long longValue = number.longValue();
        if (longValue < min) throw new IllegalArgumentException(key);
        return longValue;
    }
}
