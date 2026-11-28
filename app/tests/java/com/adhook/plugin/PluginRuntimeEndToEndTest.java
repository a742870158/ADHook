package com.adhook.plugin;

import android.content.Context;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Cross-language contract test: the fixture is produced by tools/plugin_package.py with the Java
 * key tool, and must pass the production Java verifier/installer unchanged.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class PluginRuntimeEndToEndTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Context context;

    @Before public void setup() throws Exception {
        PluginStore.resetForTests();
        PluginTrustStore.resetForTests();
        PluginSourceStore.resetForTests();
        Context app = RuntimeEnvironment.getApplication();
        File files = temporary.newFolder("files");
        context = new android.content.ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
            @Override public File getDataDir() { return temporary.getRoot(); }
            @Override public Context createDeviceProtectedStorageContext() { return this; }
        };
    }

    @After public void teardown() {
        PluginStore.resetForTests();
        PluginTrustStore.resetForTests();
        PluginSourceStore.resetForTests();
    }

    private static byte[] fixture() throws Exception {
        try (InputStream input = PluginRuntimeEndToEndTest.class.getResourceAsStream(
                "/plugins/sample-plugin.zip")) {
            assertNotNull("plugin fixture missing", input);
            return input.readAllBytes();
        }
    }

    private File writeFixture(byte[] bytes) throws Exception {
        File file = temporary.newFile("plugin-" + System.nanoTime() + ".zip");
        Files.write(file.toPath(), bytes);
        return file;
    }

    @Test public void pythonPackagedSignatureInstallsAndPublishesRoute() throws Exception {
        PluginInstaller installer = new PluginInstaller(context, 999_999_999);
        PluginPreflight preflight = installer.preflight(fixture(), null, false);
        assertTrue(preflight.message, preflight.isOk());
        assertEquals(PluginTrustStore.TrustCheck.State.FIRST_USE, preflight.trust.state);

        InstallError installed = installer.install(preflight, writeFixture(fixture()), false, true, "test");
        assertEquals(InstallError.OK, installed);
        InstalledPlugin record = PluginStore.get(context).find("com.adhook.plugin.sample");
        assertNotNull(record);
        assertEquals(10_000, record.getVersionCode());
        PluginManifest manifest = PluginManifest.parse(record.manifestObject());
        assertEquals(PluginTrustStore.TrustCheck.State.TRUSTED, PluginTrustStore.get(context).check(manifest).state);

        String tableJson = PluginRuntimeTable.build(List.of(record), 7L);
        assertNotNull(tableJson);
        PluginRuntimeTable table = PluginRuntimeTable.parse(tableJson);
        assertNotNull(table);
        PluginRuntimeTable.Route route = table.routes("com.example.host").get(0);
        assertEquals("com.adhook.plugin.sample", route.pluginId);
        assertEquals("com.example.SampleHook", route.entry);
        assertEquals(1, route.features.size());
        assertTrue(route.dexName.endsWith(".dex"));
    }

    @Test public void modifiedDexIsRejectedBeforeInstall() throws Exception {
        byte[] tampered = rewriteEntry(fixture(), "classes.dex", data -> {
            assertTrue(data.length > 16);
            data[data.length / 2] ^= 0x01;
        });
        PluginInstaller installer = new PluginInstaller(context, 999_999_999);
        PluginPreflight preflight = installer.preflight(tampered, null, false);
        assertFalse(preflight.isOk());
        assertEquals(0, PluginStore.get(context).count());
    }

    @Test public void modifiedSignatureIsRejectedBeforeInstall() throws Exception {
        byte[] tampered = rewriteEntry(fixture(), "META-INF/plugin.sig", data -> {
            String text = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(text);
            String signature = json.getString("signature");
            char replacement = signature.charAt(10) == 'A' ? 'B' : 'A';
            json.put("signature", signature.substring(0, 10) + replacement + signature.substring(11));
            byte[] rewritten = json.toString().getBytes(StandardCharsets.UTF_8);
            System.arraycopy(rewritten, 0, data, 0, Math.min(rewritten.length, data.length));
        });
        PluginInstaller installer = new PluginInstaller(context, 999_999_999);
        PluginPreflight preflight = installer.preflight(tampered, null, false);
        assertFalse(preflight.isOk());
        assertEquals(0, PluginStore.get(context).count());
    }

    @FunctionalInterface
    private interface EntryMutation {
        void apply(byte[] data) throws Exception;
    }

    private static byte[] rewriteEntry(byte[] zip, String entryName, EntryMutation mutation) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip));
             ZipOutputStream out = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                byte[] data = input.readAllBytes();
                if (entryName.equals(entry.getName())) mutation.apply(data);
                out.putNextEntry(new ZipEntry(entry.getName()));
                out.write(data);
                out.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
