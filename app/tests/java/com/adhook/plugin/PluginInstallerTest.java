package com.adhook.plugin;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** End-to-end installer transaction tests (Robolectric filesystem, no framework/Xposed). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class PluginInstallerTest {

    private static final String ID = "com.example.cleaner";
    private static final String OTHER_ID = "com.example.other";
    private static final String TARGET = "com.example.target";
    private static final String OTHER_TARGET = "com.example.othertarget";

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private Context context;
    private File pluginsDirectory;
    private PluginStore store;
    private PluginTrustStore trustStore;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pluginsDirectory = new File(context.getFilesDir(), "plugins");
        PluginFiles.deleteRecursively(pluginsDirectory);
        store = new PluginStore(pluginsDirectory);
        trustStore = new PluginTrustStore(pluginsDirectory);
    }

    private PluginInstaller installer(int baseVersionCode) {
        return new PluginInstaller(context, baseVersionCode, store, trustStore);
    }

    private File write(PluginTestFixtures.Built built, String name) throws Exception {
        return built.write(folder.newFolder("zips-" + System.nanoTime()), name);
    }

    private PluginTestFixtures.Built signed(String id, int versionCode, String targetPackage,
                                            KeyPair keyPair) throws Exception {
        return PluginTestFixtures.build(id, versionCode, targetPackage, keyPair, true);
    }

    @Test public void installsSignedPackageWithPinnedPublisherAndFiles() throws Exception {
        KeyPair keyPair = PluginTestFixtures.keyPair();
        PluginTestFixtures.Built built = signed(ID, 1, TARGET, keyPair);
        File zip = write(built, "plugin.zip");
        PluginInstaller installer = installer(1000);

        PluginPreflight preflight = installer.preflight(null, zip, false);
        assertTrue(preflight.message, preflight.isOk());
        assertNotNull(preflight.manifest);
        assertEquals(0, preflight.installedVersionCode);
        assertEquals(PluginTrustStore.TrustCheck.State.FIRST_USE, preflight.trust.state);

        assertEquals(InstallError.OK, installer.install(preflight, zip, false, true, "source-1"));
        InstalledPlugin installed = store.find(ID);
        assertNotNull(installed);
        assertTrue(installed.isEnabled());
        assertEquals("source-1", installed.getSourceId());
        assertEquals(PluginTestFixtures.sha256(built.dex), installed.getDexSha256());
        assertEquals(PluginTestFixtures.sha256(built.zipBytes), installed.getZipSha256());
        assertEquals(PluginTestFixtures.fingerprint(keyPair.getPublic()),
                installed.getPublisherFingerprint());
        assertEquals(PluginTrustStore.TrustCheck.State.TRUSTED, trustStore.check(built.manifest).state);

        File versionDirectory = new File(new File(pluginsDirectory, ID), "1");
        assertArrayEquals(built.dex, Files.readAllBytes(
                new File(versionDirectory, PluginZip.DEX_ENTRY).toPath()));
        assertArrayEquals(built.assets.get("assets/rules.json"), Files.readAllBytes(
                new File(versionDirectory, "assets/rules.json").toPath()));
    }

    @Test public void preflightFromBytesCanBeInstalledFromFile() throws Exception {
        PluginTestFixtures.Built built = signed(ID, 1, TARGET, PluginTestFixtures.keyPair());
        File zip = write(built, "plugin.zip");
        PluginInstaller installer = installer(1000);
        PluginPreflight preflight = installer.preflight(built.zipBytes, null, false);
        assertTrue(preflight.message, preflight.isOk());
        assertEquals(PluginTestFixtures.sha256(built.zipBytes), preflight.zipSha256);
        assertEquals(InstallError.OK, installer.install(preflight, zip, false, true, ""));
    }

    @Test public void firstUseWithoutConfirmationIsRejected() throws Exception {
        PluginTestFixtures.Built built = signed(ID, 1, TARGET, PluginTestFixtures.keyPair());
        File zip = write(built, "plugin.zip");
        PluginInstaller installer = installer(1000);
        PluginPreflight preflight = installer.preflight(null, zip, false);
        assertTrue(preflight.isOk());
        assertEquals(InstallError.UNTRUSTED_PUBLISHER,
                installer.install(preflight, zip, false, false, ""));
        assertNull(store.find(ID));
        assertFalse(new File(pluginsDirectory, ID).exists());
    }

    @Test public void downgradeAndDifferentHashAtSameVersionAreRejected() throws Exception {
        KeyPair keyPair = PluginTestFixtures.keyPair();
        PluginInstaller installer = installer(1000);
        PluginTestFixtures.Built version2 = signed(ID, 2, TARGET, keyPair);
        File zip2 = write(version2, "v2.zip");
        assertTrue(installer.preflight(null, zip2, false).isOk());
        assertEquals(InstallError.OK, installer.install(
                installer.preflight(null, zip2, false), zip2, false, true, ""));

        PluginTestFixtures.Built version1 = signed(ID, 1, TARGET, keyPair);
        File zip1 = write(version1, "v1.zip");
        PluginPreflight downgrade = installer.preflight(null, zip1, false);
        assertFalse(downgrade.isOk());
        assertEquals(InstallError.DOWNGRADE, downgrade.error);
        assertEquals(2, downgrade.installedVersionCode);
        assertEquals(InstallError.DOWNGRADE, installer.install(downgrade, zip1, false, true, ""));

        JSONObject manifest = PluginTestFixtures.manifestJson(ID, 2, TARGET, keyPair);
        PluginTestFixtures.Built sameVersionNewDex = PluginTestFixtures.build(manifest,
                PluginTestFixtures.sampleDex("different"), PluginTestFixtures.sampleAssets("x"),
                keyPair, true);
        File zip3 = write(sameVersionNewDex, "v2b.zip");
        PluginPreflight sameVersion = installer.preflight(null, zip3, false);
        assertEquals(InstallError.SAME_VERSION_DIFFERENT_HASH, sameVersion.error);
    }

    @Test public void identicalArchiveIsAlreadyInstalledAndIdempotent() throws Exception {
        PluginTestFixtures.Built built = signed(ID, 1, TARGET, PluginTestFixtures.keyPair());
        File zip = write(built, "plugin.zip");
        PluginInstaller installer = installer(1000);
        assertEquals(InstallError.OK, installer.install(
                installer.preflight(null, zip, false), zip, false, true, ""));
        PluginPreflight again = installer.preflight(null, zip, false);
        assertTrue(again.alreadyInstalled);
        assertTrue(again.isOk());
        assertEquals(InstallError.OK, installer.install(again, zip, false, false, ""));
    }

    @Test public void targetOwnershipConflictIsReported() throws Exception {
        PluginInstaller installer = installer(1000);
        PluginTestFixtures.Built first = signed(ID, 1, TARGET, PluginTestFixtures.keyPair());
        File firstZip = write(first, "first.zip");
        assertEquals(InstallError.OK, installer.install(
                installer.preflight(null, firstZip, false), firstZip, false, true, ""));

        PluginTestFixtures.Built second = signed(OTHER_ID, 1, TARGET, PluginTestFixtures.keyPair());
        File secondZip = write(second, "second.zip");
        PluginPreflight conflict = installer.preflight(null, secondZip, false);
        assertFalse(conflict.isOk());
        assertEquals(InstallError.CONFLICT, conflict.conflict);
        assertEquals(InstallError.CONFLICT, installer.install(conflict, secondZip, false, true, ""));
        assertNull(store.find(OTHER_ID));
    }

    @Test public void changedPublisherKeyIsBlocked() throws Exception {
        KeyPair original = PluginTestFixtures.keyPair();
        PluginInstaller installer = installer(1000);
        PluginTestFixtures.Built version1 = signed(ID, 1, TARGET, original);
        File zip1 = write(version1, "v1.zip");
        assertEquals(InstallError.OK, installer.install(
                installer.preflight(null, zip1, false), zip1, false, true, ""));

        KeyPair replacement = PluginTestFixtures.keyPair();
        PluginTestFixtures.Built version2 = signed(ID, 2, TARGET, replacement);
        File zip2 = write(version2, "v2.zip");
        PluginPreflight changed = installer.preflight(null, zip2, false);
        assertEquals(InstallError.PUBLISHER_CHANGED, changed.error);
        assertEquals(InstallError.PUBLISHER_CHANGED,
                installer.install(changed, zip2, false, true, ""));
        assertEquals(1, store.find(ID).getVersionCode());
    }

    @Test public void unsignedPackageRequiresExplicitDeveloperMode() throws Exception {
        PluginTestFixtures.Built built = PluginTestFixtures.build(ID, 1, TARGET, null, false);
        File zip = write(built, "unsigned.zip");
        PluginInstaller installer = installer(1000);
        PluginPreflight rejected = installer.preflight(null, zip, false);
        assertEquals(InstallError.UNSIGNED, rejected.error);
        assertEquals(InstallError.UNSIGNED, installer.install(rejected, zip, false, false, ""));

        PluginPreflight allowed = installer.preflight(null, zip, true);
        assertTrue(allowed.message, allowed.isOk());
        assertEquals(InstallError.OK, installer.install(allowed, zip, true, false, "dev"));
        InstalledPlugin installed = store.find(ID);
        assertNotNull(installed);
        assertTrue(installed.getPublisherKeyId().isEmpty());
        assertTrue(installed.getPublisherFingerprint().isEmpty());
        assertEquals(0, trustStore.count());
    }

    @Test public void abiAndBaseVersionAreCheckedBeforeInstall() throws Exception {
        KeyPair keyPair = PluginTestFixtures.keyPair();
        JSONObject futureAbi = PluginTestFixtures.manifestJson(ID, 1, TARGET, keyPair);
        futureAbi.put("abi", PluginConstants.PLUGIN_ABI + 1);
        PluginTestFixtures.Built abiPackage = PluginTestFixtures.build(futureAbi,
                PluginTestFixtures.sampleDex("abi"), PluginTestFixtures.sampleAssets("abi"),
                keyPair, true);
        File abiZip = write(abiPackage, "abi.zip");
        PluginInstaller installer = installer(1000);
        assertEquals(InstallError.ABI_MISMATCH, installer.preflight(null, abiZip, false).error);

        JSONObject newerBase = PluginTestFixtures.manifestJson(ID, 1, TARGET, keyPair);
        newerBase.put("minBaseVersionCode", 5000);
        PluginTestFixtures.Built basePackage = PluginTestFixtures.build(newerBase,
                PluginTestFixtures.sampleDex("base"), PluginTestFixtures.sampleAssets("base"),
                keyPair, true);
        File baseZip = write(basePackage, "base.zip");
        assertEquals(InstallError.BASE_TOO_OLD,
                installer(1000).preflight(null, baseZip, false).error);
    }

    @Test public void failedStoreCommitKeepsThePreviousVersion() throws Exception {
        FailingStore failingStore = new FailingStore(pluginsDirectory);
        KeyPair keyPair = PluginTestFixtures.keyPair();
        PluginInstaller installer = new PluginInstaller(context, 1000, failingStore, trustStore);

        PluginTestFixtures.Built version1 = signed(ID, 1, TARGET, keyPair);
        File zip1 = write(version1, "v1.zip");
        assertEquals(InstallError.OK, installer.install(
                installer.preflight(null, zip1, false), zip1, false, true, ""));

        PluginTestFixtures.Built version2 = signed(ID, 2, TARGET, keyPair);
        File zip2 = write(version2, "v2.zip");
        failingStore.failUpsert = true;
        PluginPreflight preflight = installer.preflight(null, zip2, false);
        assertTrue(preflight.message, preflight.isOk());
        assertEquals(InstallError.STORAGE_FAILURE,
                installer.install(preflight, zip2, false, true, ""));

        assertEquals(1, failingStore.find(ID).getVersionCode());
        File version1Directory = new File(new File(pluginsDirectory, ID), "1");
        assertArrayEquals(version1.dex, Files.readAllBytes(
                new File(version1Directory, PluginZip.DEX_ENTRY).toPath()));
        assertFalse(new File(new File(pluginsDirectory, ID), "2").exists());
        File[] staging = new File(pluginsDirectory, ID)
                .listFiles((dir, name) -> name.startsWith(".staging-"));
        assertTrue(staging == null || staging.length == 0);
    }

    @Test public void uninstallRemovesFilesButKeepsPublisherUntilForgotten() throws Exception {
        KeyPair keyPair = PluginTestFixtures.keyPair();
        PluginTestFixtures.Built built = signed(ID, 1, TARGET, keyPair);
        File zip = write(built, "plugin.zip");
        PluginInstaller installer = installer(1000);
        assertEquals(InstallError.OK, installer.install(
                installer.preflight(null, zip, false), zip, false, true, ""));
        assertEquals(1, trustStore.count());

        assertTrue(installer.uninstall(ID));
        assertNull(store.find(ID));
        assertFalse(new File(pluginsDirectory, ID).exists());
        assertEquals(1, trustStore.count());

        assertTrue(installer.forgetPublisher(ID));
        assertEquals(0, trustStore.count());
        assertFalse(installer.uninstall("../escape"));
        assertFalse(PluginInstaller.deleteInstalledFiles(context, "../escape"));
    }

    @Test public void disableTogglePersistsThroughStore() throws Exception {
        PluginTestFixtures.Built built = signed(ID, 1, TARGET, PluginTestFixtures.keyPair());
        File zip = write(built, "plugin.zip");
        PluginInstaller installer = installer(1000);
        assertEquals(InstallError.OK, installer.install(
                installer.preflight(null, zip, false), zip, false, true, ""));
        assertTrue(installer.setEnabled(ID, false));
        assertFalse(store.find(ID).isEnabled());
        assertTrue(new PluginStore(pluginsDirectory).find(ID) != null);
        assertFalse(new PluginStore(pluginsDirectory).find(ID).isEnabled());
    }

    /** Test seam: force the catalogue commit to fail after files were promoted. */
    private static final class FailingStore extends PluginStore {
        boolean failUpsert;

        FailingStore(File pluginsDirectory) {
            super(pluginsDirectory);
        }

        @Override public synchronized boolean upsert(InstalledPlugin plugin) {
            return !failUpsert && super.upsert(plugin);
        }
    }
}
