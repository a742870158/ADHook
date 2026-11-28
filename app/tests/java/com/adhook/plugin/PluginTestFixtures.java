package com.adhook.plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Shared JVM test fixtures: EC key pairs, manifest/zip builders and a raw ZIP writer that can
 * produce duplicates, traversal names and forged size fields that {@link java.util.zip.ZipOutputStream}
 * would refuse to emit. No Android framework types are used here so plain JUnit suites can share it.
 */
final class PluginTestFixtures {

    static final String ENTRY = "com.example.plugin.Entry";
    static final String TARGET = "com.example.target";

    private PluginTestFixtures() {}

    static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static byte[] spki(PublicKey publicKey) {
        return publicKey.getEncoded();
    }

    static String keyId(PublicKey publicKey) {
        return PluginSignature.keyId(spki(publicKey));
    }

    static String fingerprint(PublicKey publicKey) {
        return PluginSignature.fingerprint(spki(publicKey));
    }

    static String sha256(byte[] bytes) {
        return PluginFiles.sha256Hex(bytes);
    }

    static String sha256(String text) {
        return PluginFiles.sha256Hex(text);
    }

    static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] sampleDex(String seed) {
        return utf8("dex:" + seed);
    }

    static Map<String, byte[]> sampleAssets(String seed) {
        Map<String, byte[]> assets = new LinkedHashMap<>();
        assets.put("assets/rules.json", utf8("{\"source\":\"" + seed + "\"}"));
        return assets;
    }

    static Map<String, String> fileHashes(byte[] dex, Map<String, byte[]> assets) {
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put(PluginZip.DEX_ENTRY, sha256(dex));
        for (Map.Entry<String, byte[]> asset : assets.entrySet()) {
            hashes.put(asset.getKey(), sha256(asset.getValue()));
        }
        return hashes;
    }

    /** Base manifest with a placeholder files map; {@link #build} fills real hashes. */
    static JSONObject manifestJson(String id, int versionCode, String targetPackage, KeyPair keyPair)
            throws Exception {
        JSONObject manifest = new JSONObject();
        manifest.put("schema", PluginConstants.SCHEMA_VERSION);
        manifest.put("id", id);
        manifest.put("name", "Plugin " + id);
        manifest.put("version", "1.0." + versionCode);
        manifest.put("versionCode", versionCode);
        manifest.put("abi", PluginConstants.PLUGIN_ABI);
        manifest.put("minBaseVersionCode", 0);
        manifest.put("authorName", "Tester");
        manifest.put("authorUrl", "https://example.com/author");
        manifest.put("description", "test plugin");
        manifest.put("entry", ENTRY);
        JSONArray targets = new JSONArray();
        JSONObject target = new JSONObject();
        target.put("packageName", targetPackage);
        target.put("label", "Target");
        target.put("primary", "");
        target.put("processes", new JSONArray());
        targets.put(target);
        manifest.put("targets", targets);
        manifest.put("features", new JSONArray());
        manifest.put("files", new JSONObject());
        if (keyPair != null) {
            JSONObject publisher = new JSONObject();
            publisher.put("keyId", keyId(keyPair.getPublic()));
            publisher.put("publicKey", base64(spki(keyPair.getPublic())));
            publisher.put("publishedAt", 1_700_000_000_000L);
            manifest.put("publisher", publisher);
        }
        return manifest;
    }

    static byte[] sign(byte[] manifestBytes, Map<String, String> fileHashes, PrivateKey privateKey)
            throws Exception {
        byte[] canonical = PluginSignature.canonicalBytes(manifestBytes, fileHashes);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(canonical);
        return signer.sign();
    }

    static byte[] signatureEnvelope(String keyId, byte[] signatureDer) {
        JSONObject envelope = new JSONObject();
        try {
            envelope.put("schema", 1);
            envelope.put("algorithm", PluginSignature.ALGORITHM);
            envelope.put("keyId", keyId);
            envelope.put("signature", base64(signatureDer));
        } catch (org.json.JSONException ignored) {
            // Fixture values are non-null.
        }
        return utf8(envelope.toString());
    }

    /** Fully materialized package ready to write to disk or hand to the installer. */
    static final class Built {
        byte[] zipBytes;
        byte[] manifestBytes;
        PluginManifest manifest;
        byte[] dex;
        Map<String, byte[]> assets;
        byte[] signatureBytes;
        KeyPair keyPair;

        File write(File directory, String fileName) throws IOException {
            File file = new File(directory, fileName);
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(zipBytes);
            }
            return file;
        }
    }

    static Built build(String id, int versionCode, String targetPackage, KeyPair keyPair,
                       boolean sign) throws Exception {
        return build(manifestJson(id, versionCode, targetPackage, keyPair),
                sampleDex(id + ":" + versionCode), sampleAssets(id), keyPair, sign);
    }

    /**
     * Serializes the supplied manifest (with a real {@code files} map), optionally signs it and
     * packs the archive. Existing {@code files}/{@code publisher} entries are replaced so callers
     * can mutate every other field freely.
     */
    static Built build(JSONObject manifestBase, byte[] dex, Map<String, byte[]> assets,
                       KeyPair keyPair, boolean sign) throws Exception {
        Map<String, byte[]> safeAssets = assets != null ? assets : new LinkedHashMap<>();
        Map<String, String> hashes = fileHashes(dex, safeAssets);
        JSONObject files = new JSONObject();
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            files.put(entry.getKey(), entry.getValue());
        }
        manifestBase.put("files", files);
        if (keyPair == null) manifestBase.remove("publisher");
        byte[] manifestBytes = utf8(manifestBase.toString());
        Built built = new Built();
        built.keyPair = keyPair;
        built.manifestBytes = manifestBytes;
        built.manifest = PluginManifest.parse(new JSONObject(new String(manifestBytes,
                StandardCharsets.UTF_8)));
        built.dex = dex;
        built.assets = safeAssets;
        List<ZipEntry> entries = new ArrayList<>();
        entries.add(new ZipEntry(PluginZip.MANIFEST_ENTRY, manifestBytes));
        entries.add(new ZipEntry(PluginZip.DEX_ENTRY, dex));
        for (Map.Entry<String, byte[]> asset : safeAssets.entrySet()) {
            entries.add(new ZipEntry(asset.getKey(), asset.getValue()));
        }
        if (sign) {
            byte[] der = PluginTestFixtures.sign(manifestBytes, hashes, keyPair.getPrivate());
            built.signatureBytes = signatureEnvelope(keyId(keyPair.getPublic()), der);
            entries.add(new ZipEntry(PluginZip.SIGNATURE_ENTRY, built.signatureBytes));
        }
        built.zipBytes = zip(entries);
        return built;
    }

    static PluginPackage toPackage(Built built) {
        return new PluginPackage(built.manifestBytes, built.manifest, built.dex,
                new LinkedHashMap<>(built.assets), built.signatureBytes);
    }

    static InstalledPlugin installed(String id, int versionCode, String targetPackage) {
        return installed(id, versionCode, targetPackage, "", true);
    }

    /** Cheap unsigned catalogue entry (no key generation/signing) for store-limit tests. */
    static InstalledPlugin installedUnsigned(String id, int versionCode, String targetPackage) {
        try {
            JSONObject manifest = manifestJson(id, versionCode, targetPackage, null);
            JSONObject files = new JSONObject();
            files.put(PluginZip.DEX_ENTRY, "a".repeat(64));
            manifest.put("files", files);
            String manifestJson = manifest.toString();
            return new InstalledPlugin(id, "Plugin " + id, "1.0.0", versionCode,
                    PluginConstants.PLUGIN_ABI, 0, ENTRY, "Tester", "test plugin",
                    List.of(new PluginTarget(targetPackage, "Target", "", List.of())),
                    new ArrayList<>(), "", 1_700_000_000_000L,
                    "", "", "a".repeat(64), "b".repeat(64), manifestJson);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static InstalledPlugin installed(String id, int versionCode, String targetPackage,
                                     String dexSha256, boolean enabled) {
        try {
            KeyPair keyPair = keyPair();
            JSONObject manifest = manifestJson(id, versionCode, targetPackage, keyPair);
            Built built = build(manifest, sampleDex(id + ":" + versionCode), sampleAssets(id),
                    keyPair, true);
            return new InstalledPlugin(id, built.manifest.getName(), built.manifest.getVersion(),
                    versionCode, PluginConstants.PLUGIN_ABI, built.manifest.getMinBaseVersionCode(),
                    built.manifest.getEntry(), built.manifest.getAuthorName(),
                    built.manifest.getDescription(), built.manifest.getTargets(),
                    built.manifest.getFeatures(), "test-source", 1_700_000_000_000L,
                    built.manifest.getPublisherKeyId(), fingerprint(keyPair.getPublic()),
                    sha256(built.zipBytes),
                    dexSha256.isEmpty() ? sha256(built.dex) : dexSha256,
                    new String(built.manifestBytes, StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static InstalledPlugin installedWithFeatures(String id, int versionCode, String targetPackage,
                                                 int featureCount, int descriptionLength) {
        try {
            KeyPair keyPair = keyPair();
            JSONObject manifest = manifestJson(id, versionCode, targetPackage, keyPair);
            JSONArray features = new JSONArray();
            for (int i = 0; i < featureCount; i++) {
                JSONObject feature = new JSONObject();
                feature.put("id", "feature-" + i);
                feature.put("name", "Feature " + i);
                feature.put("description", "x".repeat(descriptionLength));
                feature.put("defaultEnabled", false);
                feature.put("implemented", true);
                features.put(feature);
            }
            manifest.put("features", features);
            Built built = build(manifest, sampleDex(id), sampleAssets(id), keyPair, true);
            return new InstalledPlugin(id, built.manifest.getName(), built.manifest.getVersion(),
                    versionCode, PluginConstants.PLUGIN_ABI, 0, built.manifest.getEntry(),
                    "Tester", "test plugin", built.manifest.getTargets(),
                    built.manifest.getFeatures(), "", 1_700_000_000_000L,
                    built.manifest.getPublisherKeyId(), fingerprint(keyPair.getPublic()),
                    sha256(built.zipBytes), sha256(built.dex),
                    new String(built.manifestBytes, StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ---------------------------------------------------------------- raw ZIP writer

    /** One archive entry; {@code declaredSize >= 0} forges the central-directory size. */
    static final class ZipEntry {
        final String name;
        final byte[] data;
        final long declaredSize;
        final boolean directory;

        ZipEntry(String name, byte[] data) {
            this(name, data, -1L, false);
        }

        ZipEntry(String name, byte[] data, long declaredSize, boolean directory) {
            this.name = name;
            this.data = data != null ? data : new byte[0];
            this.declaredSize = declaredSize;
            this.directory = directory;
        }
    }

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        List<ZipEntry> list = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            list.add(new ZipEntry(entry.getKey(), entry.getValue()));
        }
        return zip(list);
    }

    /** Minimal stored-only ZIP writer; supports duplicates, traversal names and forged sizes. */
    static byte[] zip(List<ZipEntry> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        List<Long> offsets = new ArrayList<>();
        for (ZipEntry entry : entries) {
            long offset = output.size();
            offsets.add(offset);
            byte[] name = entry.name.getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(entry.data);
            long size = entry.declaredSize >= 0 ? entry.declaredSize : entry.data.length;
            writeInt(output, 0x04034b50);
            writeShort(output, 20);
            writeShort(output, 0x0800); // UTF-8 names
            writeShort(output, 0);      // stored
            writeShort(output, 0);      // time
            writeShort(output, 0);      // date
            writeInt(output, (int) crc.getValue());
            writeInt(output, entry.data.length);
            writeInt(output, (int) size);
            writeShort(output, name.length);
            writeShort(output, 0);
            output.write(name);
            output.write(entry.data);
        }
        for (int i = 0; i < entries.size(); i++) {
            ZipEntry entry = entries.get(i);
            byte[] name = entry.name.getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(entry.data);
            long size = entry.declaredSize >= 0 ? entry.declaredSize : entry.data.length;
            writeInt(central, 0x02014b50);
            writeShort(central, 20);
            writeShort(central, 20);
            writeShort(central, 0x0800);
            writeShort(central, 0);
            writeShort(central, 0);
            writeShort(central, 0);
            writeInt(central, (int) crc.getValue());
            writeInt(central, entry.data.length);
            writeInt(central, (int) size);
            writeShort(central, name.length);
            writeShort(central, 0);
            writeShort(central, 0);
            writeShort(central, 0);
            writeShort(central, 0);
            writeInt(central, 0);
            writeInt(central, (int) (long) offsets.get(i));
            central.write(name);
        }
        int centralOffset = output.size();
        byte[] centralBytes = central.toByteArray();
        output.write(centralBytes);
        writeInt(output, 0x06054b50);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, entries.size());
        writeShort(output, entries.size());
        writeInt(output, centralBytes.length);
        writeInt(output, centralOffset);
        writeShort(output, 0);
        return output.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
        output.write((value >> 16) & 0xff);
        output.write((value >> 24) & 0xff);
    }
}
