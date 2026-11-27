package com.adhook.plugin;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Strict reader for the plugin archive.
 *
 * <p>The central directory is inspected first for structure and declared sizes; the manifest is
 * parsed before any content file is trusted; every content file is then read with hard byte
 * budgets and verified against the manifest's SHA-256 list. Hash and size checks use the actual
 * decompressed bytes, not only the central-directory metadata.</p>
 */
public final class PluginZip {

    public static final String MANIFEST_ENTRY = "plugin.json";
    public static final String DEX_ENTRY = "classes.dex";
    public static final String SIGNATURE_ENTRY = "META-INF/plugin.sig";

    private PluginZip() {}

    public static PluginPackage read(File zipFile) throws PluginValidationException {
        if (zipFile == null || !zipFile.isFile()) {
            throw new PluginValidationException("插件包不存在或不是文件", PluginValidationException.Kind.IO);
        }
        long length = zipFile.length();
        if (length <= 0L) throw new PluginValidationException("插件包为空");
        if (length > PluginConstants.MAX_ZIP_BYTES) {
            throw new PluginValidationException("插件包超过 " + PluginConstants.MAX_ZIP_BYTES + " 字节预算");
        }
        try (ZipFile zip = new ZipFile(zipFile)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            if (entries.isEmpty()) throw new PluginValidationException("插件包没有条目");
            if (entries.size() > PluginConstants.MAX_ZIP_ENTRIES) {
                throw new PluginValidationException("插件包条目超过 " + PluginConstants.MAX_ZIP_ENTRIES);
            }
            ZipEntry manifestEntry = null;
            ZipEntry dexEntry = null;
            ZipEntry signatureEntry = null;
            Map<String, ZipEntry> assetEntries = new LinkedHashMap<>();
            Set<String> names = new LinkedHashSet<>();
            long declaredTotal = 0L;
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (name == null || !PluginConstants.isSafeArchivePath(name) || entry.isDirectory()) {
                    throw new PluginValidationException("插件包包含非法条目路径");
                }
                if (!names.add(name)) throw new PluginValidationException("插件包条目重复：" + name);
                if (MANIFEST_ENTRY.equals(name)) {
                    manifestEntry = entry;
                } else if (DEX_ENTRY.equals(name)) {
                    dexEntry = entry;
                } else if (SIGNATURE_ENTRY.equals(name)) {
                    signatureEntry = entry;
                } else if (name.startsWith("assets/") && name.length() > "assets/".length()) {
                    assetEntries.put(name, entry);
                } else {
                    throw new PluginValidationException("插件包包含未允许的条目：" + name);
                }
                long declared = entry.getSize();
                if (declared >= 0L) {
                    declaredTotal += declared;
                    if (declaredTotal > PluginConstants.MAX_UNCOMPRESSED_BYTES) {
                        throw new PluginValidationException("插件包解压后超过 " + PluginConstants.MAX_UNCOMPRESSED_BYTES
                                + " 字节预算");
                    }
                }
            }
            if (manifestEntry == null) throw new PluginValidationException("插件包缺少 plugin.json");
            if (dexEntry == null) throw new PluginValidationException("插件包缺少 classes.dex");

            long[] actualTotal = new long[1];
            byte[] manifestBytes = readEntry(zip, manifestEntry, PluginConstants.MAX_MANIFEST_BYTES,
                    null, actualTotal);
            if (manifestBytes.length == 0) throw new PluginValidationException("plugin.json 为空");
            PluginManifest manifest;
            try {
                rejectUnsafeManifestBytes(manifestBytes);
                manifest = PluginManifest.parse(new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8)));
            } catch (JSONException malformed) {
                throw new PluginValidationException("plugin.json 不是合法 JSON", PluginValidationException.Kind.MANIFEST);
            } catch (IllegalArgumentException invalid) {
                throw new PluginValidationException(invalid.getMessage(), PluginValidationException.Kind.MANIFEST);
            }

            Set<String> declaredFiles = new LinkedHashSet<>(manifest.getFiles().keySet());
            Set<String> actualFiles = new LinkedHashSet<>();
            actualFiles.add(DEX_ENTRY);
            actualFiles.addAll(assetEntries.keySet());
            for (String name : actualFiles) {
                if (!declaredFiles.contains(name)) {
                    throw new PluginValidationException("插件包条目未在文件清单声明：" + name,
                            PluginValidationException.Kind.MANIFEST);
                }
            }
            for (String name : declaredFiles) {
                if (!actualFiles.contains(name)) {
                    throw new PluginValidationException("插件包缺少文件清单声明的文件：" + name,
                            PluginValidationException.Kind.MANIFEST);
                }
            }

            byte[] dex = readEntry(zip, dexEntry, PluginConstants.MAX_DEX_BYTES,
                    manifest.getFiles().get(DEX_ENTRY), actualTotal);
            Map<String, byte[]> assets = new LinkedHashMap<>();
            for (Map.Entry<String, ZipEntry> asset : assetEntries.entrySet()) {
                String name = asset.getKey();
                byte[] bytes = readEntry(zip, asset.getValue(), PluginConstants.MAX_ASSET_BYTES,
                        manifest.getFiles().get(name), actualTotal);
                assets.put(name, bytes);
            }
            byte[] signature = null;
            if (signatureEntry != null) {
                signature = readEntry(zip, signatureEntry, PluginConstants.MAX_SIGNATURE_BYTES,
                        null, actualTotal);
            }
            return new PluginPackage(manifestBytes, manifest, dex, assets, signature);
        } catch (IOException failure) {
            // Unreadable/encrypted/corrupt archives are package-format failures, not storage errors.
            throw new PluginValidationException("无法读取插件包", PluginValidationException.Kind.ZIP);
        }
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry, long maxBytes, String expectedSha256,
                                    long[] actualTotal) throws PluginValidationException {
        long declared = entry.getSize();
        if (declared >= 0L && declared > maxBytes) {
            throw new PluginValidationException("条目 " + entry.getName() + " 超过体积预算");
        }
        byte[] bytes;
        try (InputStream input = zip.getInputStream(entry)) {
            bytes = PluginFiles.readBounded(input, maxBytes);
        } catch (IOException failure) {
            throw new PluginValidationException("条目 " + entry.getName() + " 解压失败或超过体积预算");
        }
        if (declared >= 0L && bytes.length != declared) {
            throw new PluginValidationException("条目 " + entry.getName() + " 解压大小与声明不符");
        }
        actualTotal[0] += bytes.length;
        if (actualTotal[0] > PluginConstants.MAX_UNCOMPRESSED_BYTES) {
            throw new PluginValidationException("插件包解压后超过 " + PluginConstants.MAX_UNCOMPRESSED_BYTES
                    + " 字节预算");
        }
        if (expectedSha256 != null) {
            String actual = PluginFiles.sha256Hex(bytes);
            if (!actual.equals(expectedSha256.toLowerCase(Locale.ROOT))) {
                throw new PluginValidationException("条目 " + entry.getName() + " 的 SHA-256 校验失败");
            }
        }
        return bytes;
    }

    /**
     * Rejects a UTF-8 BOM and duplicate top-level JSON keys. org.json keeps the last duplicate,
     * which would let the signed bytes and the parsed manifest disagree about which value won.
     */
    private static void rejectUnsafeManifestBytes(byte[] bytes) throws PluginValidationException {
        if (PluginJson.hasUtf8Bom(bytes)) {
            throw new PluginValidationException("plugin.json 不得包含 UTF-8 BOM",
                    PluginValidationException.Kind.MANIFEST);
        }
        if (PluginJson.hasDuplicateTopLevelKeys(new String(bytes, StandardCharsets.UTF_8))) {
            throw new PluginValidationException("plugin.json 包含重复字段",
                    PluginValidationException.Kind.MANIFEST);
        }
    }

    /** Exposed for diagnostics and tests: whether an archive path is an allowed plugin entry. */
    public static boolean isAllowedEntry(String name) {
        if (name == null || !PluginConstants.isSafeArchivePath(name)) return false;
        if (MANIFEST_ENTRY.equals(name) || DEX_ENTRY.equals(name) || SIGNATURE_ENTRY.equals(name)) return true;
        return name.startsWith("assets/") && name.length() > "assets/".length();
    }

    static List<String> sortedNames(Set<String> names) {
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        return sorted;
    }
}
