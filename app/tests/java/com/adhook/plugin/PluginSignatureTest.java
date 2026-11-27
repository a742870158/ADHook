package com.adhook.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Canonical-byte and ECDSA P-256 verification regressions. */
public class PluginSignatureTest {

    private static final String ID = "com.example.cleaner";
    private static final String TARGET = "com.example.target";

    @Test public void validSignatureVerifies() throws Exception {
        PluginTestFixtures.Built built = PluginTestFixtures.build(ID, 1, TARGET,
                PluginTestFixtures.keyPair(), true);
        assertTrue(PluginSignature.verify(PluginTestFixtures.toPackage(built)));
    }

    @Test public void canonicalBytesFollowTheDocumentedFormat() throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        hashes.put("classes.dex", PluginTestFixtures.sha256("dex".getBytes(StandardCharsets.UTF_8)));
        hashes.put("assets/a.json", PluginTestFixtures.sha256("{}".getBytes(StandardCharsets.UTF_8)));
        byte[] manifestBytes = "{\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        byte[] canonical = PluginSignature.canonicalBytes(manifestBytes, hashes);
        String expected = "adhook-plugin-sig-v1\n"
                + PluginTestFixtures.sha256(manifestBytes) + "\n"
                + "assets/a.json " + hashes.get("assets/a.json") + "\n"
                + "classes.dex " + hashes.get("classes.dex") + "\n";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), canonical);
    }

    @Test public void tamperedManifestBytesFail() throws Exception {
        PluginTestFixtures.Built built = PluginTestFixtures.build(ID, 1, TARGET,
                PluginTestFixtures.keyPair(), true);
        PluginPackage pkg = PluginTestFixtures.toPackage(built);
        byte[] tampered = new String(built.manifestBytes, StandardCharsets.UTF_8)
                .replace("\"name\":\"", "\"name\":\"x").getBytes(StandardCharsets.UTF_8);
        PluginPackage changed = new PluginPackage(tampered, pkg.manifest, pkg.dex,
                new LinkedHashMap<>(pkg.assets), pkg.signatureBytes);
        assertFalse(PluginSignature.verify(changed));
    }

    @Test public void tamperedDexAssetOrExtraAssetFails() throws Exception {
        PluginTestFixtures.Built built = PluginTestFixtures.build(ID, 1, TARGET,
                PluginTestFixtures.keyPair(), true);
        byte[] changedDex = built.dex.clone();
        changedDex[0] = (byte) (changedDex[0] ^ 0x01);
        PluginPackage dexChanged = new PluginPackage(built.manifestBytes, built.manifest, changedDex,
                new LinkedHashMap<>(built.assets), built.signatureBytes);
        assertFalse(PluginSignature.verify(dexChanged));

        Map<String, byte[]> changedAssets = new LinkedHashMap<>(built.assets);
        changedAssets.put("assets/rules.json", "{}".getBytes(StandardCharsets.UTF_8));
        PluginPackage assetChanged = new PluginPackage(built.manifestBytes, built.manifest, built.dex,
                changedAssets, built.signatureBytes);
        assertFalse(PluginSignature.verify(assetChanged));

        Map<String, byte[]> extraAssets = new LinkedHashMap<>(built.assets);
        extraAssets.put("assets/extra.json", "{}".getBytes(StandardCharsets.UTF_8));
        PluginPackage extra = new PluginPackage(built.manifestBytes, built.manifest, built.dex,
                extraAssets, built.signatureBytes);
        assertFalse(PluginSignature.verify(extra));
    }

    @Test public void unsignedPackageFailsVerification() throws Exception {
        PluginTestFixtures.Built built = PluginTestFixtures.build(ID, 1, TARGET, null, false);
        assertFalse(PluginSignature.verify(PluginTestFixtures.toPackage(built)));
        assertFalse(PluginSignature.verify(new PluginPackage(built.manifestBytes, built.manifest,
                built.dex, new LinkedHashMap<>(built.assets), null)));
    }

    @Test public void keyIdMustMatchManifestAndKeyMaterial() throws Exception {
        KeyPair signer = PluginTestFixtures.keyPair();
        KeyPair other = PluginTestFixtures.keyPair();
        JSONObject manifest = PluginTestFixtures.manifestJson(ID, 1, TARGET, signer);
        JSONObject publisher = manifest.getJSONObject("publisher");
        publisher.put("keyId", PluginTestFixtures.keyId(other.getPublic()));
        PluginTestFixtures.Built built = PluginTestFixtures.build(manifest,
                PluginTestFixtures.sampleDex("x"), PluginTestFixtures.sampleAssets("x"), signer, true);
        assertFalse(PluginSignature.verify(PluginTestFixtures.toPackage(built)));

        JSONObject envelope = new JSONObject(new String(built.signatureBytes, StandardCharsets.UTF_8));
        assertEquals(PluginTestFixtures.keyId(signer.getPublic()), envelope.optString("keyId"));
        assertEquals(PluginTestFixtures.keyId(other.getPublic()),
                built.manifest.getPublisherKeyId());
        assertFalse(PluginSignature.verify(PluginTestFixtures.toPackage(built)));
    }

    @Test public void nonEcdsaAlgorithmIsRejected() throws Exception {
        PluginTestFixtures.Built built = PluginTestFixtures.build(ID, 1, TARGET,
                PluginTestFixtures.keyPair(), true);
        String envelope = new String(built.signatureBytes, StandardCharsets.UTF_8);
        byte[] replaced = envelope.replace(PluginSignature.ALGORITHM, "RSA_SHA256")
                .getBytes(StandardCharsets.UTF_8);
        PluginPackage pkg = new PluginPackage(built.manifestBytes, built.manifest, built.dex,
                new LinkedHashMap<>(built.assets), replaced);
        assertFalse(PluginSignature.verify(pkg));
        assertTrue(PluginSignature.verify(PluginTestFixtures.toPackage(built)));
    }

    @Test public void wrongSignatureSchemaIsRejected() throws Exception {
        PluginTestFixtures.Built built = PluginTestFixtures.build(ID, 1, TARGET,
                PluginTestFixtures.keyPair(), true);
        String envelope = new String(built.signatureBytes, StandardCharsets.UTF_8);
        byte[] replaced = envelope.replace("\"schema\":1", "\"schema\":2")
                .getBytes(StandardCharsets.UTF_8);
        PluginPackage pkg = new PluginPackage(built.manifestBytes, built.manifest, built.dex,
                new LinkedHashMap<>(built.assets), replaced);
        assertFalse(PluginSignature.verify(pkg));
    }

    @Test public void verifyWithKeyAcceptsOnlyMatchingBytes() throws Exception {
        KeyPair keyPair = PluginTestFixtures.keyPair();
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("classes.dex", PluginTestFixtures.sha256("dex".getBytes(StandardCharsets.UTF_8)));
        byte[] manifestBytes = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] canonical = PluginSignature.canonicalBytes(manifestBytes, hashes);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(canonical);
        byte[] der = signer.sign();
        byte[] spki = PluginTestFixtures.spki(keyPair.getPublic());
        assertTrue(PluginSignature.verifyWithKey(canonical, der, spki));
        assertFalse(PluginSignature.verifyWithKey("other".getBytes(StandardCharsets.UTF_8), der, spki));
        assertFalse(PluginSignature.verifyWithKey(canonical, der,
                PluginTestFixtures.spki(PluginTestFixtures.keyPair().getPublic())));
        assertFalse(PluginSignature.verifyWithKey(canonical, new byte[0], spki));
    }

    @Test public void nonP256PublicKeyIsRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair p384 = generator.generateKeyPair();
        JSONObject manifest = PluginTestFixtures.manifestJson(ID, 1, TARGET, p384);
        PluginTestFixtures.Built built = PluginTestFixtures.build(manifest,
                PluginTestFixtures.sampleDex("p384"), PluginTestFixtures.sampleAssets("p384"), p384, true);
        assertFalse(PluginSignature.verify(PluginTestFixtures.toPackage(built)));
    }
}
