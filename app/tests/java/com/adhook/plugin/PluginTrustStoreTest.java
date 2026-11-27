package com.adhook.plugin;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** TOFU publisher pinning regressions (Robolectric for the app files directory). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class PluginTrustStoreTest {

    private static final String ID = "com.example.cleaner";
    private static final String TARGET = "com.example.target";

    private File pluginsDirectory;
    private KeyPair publisher;
    private PluginManifest manifest;

    @Before public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        pluginsDirectory = new File(context.getFilesDir(), "plugins");
        PluginFiles.deleteRecursively(pluginsDirectory);
        publisher = PluginTestFixtures.keyPair();
        manifest = manifestFor(publisher);
    }

    private PluginManifest manifestFor(KeyPair keyPair) throws Exception {
        return PluginTestFixtures.build(ID, 1, TARGET, keyPair, true).manifest;
    }

    @Test public void firstUseNeedsExplicitConfirmationThenBecomesTrusted() {
        PluginTrustStore store = new PluginTrustStore(pluginsDirectory);
        PluginTrustStore.TrustCheck first = store.check(manifest);
        assertEquals(PluginTrustStore.TrustCheck.State.FIRST_USE, first.state);
        assertEquals(PluginTestFixtures.keyId(publisher.getPublic()), first.keyId);
        assertEquals(PluginTestFixtures.fingerprint(publisher.getPublic()), first.fingerprint);
        assertTrue(first.pinnedFingerprint.isEmpty());

        assertFalse(store.pin(manifest, false));
        assertEquals(PluginTrustStore.TrustCheck.State.FIRST_USE, store.check(manifest).state);

        assertTrue(store.pin(manifest, true));
        PluginTrustStore.TrustCheck trusted = store.check(manifest);
        assertEquals(PluginTrustStore.TrustCheck.State.TRUSTED, trusted.state);
        assertEquals(first.fingerprint, trusted.pinnedFingerprint);
        assertEquals(1, store.count());

        PluginTrustStore reloaded = new PluginTrustStore(pluginsDirectory);
        assertEquals(PluginTrustStore.TrustCheck.State.TRUSTED, reloaded.check(manifest).state);
        assertEquals(PluginTestFixtures.fingerprint(publisher.getPublic()),
                reloaded.pinnedFingerprints().get(ID));
    }

    @Test public void changedPublisherKeyIsNeverRepinned() throws Exception {
        PluginTrustStore store = new PluginTrustStore(pluginsDirectory);
        assertTrue(store.pin(manifest, true));

        KeyPair replacement = PluginTestFixtures.keyPair();
        PluginManifest changed = manifestFor(replacement);
        PluginTrustStore.TrustCheck check = store.check(changed);
        assertEquals(PluginTrustStore.TrustCheck.State.KEY_CHANGED, check.state);
        assertEquals(PluginTestFixtures.keyId(replacement.getPublic()), check.keyId);
        assertEquals(PluginTestFixtures.fingerprint(publisher.getPublic()), check.pinnedFingerprint);
        assertFalse(store.pin(changed, false));
        assertFalse(store.pin(changed, true));
        assertEquals(PluginTrustStore.TrustCheck.State.KEY_CHANGED, store.check(changed).state);
    }

    @Test public void revokeReturnsToFirstUseAndPersists() {
        PluginTrustStore store = new PluginTrustStore(pluginsDirectory);
        assertTrue(store.pin(manifest, true));
        assertTrue(store.revoke(ID));
        assertEquals(PluginTrustStore.TrustCheck.State.FIRST_USE, store.check(manifest).state);

        PluginTrustStore reloaded = new PluginTrustStore(pluginsDirectory);
        assertEquals(PluginTrustStore.TrustCheck.State.FIRST_USE, reloaded.check(manifest).state);
        assertEquals(0, reloaded.count());
    }

    @Test public void corruptTrustFileIsPreservedAndFreezesWrites() throws Exception {
        PluginFiles.ensureDirectory(pluginsDirectory);
        File trust = new File(pluginsDirectory, "trust.json");
        try (FileOutputStream output = new FileOutputStream(trust)) {
            output.write("{corrupt".getBytes(StandardCharsets.UTF_8));
        }
        PluginTrustStore store = new PluginTrustStore(pluginsDirectory);
        assertFalse(store.isHealthy());
        assertEquals(PluginTrustStore.TrustCheck.State.FIRST_USE, store.check(manifest).state);
        assertFalse(store.pin(manifest, true));
        assertFalse(store.revoke(ID));
        File[] corrupt = pluginsDirectory.listFiles((dir, name) -> name.startsWith("trust.json.corrupt"));
        assertTrue(corrupt != null && corrupt.length == 1);
    }

    @Test public void clearAllResetsUnhealthyStoreForExplicitRecovery() throws Exception {
        PluginTrustStore store = new PluginTrustStore(pluginsDirectory);
        assertTrue(store.pin(manifest, true));

        PluginFiles.writeAtomically(new File(pluginsDirectory, "trust.json"), "not json");
        PluginTrustStore broken = new PluginTrustStore(pluginsDirectory);
        assertFalse(broken.isHealthy());
        assertTrue(broken.clearAll());
        assertTrue(broken.isHealthy());
        assertEquals(PluginTrustStore.TrustCheck.State.FIRST_USE, broken.check(manifest).state);
    }

    @Test public void unsignedManifestCannotBePinned() {
        try {
            PluginManifest unsigned = PluginTestFixtures.build(ID, 1, TARGET, null, false).manifest;
            PluginTrustStore store = new PluginTrustStore(pluginsDirectory);
            assertEquals(PluginTrustStore.TrustCheck.State.FIRST_USE, store.check(unsigned).state);
            assertFalse(store.pin(unsigned, true));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test public void trustStateForUnknownPluginIdIsFirstUse() {
        PluginTrustStore store = new PluginTrustStore(pluginsDirectory);
        assertTrue(store.pin(manifest, true));
        PluginTrustStore.TrustCheck other = store.check(null);
        assertEquals(PluginTrustStore.TrustCheck.State.FIRST_USE, other.state);
        assertEquals(1, store.pinnedFingerprints().size());
    }
}
