package com.adhook.plugin;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Archive structure, hash and budget rejection tests. */
public class PluginZipTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final String ID = "com.example.cleaner";
    private static final String TARGET = "com.example.target";

    private PluginTestFixtures.Built signedPackage() throws Exception {
        return PluginTestFixtures.build(ID, 3, TARGET, PluginTestFixtures.keyPair(), true);
    }

    private List<PluginTestFixtures.ZipEntry> entriesOf(PluginTestFixtures.Built built) {
        List<PluginTestFixtures.ZipEntry> entries = new ArrayList<>();
        entries.add(new PluginTestFixtures.ZipEntry(PluginZip.MANIFEST_ENTRY, built.manifestBytes));
        entries.add(new PluginTestFixtures.ZipEntry(PluginZip.DEX_ENTRY, built.dex));
        for (Map.Entry<String, byte[]> asset : built.assets.entrySet()) {
            entries.add(new PluginTestFixtures.ZipEntry(asset.getKey(), asset.getValue()));
        }
        if (built.signatureBytes != null) {
            entries.add(new PluginTestFixtures.ZipEntry(PluginZip.SIGNATURE_ENTRY, built.signatureBytes));
        }
        return entries;
    }

    private File writeZip(byte[] zipBytes) throws Exception {
        File file = folder.newFile("plugin-" + System.nanoTime() + ".zip");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(zipBytes);
        }
        return file;
    }

    private static void assertRejected(File file) {
        assertThrows(PluginValidationException.class, () -> PluginZip.read(file));
    }

    @Test public void readsAValidSignedPackage() throws Exception {
        PluginTestFixtures.Built built = signedPackage();
        PluginPackage pkg = PluginZip.read(writeZip(built.zipBytes));
        assertEquals(ID, pkg.manifest.getId());
        assertArrayEquals(built.dex, pkg.dex);
        assertArrayEquals(built.manifestBytes, pkg.manifestBytes);
        assertEquals(1, pkg.assets.size());
        assertTrue(pkg.assets.containsKey("assets/rules.json"));
        assertArrayEquals(built.signatureBytes, pkg.signatureBytes);
    }

    @Test public void rejectsPathTraversalAndAbsoluteAndBackslashEntries() throws Exception {
        PluginTestFixtures.Built built = signedPackage();
        for (String badName : new String[]{"assets/../evil", "/abs/evil", "assets\\evil", "./assets/x",
                "assets//x"}) {
            List<PluginTestFixtures.ZipEntry> entries = entriesOf(built);
            entries.add(new PluginTestFixtures.ZipEntry(badName, "x".getBytes()));
            assertRejected(writeZip(PluginTestFixtures.zip(entries)));
        }
    }

    @Test public void rejectsDirectoryAndNonAsciiEntries() throws Exception {
        PluginTestFixtures.Built built = signedPackage();
        List<PluginTestFixtures.ZipEntry> directory = entriesOf(built);
        directory.add(new PluginTestFixtures.ZipEntry("assets/dir/", new byte[0], -1L, true));
        assertRejected(writeZip(PluginTestFixtures.zip(directory)));

        PluginTestFixtures.Built second = signedPackage();
        List<PluginTestFixtures.ZipEntry> nonAscii = entriesOf(second);
        nonAscii.add(new PluginTestFixtures.ZipEntry("assets/中文", "x".getBytes()));
        assertRejected(writeZip(PluginTestFixtures.zip(nonAscii)));
    }

    @Test public void rejectsDuplicateEntries() throws Exception {
        PluginTestFixtures.Built built = signedPackage();
        List<PluginTestFixtures.ZipEntry> entries = entriesOf(built);
        entries.add(new PluginTestFixtures.ZipEntry("assets/rules.json", "other".getBytes()));
        assertRejected(writeZip(PluginTestFixtures.zip(entries)));
    }

    @Test public void rejectsUndeclaredAndMissingFiles() throws Exception {
        PluginTestFixtures.Built built = signedPackage();
        List<PluginTestFixtures.ZipEntry> undeclared = entriesOf(built);
        undeclared.add(new PluginTestFixtures.ZipEntry("assets/extra.json", "{}".getBytes()));
        assertRejected(writeZip(PluginTestFixtures.zip(undeclared)));

        PluginTestFixtures.Built second = signedPackage();
        List<PluginTestFixtures.ZipEntry> missing = entriesOf(second);
        missing.removeIf(entry -> entry.name.startsWith("assets/"));
        assertRejected(writeZip(PluginTestFixtures.zip(missing)));
    }

    @Test public void rejectsPackageWithoutDex() throws Exception {
        PluginTestFixtures.Built built = signedPackage();
        List<PluginTestFixtures.ZipEntry> entries = entriesOf(built);
        entries.removeIf(entry -> entry.name.equals(PluginZip.DEX_ENTRY));
        assertRejected(writeZip(PluginTestFixtures.zip(entries)));
    }

    @Test public void rejectsHashMismatch() throws Exception {
        KeyPair keyPair = PluginTestFixtures.keyPair();
        JSONObject manifest = PluginTestFixtures.manifestJson(ID, 1, TARGET, keyPair);
        JSONObject files = new JSONObject();
        files.put(PluginZip.DEX_ENTRY, "b".repeat(64));
        manifest.put("files", files);
        byte[] manifestBytes = PluginTestFixtures.utf8(manifest.toString());
        List<PluginTestFixtures.ZipEntry> entries = new ArrayList<>();
        entries.add(new PluginTestFixtures.ZipEntry(PluginZip.MANIFEST_ENTRY, manifestBytes));
        entries.add(new PluginTestFixtures.ZipEntry(PluginZip.DEX_ENTRY, "dex".getBytes()));
        assertRejected(writeZip(PluginTestFixtures.zip(entries)));
    }

    @Test public void rejectsDeclaredButMissingFile() throws Exception {
        KeyPair keyPair = PluginTestFixtures.keyPair();
        JSONObject manifest = PluginTestFixtures.manifestJson(ID, 1, TARGET, keyPair);
        JSONObject files = new JSONObject();
        files.put(PluginZip.DEX_ENTRY, PluginTestFixtures.sha256("dex".getBytes()));
        files.put("assets/missing.json", "c".repeat(64));
        manifest.put("files", files);
        List<PluginTestFixtures.ZipEntry> entries = new ArrayList<>();
        entries.add(new PluginTestFixtures.ZipEntry(PluginZip.MANIFEST_ENTRY,
                PluginTestFixtures.utf8(manifest.toString())));
        entries.add(new PluginTestFixtures.ZipEntry(PluginZip.DEX_ENTRY, "dex".getBytes()));
        assertRejected(writeZip(PluginTestFixtures.zip(entries)));
    }

    @Test public void rejectsOversizedAssetsAndForgedUncompressedBudget() throws Exception {
        KeyPair keyPair = PluginTestFixtures.keyPair();
        byte[] bigAsset = new byte[PluginConstants.MAX_ASSET_BYTES + 1];
        JSONObject manifest = PluginTestFixtures.manifestJson(ID, 1, TARGET, keyPair);
        JSONObject files = new JSONObject();
        files.put(PluginZip.DEX_ENTRY, PluginTestFixtures.sha256("dex".getBytes()));
        files.put("assets/big.bin", PluginTestFixtures.sha256(bigAsset));
        manifest.put("files", files);
        List<PluginTestFixtures.ZipEntry> entries = new ArrayList<>();
        entries.add(new PluginTestFixtures.ZipEntry(PluginZip.MANIFEST_ENTRY,
                PluginTestFixtures.utf8(manifest.toString())));
        entries.add(new PluginTestFixtures.ZipEntry(PluginZip.DEX_ENTRY, "dex".getBytes()));
        entries.add(new PluginTestFixtures.ZipEntry("assets/big.bin", bigAsset));
        assertRejected(writeZip(PluginTestFixtures.zip(entries)));

        List<PluginTestFixtures.ZipEntry> forged = new ArrayList<>();
        forged.add(new PluginTestFixtures.ZipEntry(PluginZip.MANIFEST_ENTRY,
                PluginTestFixtures.utf8(manifest.toString())));
        forged.add(new PluginTestFixtures.ZipEntry(PluginZip.DEX_ENTRY, "dex".getBytes()));
        forged.add(new PluginTestFixtures.ZipEntry("assets/big.bin", new byte[0],
                PluginConstants.MAX_UNCOMPRESSED_BYTES + 1, false));
        assertRejected(writeZip(PluginTestFixtures.zip(forged)));
    }

    @Test public void rejectsTooManyEntriesAndOversizedZipFile() throws Exception {
        List<PluginTestFixtures.ZipEntry> entries = new ArrayList<>();
        for (int i = 0; i < PluginConstants.MAX_ZIP_ENTRIES + 4; i++) {
            entries.add(new PluginTestFixtures.ZipEntry("assets/a" + i, new byte[0]));
        }
        assertRejected(writeZip(PluginTestFixtures.zip(entries)));

        File oversized = folder.newFile("oversized.zip");
        try (FileOutputStream output = new FileOutputStream(oversized)) {
            output.write(new byte[PluginConstants.MAX_ZIP_BYTES + 1]);
        }
        assertRejected(oversized);
    }

    @Test public void rejectsMissingManifest() throws Exception {
        List<PluginTestFixtures.ZipEntry> entries = new ArrayList<>();
        entries.add(new PluginTestFixtures.ZipEntry(PluginZip.DEX_ENTRY, "dex".getBytes()));
        assertRejected(writeZip(PluginTestFixtures.zip(entries)));
    }

    @Test public void rejectsMalformedManifestJson() throws Exception {
        List<PluginTestFixtures.ZipEntry> entries = new ArrayList<>();
        entries.add(new PluginTestFixtures.ZipEntry(PluginZip.MANIFEST_ENTRY, "{not-json".getBytes()));
        entries.add(new PluginTestFixtures.ZipEntry(PluginZip.DEX_ENTRY, "dex".getBytes()));
        assertRejected(writeZip(PluginTestFixtures.zip(entries)));
    }

    @Test public void unsignedPackageIsReadableButNotSigned() throws Exception {
        PluginTestFixtures.Built built = PluginTestFixtures.build(ID, 1, TARGET, null, false);
        PluginPackage pkg = PluginZip.read(writeZip(built.zipBytes));
        assertNotNull(pkg);
        assertEquals(null, pkg.signatureBytes);
        assertTrue(!pkg.isSigned());
    }

    @Test public void rejectsDuplicateManifestKeysAndUtf8Bom() throws Exception {
        byte[] duplicateKeys = PluginTestFixtures.utf8(
                "{\"schema\":1,\"schema\":1,\"id\":\"com.example.cleaner\"}");
        List<PluginTestFixtures.ZipEntry> duplicateEntries = new ArrayList<>();
        duplicateEntries.add(new PluginTestFixtures.ZipEntry(PluginZip.MANIFEST_ENTRY, duplicateKeys));
        duplicateEntries.add(new PluginTestFixtures.ZipEntry(PluginZip.DEX_ENTRY, "dex".getBytes()));
        assertRejected(writeZip(PluginTestFixtures.zip(duplicateEntries)));

        PluginTestFixtures.Built built = signedPackage();
        byte[] bom = new byte[built.manifestBytes.length + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(built.manifestBytes, 0, bom, 3, built.manifestBytes.length);
        List<PluginTestFixtures.ZipEntry> bomEntries = entriesOf(built);
        bomEntries.removeIf(entry -> entry.name.equals(PluginZip.MANIFEST_ENTRY));
        bomEntries.add(new PluginTestFixtures.ZipEntry(PluginZip.MANIFEST_ENTRY, bom));
        assertRejected(writeZip(PluginTestFixtures.zip(bomEntries)));
    }

    @Test public void oversizedSignatureEntryIsRejected() throws Exception {
        PluginTestFixtures.Built built = signedPackage();
        List<PluginTestFixtures.ZipEntry> entries = entriesOf(built);
        entries.removeIf(entry -> entry.name.equals(PluginZip.SIGNATURE_ENTRY));
        entries.add(new PluginTestFixtures.ZipEntry(PluginZip.SIGNATURE_ENTRY,
                new byte[PluginConstants.MAX_SIGNATURE_BYTES + 1]));
        assertRejected(writeZip(PluginTestFixtures.zip(entries)));
    }
}
