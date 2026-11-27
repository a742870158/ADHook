package com.adhook.plugin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Installed catalogue atomic persistence, corruption isolation and limits. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class PluginStoreTest {

    private File pluginsDirectory;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pluginsDirectory = new File(context.getFilesDir(), "plugins");
        PluginFiles.deleteRecursively(pluginsDirectory);
    }

    private PluginStore store() {
        return new PluginStore(pluginsDirectory);
    }

    @Test public void upsertListFindToggleAndRemovePersist() {
        PluginStore store = store();
        InstalledPlugin first = PluginTestFixtures.installedUnsigned("com.example.aa", 1,
                "com.example.target1");
        InstalledPlugin second = PluginTestFixtures.installedUnsigned("com.example.bb", 2,
                "com.example.target2");
        assertTrue(store.upsert(first));
        assertTrue(store.upsert(second));
        assertEquals(2, store.count());
        assertEquals(2, store.list().size());
        assertNotNull(store.find("com.example.aa"));

        assertTrue(store.setEnabled("com.example.aa", false));
        assertFalse(store.find("com.example.aa").isEnabled());
        assertTrue(store.setEnabled("com.example.aa", false)); // idempotent
        assertTrue(store.remove("com.example.bb"));
        assertNull(store.find("com.example.bb"));

        PluginStore reloaded = store();
        assertTrue(reloaded.isHealthy());
        assertEquals(1, reloaded.count());
        assertFalse(reloaded.find("com.example.aa").isEnabled());
        assertTrue(reloaded.find("com.example.aa").getTargets().get(0).matches("com.example.target1"));
    }

    @Test public void listIsDetachedFromStoreState() {
        PluginStore store = store();
        InstalledPlugin plugin = PluginTestFixtures.installedUnsigned("com.example.aa", 1,
                "com.example.target1");
        assertTrue(store.upsert(plugin));
        List<InstalledPlugin> snapshot = store.list();
        snapshot.clear();
        assertEquals(1, store.count());
    }

    @Test public void rejectsBeyondMaximumPluginCount() {
        PluginStore store = store();
        for (int i = 0; i < PluginConstants.MAX_PLUGINS; i++) {
            assertTrue("install " + i, store.upsert(PluginTestFixtures.installedUnsigned(
                    "com.example.p" + i, 1, "com.example.target" + i)));
        }
        assertEquals(PluginConstants.MAX_PLUGINS, store.count());
        assertFalse(store.upsert(PluginTestFixtures.installedUnsigned("com.example.overflow", 1,
                "com.example.overflow")));
        assertEquals(PluginConstants.MAX_PLUGINS, store.count());
        // Replacing an existing id is allowed at the limit.
        assertTrue(store.upsert(PluginTestFixtures.installedUnsigned("com.example.p0", 2,
                "com.example.target0")));
    }

    @Test public void corruptStoreIsPreservedAndFrozen() throws Exception {
        PluginFiles.ensureDirectory(pluginsDirectory);
        PluginFiles.writeAtomically(new File(pluginsDirectory, "installed.json"),
                "{\"schema\":1,\"plugins\":[{\"id\":\"broken\"}]}");
        PluginStore store = store();
        assertFalse(store.isHealthy());
        assertEquals(0, store.count());
        assertFalse(store.upsert(PluginTestFixtures.installedUnsigned("com.example.aa", 1,
                "com.example.target1")));
        assertFalse(store.remove("com.example.aa"));
        File[] preserved = pluginsDirectory.listFiles(
                (dir, name) -> name.startsWith("installed.json.corrupt"));
        assertTrue(preserved != null && preserved.length == 1);
        assertEquals("installed.json", preserved[0].getName().substring(0, "installed.json".length()));
    }

    @Test public void missingMainFileWithBackupIsTreatedAsUnrecoverable() throws Exception {
        PluginStore store = store();
        assertTrue(store.upsert(PluginTestFixtures.installedUnsigned("com.example.aa", 1,
                "com.example.target1")));
        assertTrue(new File(pluginsDirectory, "installed.json.bak").isFile()
                || new File(pluginsDirectory, "installed.json").isFile());
        // Simulate a crash between "old -> .bak" and "temp -> installed.json".
        PluginFiles.writeAtomically(new File(pluginsDirectory, "installed.json.bak"),
                new JSONObject().put("schema", 1).put("plugins", new JSONArray()).toString());
        java.nio.file.Files.delete(new File(pluginsDirectory, "installed.json").toPath());
        PluginStore reloaded = store();
        assertFalse(reloaded.isHealthy());
        assertTrue(reloaded.getLoadWarning() != null);
        assertFalse(reloaded.upsert(PluginTestFixtures.installedUnsigned("com.example.aa", 1,
                "com.example.target1")));
    }

    @Test public void duplicateIdsIncludingInvalidEntriesAreRejectedAsCorruption() throws Exception {
        PluginFiles.ensureDirectory(pluginsDirectory);
        JSONObject root = new JSONObject();
        root.put("schema", 1);
        JSONArray array = new JSONArray();
        InstalledPlugin plugin = PluginTestFixtures.installedUnsigned("com.example.aa", 1,
                "com.example.target1");
        array.put(plugin.toJson());
        array.put(plugin.toJson());
        root.put("plugins", array);
        PluginFiles.writeAtomically(new File(pluginsDirectory, "installed.json"),
                root.toString().getBytes(StandardCharsets.UTF_8));
        PluginStore store = store();
        assertFalse(store.isHealthy());
        assertEquals(0, store.count());
    }

    @Test public void installedPluginJsonRoundTripKeepsDerivedFields() {
        InstalledPlugin original = PluginTestFixtures.installed("com.example.aa", 4,
                "com.example.target1");
        InstalledPlugin parsed = InstalledPlugin.fromJson(original.toJson());
        assertNotNull(parsed);
        assertEquals(original.getId(), parsed.getId());
        assertEquals(original.getVersionCode(), parsed.getVersionCode());
        assertEquals(original.getDexSha256(), parsed.getDexSha256());
        assertEquals(original.getMinBaseVersionCode(), parsed.getMinBaseVersionCode());
        assertEquals(original.getZipSha256(), parsed.getZipSha256());
        assertEquals(original.getPublisherFingerprint(), parsed.getPublisherFingerprint());
        assertEquals(original.getManifestJson(), parsed.getManifestJson());
        assertEquals(original.getTargets().get(0).getPackageName(),
                parsed.getTargets().get(0).getPackageName());
        assertNotNull(parsed.manifestObject().optString("id", null));
    }
}
