package com.adhook.plugin;

import com.adhook.config.model.FeatureMeta;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parsed and validated {@code plugin.json}.
 *
 * <p>Validation is intentionally strict on identity, target and file-list fields; display-only
 * fields have bounded lengths. Unknown JSON keys are ignored so a newer packaging tool can add
 * metadata without breaking older runtimes, while the required contract stays mandatory.</p>
 */
public final class PluginManifest {

    private final int schema;
    private final String id;
    private final String name;
    private final String version;
    private final int versionCode;
    private final int abi;
    private final int minBaseVersionCode;
    private final String authorName;
    private final String authorUrl;
    private final String description;
    private final String readme;
    private final String entry;
    private final List<PluginTarget> targets;
    private final List<PluginFeature> features;
    private final Map<String, String> files;
    private final String publisherKeyId;
    private final String publisherPublicKeyBase64;
    private final long publishedAt;

    public PluginManifest(int schema, String id, String name, String version, int versionCode,
                          int abi, int minBaseVersionCode, String authorName, String authorUrl,
                          String description, String readme, String entry, List<PluginTarget> targets,
                          List<PluginFeature> features, Map<String, String> files,
                          String publisherKeyId, String publisherPublicKeyBase64, long publishedAt) {
        this.schema = schema;
        this.id = id;
        this.name = name;
        this.version = version;
        this.versionCode = versionCode;
        this.abi = abi;
        this.minBaseVersionCode = minBaseVersionCode;
        this.authorName = authorName != null ? authorName : "";
        this.authorUrl = authorUrl != null ? authorUrl : "";
        this.description = description != null ? description : "";
        this.readme = readme != null ? readme : "";
        this.entry = entry;
        this.targets = Collections.unmodifiableList(
                targets != null ? new ArrayList<>(targets) : new ArrayList<>());
        this.features = Collections.unmodifiableList(
                features != null ? new ArrayList<>(features) : new ArrayList<>());
        this.files = Collections.unmodifiableMap(
                files != null ? new LinkedHashMap<>(files) : new LinkedHashMap<>());
        this.publisherKeyId = publisherKeyId != null ? publisherKeyId : "";
        this.publisherPublicKeyBase64 =
                publisherPublicKeyBase64 != null ? publisherPublicKeyBase64 : "";
        this.publishedAt = publishedAt;
    }

    /**
     * Parses and fully validates a manifest object.
     *
     * @throws IllegalArgumentException with a safe, user-displayable message when invalid
     */
    public static PluginManifest parse(JSONObject json) {
        if (json == null) throw invalid("plugin.json 为空");
        requireOnlyKeys(json, "schema", "id", "name", "version", "versionCode", "abi",
                "minBaseVersionCode", "authorName", "authorUrl", "description", "readme", "entry",
                "targets", "features", "files", "publisher");
        int schema = requiredInt(json, "schema", PluginConstants.SCHEMA_VERSION,
                PluginConstants.SCHEMA_VERSION, "插件 schema 不兼容");
        String id = requiredString(json, "id", 0, 64, "插件 ID 缺失或过长");
        if (!PluginConstants.isValidPluginId(id)) throw invalid("插件 ID 格式无效");
        String name = requiredString(json, "name", 1, PluginConstants.MAX_PLUGIN_NAME_LENGTH,
                "插件名称无效");
        String version = requiredString(json, "version", 1, 64, "插件版本无效");
        if (!PluginConstants.isValidVersion(version)) throw invalid("插件版本格式无效");
        int versionCode = requiredInt(json, "versionCode", 1, Integer.MAX_VALUE, "插件 versionCode 必须为正数");
        int abi = requiredInt(json, "abi", 1, 1024, "插件 ABI 无效");
        int minBaseVersionCode = requiredInt(json, "minBaseVersionCode", 0, Integer.MAX_VALUE,
                "插件 minBaseVersionCode 无效");
        String authorName = requiredString(json, "authorName", 1, PluginConstants.MAX_NAME_LENGTH,
                "作者名称无效");
        String authorUrl = optionalString(json, "authorUrl", PluginConstants.MAX_URL_LENGTH);
        if (!authorUrl.isEmpty() && !PluginConstants.isValidHttpUrl(authorUrl)) {
            throw invalid("作者主页必须是 http/https 地址");
        }
        String description = requiredString(json, "description", 1,
                PluginConstants.MAX_DESCRIPTION_LENGTH, "插件说明无效");
        String readme = optionalString(json, "readme", PluginConstants.MAX_README_PATH_LENGTH);
        if (!readme.isEmpty() && !PluginConstants.isValidReadmePath(readme)) {
            throw invalid("插件 README 路径无效");
        }
        String entry = requiredString(json, "entry", 1, PluginConstants.MAX_ENTRY_LENGTH, "入口类名无效");
        if (!PluginConstants.isValidClassName(entry)) throw invalid("入口类名不是合法类名");

        Object rawTargets = json.opt("targets");
        if (!(rawTargets instanceof JSONArray)) throw invalid("插件未声明目标应用");
        JSONArray targetArray = (JSONArray) rawTargets;
        if (targetArray.length() < 1 || targetArray.length() > PluginConstants.MAX_TARGETS) {
            throw invalid("目标应用数量必须在 1-" + PluginConstants.MAX_TARGETS + " 之间");
        }
        List<PluginTarget> targets = new ArrayList<>();
        Set<String> targetPackages = new LinkedHashSet<>();
        for (int i = 0; i < targetArray.length(); i++) {
            Object rawTarget = targetArray.opt(i);
            if (!(rawTarget instanceof JSONObject)) throw invalid("目标应用条目格式无效");
            PluginTarget target = PluginTarget.fromJson((JSONObject) rawTarget);
            if (target == null) throw invalid("目标应用条目包含非法包名或受保护应用");
            if (!targetPackages.add(target.getPackageName())) throw invalid("目标应用包名重复");
            targets.add(target);
        }

        List<PluginFeature> features = new ArrayList<>();
        Object rawFeatures = json.opt("features");
        if (rawFeatures != null) {
            if (!(rawFeatures instanceof JSONArray)) throw invalid("插件功能列表格式无效");
            JSONArray featureArray = (JSONArray) rawFeatures;
            if (featureArray.length() > PluginConstants.MAX_FEATURES) {
                throw invalid("插件功能数量超过 " + PluginConstants.MAX_FEATURES);
            }
            Set<String> featureIds = new LinkedHashSet<>();
            for (int i = 0; i < featureArray.length(); i++) {
                Object rawFeature = featureArray.opt(i);
                if (!(rawFeature instanceof JSONObject)) throw invalid("插件功能条目格式无效");
                PluginFeature feature = PluginFeature.fromJson((JSONObject) rawFeature);
                if (feature == null) throw invalid("插件功能条目包含非法 ID 或名称");
                if (!featureIds.add(feature.getId())) throw invalid("插件功能 ID 重复");
                features.add(feature);
            }
        }

        Map<String, String> files = parseFiles(json.opt("files"));
        if (!readme.isEmpty() && !files.containsKey("assets/" + readme)) {
            throw invalid("插件 README 未包含在文件清单中");
        }

        String publisherKeyId = "";
        String publisherPublicKey = "";
        long publishedAt = 0L;
        Object rawPublisher = json.opt("publisher");
        if (rawPublisher != null) {
            if (!(rawPublisher instanceof JSONObject)) throw invalid("发布者信息格式无效");
            JSONObject publisher = (JSONObject) rawPublisher;
            requireOnlyKeys(publisher, "keyId", "publicKey", "publishedAt");
            publisherKeyId = optionalString(publisher, "keyId", 64);
            publisherPublicKey = optionalString(publisher, "publicKey",
                    PluginConstants.MAX_PUBLISHER_KEY_LENGTH);
            Object rawPublishedAt = publisher.opt("publishedAt");
            if (rawPublishedAt != null) {
                if (!(rawPublishedAt instanceof Number)) throw invalid("发布时间格式无效");
                publishedAt = ((Number) rawPublishedAt).longValue();
                if (publishedAt < 0L) throw invalid("发布时间无效");
            }
            if (publisherKeyId.isEmpty() != publisherPublicKey.isEmpty()) {
                throw invalid("发布者 keyId 与公钥必须同时存在");
            }
            if (!publisherKeyId.isEmpty()) {
                if (!PluginConstants.isHexKeyId(publisherKeyId)) throw invalid("发布者 keyId 格式无效");
                byte[] spki = PluginFiles.decodeBase64(publisherPublicKey);
                if (spki == null || spki.length < 32 || spki.length > PluginConstants.MAX_PUBLISHER_KEY_LENGTH
                        || spki[0] != 0x30) {
                    throw invalid("发布者公钥格式无效");
                }
            }
        }

        return new PluginManifest(schema, id, name, version, versionCode, abi, minBaseVersionCode,
                authorName, authorUrl, description, readme, entry, targets, features, files,
                publisherKeyId, publisherPublicKey, publishedAt);
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("schema", schema);
            json.put("id", id);
            json.put("name", name);
            json.put("version", version);
            json.put("versionCode", versionCode);
            json.put("abi", abi);
            json.put("minBaseVersionCode", minBaseVersionCode);
            json.put("authorName", authorName);
            if (!authorUrl.isEmpty()) json.put("authorUrl", authorUrl);
            json.put("description", description);
            if (!readme.isEmpty()) json.put("readme", readme);
            json.put("entry", entry);
            JSONArray targetArray = new JSONArray();
            for (PluginTarget target : targets) targetArray.put(target.toJson());
            json.put("targets", targetArray);
            JSONArray featureArray = new JSONArray();
            for (PluginFeature feature : features) featureArray.put(feature.toJson());
            json.put("features", featureArray);
            JSONObject fileObject = new JSONObject();
            for (Map.Entry<String, String> file : files.entrySet()) {
                fileObject.put(file.getKey(), file.getValue());
            }
            json.put("files", fileObject);
            JSONObject publisher = new JSONObject();
            publisher.put("keyId", publisherKeyId);
            publisher.put("publicKey", publisherPublicKeyBase64);
            publisher.put("publishedAt", publishedAt);
            json.put("publisher", publisher);
        } catch (org.json.JSONException ignored) {
            // All values are pre-validated strings/numbers; org.json cannot reject them.
        }
        return json;
    }

    public String getReadme() { return readme; }

    public List<FeatureMeta> featureMetas() {
        List<FeatureMeta> metas = new ArrayList<>(features.size());
        for (PluginFeature feature : features) metas.add(feature.toFeatureMeta());
        return metas;
    }

    /** Union of every package key the plugin may be routed under (packageName and primary). */
    public Set<String> coveredPackages() {
        Set<String> covered = new LinkedHashSet<>();
        for (PluginTarget target : targets) covered.addAll(target.coveredPackages());
        return covered;
    }

    public boolean isSigned() {
        return !publisherKeyId.isEmpty() && !publisherPublicKeyBase64.isEmpty();
    }

    private static Map<String, String> parseFiles(Object rawFiles) {
        if (!(rawFiles instanceof JSONObject)) throw invalid("插件文件清单缺失");
        JSONObject object = (JSONObject) rawFiles;
        if (object.length() < 1 || object.length() > PluginConstants.MAX_ZIP_ENTRIES) {
            throw invalid("插件文件清单数量无效");
        }
        Map<String, String> files = new LinkedHashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            if (!isDeclaredContentPath(name)) throw invalid("插件文件清单包含非法路径");
            Object rawHash = object.opt(name);
            if (!(rawHash instanceof String) || !PluginConstants.isHexSha256((String) rawHash)) {
                throw invalid("插件文件摘要无效");
            }
            files.put(name, PluginFiles.lowerHex((String) rawHash));
        }
        if (!files.containsKey("classes.dex")) throw invalid("插件文件清单缺少 classes.dex");
        return files;
    }

    private static boolean isDeclaredContentPath(String name) {
        if (!PluginConstants.isSafeArchivePath(name)) return false;
        return "classes.dex".equals(name) || name.startsWith("assets/");
    }

    private static int requiredInt(JSONObject json, String key, int min, int max, String message) {
        Object value = json.opt(key);
        if (!(value instanceof Number)) throw invalid(message);
        Number number = (Number) value;
        long longValue = number.longValue();
        if (number instanceof Double || number instanceof Float) throw invalid(message);
        if (longValue < min || longValue > max) throw invalid(message);
        return (int) longValue;
    }

    private static String requiredString(JSONObject json, String key, int minLength, int maxLength,
                                         String message) {
        String value = optionalString(json, key, maxLength);
        if (value.length() < Math.max(1, minLength)) throw invalid(message);
        return value;
    }

    private static String optionalString(JSONObject json, String key, int maxLength) {
        Object value = json.opt(key);
        if (value == null) return "";
        if (!(value instanceof String)) throw invalid("字段 " + key + " 格式无效");
        String text = (String) value;
        if (text.length() > maxLength) throw invalid("字段 " + key + " 过长");
        if (PluginConstants.hasControlCharacters(text)) throw invalid("字段 " + key + " 包含非法字符");
        return text;
    }

    private static boolean isHttpUrl(String value) {
        return (value.startsWith("https://") || value.startsWith("http://"))
                && value.length() > "https://".length();
    }

    /** Rejects unknown keys so a newer field cannot be silently ignored by an older runtime. */
    private static void requireOnlyKeys(JSONObject json, String... allowed) {
        Set<String> allow = new LinkedHashSet<>(java.util.Arrays.asList(allowed));
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            if (!allow.contains(keys.next())) throw invalid("plugin.json 包含未知字段");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    public int getSchema() { return schema; }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public int getVersionCode() { return versionCode; }
    public int getAbi() { return abi; }
    public int getMinBaseVersionCode() { return minBaseVersionCode; }
    public String getAuthorName() { return authorName; }
    public String getAuthorUrl() { return authorUrl; }
    public String getDescription() { return description; }
    public String getEntry() { return entry; }
    public List<PluginTarget> getTargets() { return targets; }
    public List<PluginFeature> getFeatures() { return features; }
    public Map<String, String> getFiles() { return files; }
    public String getPublisherKeyId() { return publisherKeyId; }
    public String getPublisherPublicKeyBase64() { return publisherPublicKeyBase64; }
    public long getPublishedAt() { return publishedAt; }

    /** Alias kept for callers that read the field as {@code publisherKeyId}. */
    public String publisherKeyId() { return publisherKeyId; }

    public byte[] publisherPublicKeyDer() {
        byte[] der = PluginFiles.decodeBase64(publisherPublicKeyBase64);
        return der != null ? der : new byte[0];
    }

    /** Detached copy (all nested values are immutable). */
    public PluginManifest copy() {
        return new PluginManifest(schema, id, name, version, versionCode, abi, minBaseVersionCode,
                authorName, authorUrl, description, readme, entry,
                new ArrayList<>(targets), new ArrayList<>(features), new LinkedHashMap<>(files),
                publisherKeyId, publisherPublicKeyBase64, publishedAt);
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}
