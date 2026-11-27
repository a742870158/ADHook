package com.adhook.config.security;

import android.content.Context;
import android.content.ContextWrapper;
import com.adhook.config.ConfigClient;
import com.adhook.config.ConfigManager;
import com.adhook.config.model.AppConfig;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Faults only the external notifications; file commit and ConfigClient invalidation are production paths. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = android.app.Application.class, shadows = {
        ConfigCommitNotificationTest.Publication.class})
public class ConfigCommitNotificationTest {
    @Implements(className = "com.adhook.config.RemoteConfigPublisher", isInAndroidSdk = false)
    public static class Publication {
        static long desired;
        static boolean failPublish, failNotice;
        @Implementation protected static void publish(String full, String launch) {
            if (failPublish) throw new LinkageError("publication unavailable after commit");
        }
        @Implementation protected static void expectVersion(long version) {
            desired = version;
            if (failNotice) throw new LinkageError("notification unavailable");
        }
    }
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String PKG = "com.notice.host";
    private ConfigManager manager;
    private File files;
    @Before public void setup() throws Exception {
        reset(); Publication.desired = 0; Publication.failPublish = false; Publication.failNotice = false;
        files = temporary.newFolder("files");
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
            @Override public File getDataDir() { return temporary.getRoot(); }
            @Override public Context createDeviceProtectedStorageContext() { return this; }
        };
        manager = ConfigManager.getInstance(context);
        assertEquals(new File(files, "adhook_config.json"), field(ConfigManager.class, "configFile").get(manager));
    }
    @After public void teardown() throws Exception {
        Field io = field(ConfigManager.class, "ioExecutor"); ExecutorService executor = (ExecutorService) io.get(manager);
        executor.shutdown(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        ConfigClient.invalidateCache(null); reset();
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void reset() throws Exception { field(ConfigManager.class, "sInstance").set(null, null); }
    private JSONObject seedCache() throws Exception {
        JSONObject marker = new JSONObject().put("stale", true);
        field(ConfigClient.class, "sSnapshotCache").set(null, marker);
        @SuppressWarnings("unchecked") Map<String, AppConfig> cached = (Map<String, AppConfig>) field(ConfigClient.class, "sAppConfigCache").get(null);
        cached.put(PKG, new AppConfig(PKG));
        return marker;
    }
    private void assertNotified() throws Exception {
        assertNull(field(ConfigClient.class, "sSnapshotCache").get(null));
        assertTrue(((Map<?, ?>) field(ConfigClient.class, "sAppConfigCache").get(null)).isEmpty());
        assertEquals(field(ConfigManager.class, "configUpdatedAt").getLong(manager), Publication.desired);
    }
    private String incoming() throws Exception {
        return new JSONObject().put("package", PKG).put("data", new JSONObject()
                .put("nativeFeatures", new JSONObject().put("splash", true))).toString();
    }
    private void assertFileAndMemoryAgree() throws Exception {
        JSONObject root = new JSONObject(new String(Files.readAllBytes(new File(files, "adhook_config.json").toPath()), java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(root.getJSONObject("apps").getJSONObject(PKG).getJSONObject("nativeFeatures").getBoolean("splash"));
        assertTrue(manager.getAppConfigSnapshot(PKG).isFeatureEnabled("splash", false));
    }
    @Test public void synchronousCommitImmediatelyInvalidatesCaches() throws Exception {
        seedCache(); manager.getAppConfig(PKG).setFeatureEnabled("splash", true);
        assertTrue(manager.saveSynchronously()); assertNotified(); assertFileAndMemoryAgree();
    }
    @Test public void postCommitPublicationLinkageFailureCannotReportUncommittedImport() throws Exception {
        seedCache(); Publication.failPublish = true;
        assertTrue(manager.importJson(incoming()).contains("已保存"));
        assertNotified(); assertFileAndMemoryAgree();
    }
    @Test public void oneFailedNoticeCannotSkipOtherNoticesOrReverseCommit() throws Exception {
        seedCache(); Publication.failNotice = true;
        assertTrue(manager.importJson(incoming()).contains("已保存"));
        assertNotified(); assertFileAndMemoryAgree();
    }
    @Test public void failedLocalWriteDoesNotNotifyOrInvalidateOldCache() throws Exception {
        JSONObject marker = seedCache(); assertTrue(new File(files, "adhook_config.json.tmp").mkdir());
        assertThrows(IllegalStateException.class, () -> manager.importJson(incoming()));
        assertSame(marker, field(ConfigClient.class, "sSnapshotCache").get(null));
        assertEquals(0, Publication.desired);
        assertNull(manager.findAppConfig(PKG)); assertFalse(new File(files, "adhook_config.json").exists());
    }
}
