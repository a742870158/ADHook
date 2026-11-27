package com.adhook.plugin;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plugin publisher signature (P-256 ECDSA, SHA-256).
 *
 * <p>Canonical signed bytes (UTF-8, no BOM/extra whitespace):</p>
 * <pre>
 * adhook-plugin-sig-v1\n
 * hex(sha256(raw plugin.json bytes))\n
 * for each manifest file name in ascending ASCII order:
 *   name + " " + hex(sha256(file bytes)) + "\n"
 * </pre>
 *
 * <p>Signature entry: {@code {"schema":1,"algorithm":"ECDSA_P256_SHA256","keyId":"...",
 * "signature":"<base64 DER>"}}. Only SHA256withECDSA over a P-256 key is accepted; the keyId
 * must equal the first 16 bytes (32 lowercase hex) of sha256(SPKI DER) and must match the
 * manifest publisher key id. All failures return false and never throw.</p>
 */
public final class PluginSignature {

    public static final String ALGORITHM = "ECDSA_P256_SHA256";
    private static final String CANONICAL_PREFIX = "adhook-plugin-sig-v1\n";

    private PluginSignature() {}

    /** First 16 bytes of sha256(SPKI DER) as lowercase hex (32 chars). */
    public static String keyId(byte[] spkiDer) {
        if (spkiDer == null || spkiDer.length == 0) return "";
        return PluginFiles.sha256Hex(spkiDer).substring(0, 32);
    }

    /** Full sha256(SPKI DER) lowercase hex, used as the pinned publisher fingerprint. */
    public static String fingerprint(byte[] spkiDer) {
        if (spkiDer == null || spkiDer.length == 0) return "";
        return PluginFiles.sha256Hex(spkiDer);
    }

    /**
     * Verifies the signature entry, the manifest/file byte binding and the keyId binding.
     * Returns false for every malformed, unsigned or tampered package.
     */
    public static boolean verify(PluginPackage pkg) {
        if (pkg == null || pkg.manifest == null || pkg.manifestBytes == null) return false;
        if (pkg.signatureBytes == null || pkg.signatureBytes.length == 0) return false;
        PluginManifest manifest = pkg.manifest;
        if (!manifest.isSigned()) return false;
        String keyId = manifest.getPublisherKeyId();
        byte[] spki = PluginFiles.decodeBase64(manifest.getPublisherPublicKeyBase64());
        if (spki == null || spki.length == 0) return false;
        if (!keyId.equals(keyId(spki))) return false;

        JSONObject signatureJson;
        try {
            signatureJson = new JSONObject(new String(pkg.signatureBytes, StandardCharsets.UTF_8));
        } catch (Exception malformed) {
            return false;
        }
        if (!(signatureJson.opt("schema") instanceof Number)
                || ((Number) signatureJson.opt("schema")).intValue() != PluginConstants.SCHEMA_VERSION) {
            return false;
        }
        if (!ALGORITHM.equals(signatureJson.optString("algorithm", ""))) return false;
        if (!keyId.equals(signatureJson.optString("keyId", ""))) return false;
        byte[] signatureDer = PluginFiles.decodeBase64(signatureJson.optString("signature", ""));
        if (signatureDer == null || signatureDer.length == 0
                || signatureDer.length > PluginConstants.MAX_SIGNATURE_BYTES) return false;

        // The signed manifest lists every file hash, so the payload bytes must still match it.
        if (!contentMatchesManifest(pkg)) return false;
        byte[] canonical = canonicalBytes(pkg.manifestBytes, manifest.getFiles());
        return verifyWithKey(canonical, signatureDer, spki);
    }

    /** Verifies raw signature bytes over already-canonical bytes with an SPKI public key. */
    public static boolean verifyWithKey(byte[] canonical, byte[] signatureDer, byte[] spkiDer) {
        if (canonical == null || signatureDer == null || spkiDer == null) return false;
        if (signatureDer.length == 0 || spkiDer.length == 0) return false;
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(spkiDer));
            if (!(publicKey instanceof ECPublicKey)) return false;
            ECParameterSpec parameters = ((ECPublicKey) publicKey).getParams();
            if (parameters == null || parameters.getOrder() == null
                    || parameters.getOrder().bitLength() != 256) {
                return false;
            }
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(canonical);
            return verifier.verify(signatureDer);
        } catch (Exception failure) {
            return false;
        }
    }

    /**
     * Builds the exact canonical bytes signed by the packaging tool.
     *
     * @param rawManifestBytes exact bytes of the {@code plugin.json} entry
     * @param fileHashes       manifest file name to lowercase sha256 mapping
     */
    public static byte[] canonicalBytes(byte[] rawManifestBytes, Map<String, String> fileHashes) {
        if (rawManifestBytes == null) return new byte[0];
        StringBuilder builder = new StringBuilder(CANONICAL_PREFIX);
        builder.append(PluginFiles.sha256Hex(rawManifestBytes)).append('\n');
        if (fileHashes != null) {
            for (Map.Entry<String, String> entry : new TreeMap<>(fileHashes).entrySet()) {
                builder.append(entry.getKey()).append(' ')
                        .append(PluginFiles.lowerHex(entry.getValue())).append('\n');
            }
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * True when the payload bytes in the package exactly match the signed manifest file list:
     * no missing, extra or modified file may slip between manifest verification and installation.
     */
    public static boolean contentMatchesManifest(PluginPackage pkg) {
        try {
            Map<String, String> declared = pkg.manifest.getFiles();
            if (declared.isEmpty() || !declared.containsKey(PluginZip.DEX_ENTRY)) return false;
            if (pkg.dex == null) return false;
            if (!PluginFiles.sha256Hex(pkg.dex).equalsIgnoreCase(declared.get(PluginZip.DEX_ENTRY))) {
                return false;
            }
            int assetCount = 0;
            for (Map.Entry<String, String> file : declared.entrySet()) {
                if (PluginZip.DEX_ENTRY.equals(file.getKey())) continue;
                byte[] bytes = pkg.assets != null ? pkg.assets.get(file.getKey()) : null;
                if (bytes == null) return false;
                if (!PluginFiles.sha256Hex(bytes).equalsIgnoreCase(file.getValue())) return false;
                assetCount++;
            }
            return pkg.assets != null && pkg.assets.size() == assetCount;
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
