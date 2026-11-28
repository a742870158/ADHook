package com.adhook.config.security;

import android.content.Context;
import android.content.ContextWrapper;
import com.adhook.config.ConfigManager;
import com.adhook.config.model.AppConfig;
import com.adhook.config.model.CustomRule;
import org.json.JSONArray;
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
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = android.app.Application.class)
public class ConfigImportSafetyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String PKG = "com.import.host";
    private ConfigManager manager;
    private Context context;
    private File files;

    @Before public void setup() throws Exception {
        reset();
        files = temporary.newFolder("files");
        context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
            @Override public File getDataDir() { return temporary.getRoot(); }
            @Override public Context createDeviceProtectedStorageContext() { return this; }
        };
        manager = ConfigManager.getInstance(context);
        Field target = ConfigManager.class.getDeclaredField("configFile"); target.setAccessible(true);
        assertEquals(new File(files, "adhook_config.json"), target.get(manager));
        manager.getAppConfig(PKG).setFeatureEnabled("existing", false);
        assertTrue(manager.saveSynchronously());
    }

    @After public void teardown() throws Exception { stop(); reset(); }
    private static void reset() throws Exception {
        Field field = ConfigManager.class.getDeclaredField("sInstance"); field.setAccessible(true); field.set(null, null);
    }
    private ExecutorService executor() throws Exception {
        Field field = ConfigManager.class.getDeclaredField("ioExecutor"); field.setAccessible(true);
        return (ExecutorService) field.get(manager);
    }
    private void stop() throws Exception {
        executor().shutdown(); assertTrue(executor().awaitTermination(10, TimeUnit.SECONDS));
    }
    private byte[] disk() throws Exception { return Files.readAllBytes(new File(files, "adhook_config.json").toPath()); }
    private JSONObject rule(String id) throws Exception {
        return new JSONObject().put("id", id).put("type", "text").put("targetValue", "广告")
                .put("action", "collapse").put("source", CustomRule.SOURCE_PICKER);
    }
    private JSONObject app() throws Exception {
        return new JSONObject().put("packageName", PKG).put("nativeFeatures", new JSONObject().put("imported", true))
                .put("customRules", new JSONArray().put(rule("imported-rule")));
    }
    private String input(int format) throws Exception {
        if (format == 0) return new JSONObject().put("apps", new JSONArray().put(new JSONObject()
                .put("id", PKG).put("rules", new JSONArray().put(rule("imported-rule"))))).toString();
        if (format == 1) return new JSONObject().put("type", "adhook_app_rules").put("package", PKG).put("data", app()).toString();
        return new JSONObject().put("apps", new JSONObject().put(PKG, app()))
                .put("global_sdk", new JSONObject().put("rule_toast_enabled", true)).toString();
    }
    private void assertUnchanged(byte[] before) throws Exception {
        assertArrayEquals(before, disk());
        AppConfig config = manager.getAppConfigSnapshot(PKG);
        assertFalse(config.isFeatureEnabled("existing", true));
        assertFalse(config.getNativeFeatures().containsKey("imported"));
        assertTrue(config.getCustomRules().isEmpty());
    }
    private void assertCommitted(int format) throws Exception {
        String result = manager.importJson(input(format));
        assertTrue(result.contains("已保存"));
        assertTrue(result.contains("独立状态"));
        assertEquals("imported-rule", manager.getAppConfigSnapshot(PKG).getCustomRules().get(0).getId());
        assertTrue(manager.getAppConfigSnapshot(PKG).isHookEnabled());
        stop(); reset(); manager = ConfigManager.getInstance(context);
        assertEquals("imported-rule", manager.getAppConfigSnapshot(PKG).getCustomRules().get(0).getId());
        assertEquals(format == 2, manager.getGlobalConfigSnapshot().isRuleToastEnabled());
    }
    @Test public void subscriptionImportIsDurableBeforeSuccess() throws Exception { assertCommitted(0); }
    @Test public void singleAppImportIsDurableBeforeSuccess() throws Exception { assertCommitted(1); }
    @Test public void fullImportIsDurableBeforeSuccess() throws Exception { assertCommitted(2); }

    @Test public void allFormatsRejectBrokenConfigWithoutMemoryChanges() throws Exception {
        byte[] before = disk();
        Field broken = ConfigManager.class.getDeclaredField("configBroken"); broken.setAccessible(true); broken.setBoolean(manager, true);
        for (int format = 0; format < 3; format++) {
            final String json = input(format);
            assertThrows(IllegalStateException.class, () -> manager.importJson(json));
            assertUnchanged(before);
        }
        assertTrue(manager.isConfigBroken());
    }
    @Test public void actualCorruptLoadKeepsBackupAndRejectsEveryImportFormat() throws Exception {
        File config = new File(files, "adhook_config.json");
        String corrupted = "{invalid configuration";
        Files.write(config.toPath(), corrupted.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(manager.load());
        File backup = new File(files, "adhook_config.json.bak");
        assertTrue(backup.isFile());
        for (int format = 0; format < 3; format++) {
            final String json = input(format);
            assertThrows(IllegalStateException.class, () -> manager.importJson(json));
            assertFalse(config.exists());
            assertEquals(corrupted, new String(Files.readAllBytes(backup.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(manager.getAppConfigSnapshot(PKG).getCustomRules().isEmpty());
        }
        assertTrue(manager.isConfigBroken());
    }
    @Test public void corruptBackupKeepsMissingConfigProtectedAcrossRestart() throws Exception {
        File config = new File(files, "adhook_config.json");
        Files.write(config.toPath(), "{broken after restart".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(manager.load());
        stop(); reset(); manager = ConfigManager.getInstance(context);
        assertTrue(manager.isConfigBroken());
        assertFalse(manager.save());
        assertFalse(manager.setInspectorEnabled(PKG, true));
        for (int format = 0; format < 3; format++) {
            String json = input(format);
            assertThrows(IllegalStateException.class, () -> manager.importJson(json));
        }
        assertFalse(config.exists());
        assertEquals("{broken after restart", new String(Files.readAllBytes(
                new File(files, "adhook_config.json.bak").toPath()), java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void anotherCorruptLoadNeverReplacesEarlierRecoveryMaterial() throws Exception {
        File backup = new File(files, "adhook_config.json.bak");
        Files.write(backup.toPath(), "earlier recovery material".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(new File(files, "adhook_config.json").toPath(), "{new broken config".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(manager.load());
        assertEquals("earlier recovery material", new String(Files.readAllBytes(backup.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        File[] additional = files.listFiles((dir, name) -> name.startsWith("adhook_config.json.bak."));
        assertNotNull(additional);
        assertEquals(1, additional.length);
        assertEquals("{new broken config", new String(Files.readAllBytes(additional[0].toPath()), java.nio.charset.StandardCharsets.UTF_8));
        stop(); reset(); manager = ConfigManager.getInstance(context);
        assertTrue(manager.isConfigBroken());
    }
    @Test public void allFormatsRejectQueueShutdownWithoutMemoryChanges() throws Exception {
        byte[] before = disk(); stop();
        for (int format = 0; format < 3; format++) {
            final String json = input(format);
            assertThrows(IllegalStateException.class, () -> manager.importJson(json));
            assertUnchanged(before);
        }
    }
    @Test public void allFormatsKeepOldFileAndMemoryWhenTemporaryWriteFails() throws Exception {
        byte[] before = disk();
        assertTrue(new File(files, "adhook_config.json.tmp").mkdir());
        for (int format = 0; format < 3; format++) {
            final String json = input(format);
            assertThrows(IllegalStateException.class, () -> manager.importJson(json));
            assertUnchanged(before);
        }
    }
    @Test public void invalidLaterSubscriptionAppDoesNotPartiallyImportEarlierApp() throws Exception {
        byte[] before = disk();
        JSONObject good = new JSONObject().put("id", PKG).put("rules", new JSONArray().put(rule("good")));
        String json = new JSONObject().put("apps", new JSONArray().put(good).put(new JSONObject().put("id", "bad/package"))).toString();
        assertThrows(IllegalArgumentException.class, () -> manager.importJson(json)); assertUnchanged(before);
    }
    @Test public void invalidLaterRuleDoesNotPartiallyImportEarlierRule() throws Exception {
        byte[] before = disk();
        JSONObject data = app().put("customRules", new JSONArray().put(rule("good")).put(rule("bad").put("parentDepth", "2")));
        String json = new JSONObject().put("package", PKG).put("data", data).toString();
        assertThrows(IllegalArgumentException.class, () -> manager.importJson(json)); assertUnchanged(before);
    }
    @Test public void invalidNativeFeatureDoesNotCoerceToEnabled() throws Exception {
        byte[] before = disk();
        String json = new JSONObject().put("package", PKG).put("data", app().put("nativeFeatures", new JSONObject().put("splash", "false"))).toString();
        assertThrows(IllegalArgumentException.class, () -> manager.importJson(json)); assertUnchanged(before);
    }
    @Test public void mismatchedPackageDoesNotCreateAnEntry() throws Exception {
        byte[] before = disk();
        String json = new JSONObject().put("package", "com.other.host").put("data", app()).toString();
        assertThrows(IllegalArgumentException.class, () -> manager.importJson(json)); assertUnchanged(before);
        assertNull(manager.findAppConfig("com.other.host"));
    }
    @Test public void unknownAndTrailingJsonAreRejected() throws Exception {
        byte[] before = disk();
        assertThrows(Exception.class, () -> manager.importJson("{}"));
        String trailing = input(1) + " {}";
        assertThrows(IllegalArgumentException.class, () -> manager.importJson(trailing)); assertUnchanged(before);
    }
    @Test public void deepJsonIsRejectedBeforeRecursiveParserOrMutation() throws Exception {
        byte[] before = disk();
        String json = "{\"apps\":{},\"extra\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}";
        assertThrows(IllegalArgumentException.class, () -> manager.importJson(json)); assertUnchanged(before);
    }
    @Test public void structuralCharactersAndEscapedQuotesInTextAreNotNesting() throws Exception {
        String text = "[".repeat(100) + " quoted \\\" text " + "}".repeat(100);
        JSONObject data = app().put("customRules", new JSONArray().put(rule("text-rule").put("targetValue", text)));
        String json = new JSONObject().put("package", PKG).put("data", data).toString();
        assertTrue(manager.importJson(json).contains("已保存"));
        assertEquals(text, manager.getAppConfigSnapshot(PKG).getCustomRules().get(0).getTargetValue());
    }
    @Test public void queuedOlderSaveCannotOverwriteCommittedImport() throws Exception {
        assertTrue(manager.save()); manager.importJson(input(1)); assertTrue(manager.awaitPendingWrites());
        assertEquals("imported-rule", new JSONObject(new String(disk(), java.nio.charset.StandardCharsets.UTF_8))
                .getJSONObject("apps").getJSONObject(PKG).getJSONArray("customRules").getJSONObject(0).getString("id"));
    }
    @Test public void interruptedBlockedQueueStartsNoImportOrLatePayload() throws Exception {
        byte[] before = disk();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        executor().execute(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        try {
            Thread.currentThread().interrupt();
            String json = input(1);
            assertThrows(IllegalStateException.class, () -> manager.importJson(json));
            assertTrue(Thread.currentThread().isInterrupted());
            assertUnchanged(before);
        } finally { Thread.interrupted(); release.countDown(); }
        assertTrue(manager.awaitWriteQueueIdle()); assertUnchanged(before);
    }
    @Test public void atomicMoveFailureKeepsOriginalFileAndMemory() throws Exception {
        byte[] before = disk();
        File blocked = temporary.newFolder("blocked-target");
        Files.write(new File(blocked, "child").toPath(), "block directory replacement".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Field target = ConfigManager.class.getDeclaredField("configFile"); target.setAccessible(true); target.set(manager, blocked);
        String json = input(1);
        assertThrows(IllegalStateException.class, () -> manager.importJson(json));
        assertUnchanged(before); assertTrue(blocked.isDirectory());
    }
    @Test public void synchronousSaveFailureDoesNotDeleteExistingConfig() throws Exception {
        byte[] before = disk(); assertTrue(new File(files, "adhook_config.json.tmp").mkdir());
        Field version = ConfigManager.class.getDeclaredField("configUpdatedAt"); version.setAccessible(true);
        long previousVersion = version.getLong(manager);
        assertFalse(manager.saveSynchronously()); assertArrayEquals(before, disk());
        assertEquals(previousVersion, version.getLong(manager));
        assertTrue(manager.awaitWriteQueueIdle());
        assertTrue(new File(files, "adhook_config.json.tmp").delete());
        assertTrue(manager.saveSynchronously());
        assertTrue(version.getLong(manager) > previousVersion);
    }
}
