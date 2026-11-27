package com.adhook.config.security;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import com.adhook.config.ConfigManager;
import com.adhook.config.ConfigProvider;
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
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowSystemClock;
import java.io.File;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class ConfigSecurityIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Context context;
    private ConfigManager manager;
    private ConfigProvider provider;
    private static final String HOST = "com.security.host";
    private static final String OTHER = "com.security.other";
    private static final int HOST_UID = 22001;

    @Before public void setUp() throws Exception {
        resetManager();
        Application app = RuntimeEnvironment.getApplication();
        File files = temporary.newFolder("files");
        context = new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
            @Override public File getDataDir() { return temporary.getRoot(); }
            @Override public Context createDeviceProtectedStorageContext() { return this; }
        };
        PackageInfo host = new PackageInfo();
        host.packageName = HOST;
        host.applicationInfo = new ApplicationInfo();
        host.applicationInfo.packageName = HOST;
        host.applicationInfo.uid = HOST_UID;
        host.signatures = new Signature[]{new Signature(new byte[]{1, 2, 3, 4})};
        Shadows.shadowOf(context.getPackageManager()).installPackage(host);
        Shadows.shadowOf(context.getPackageManager()).setPackagesForUid(HOST_UID, HOST);
        ShadowBinder.setCallingUid(Process.myUid());
        manager = ConfigManager.getInstance(context);
        provider = new ConfigProvider();
        ProviderInfo info = new ProviderInfo(); info.authority = ConfigProvider.AUTHORITY; info.exported = true;
        provider.attachInfo(context, info);
    }

    @After public void tearDown() throws Exception {
        ShadowBinder.setCallingUid(Process.myUid());
        if (manager != null) {
            Field io = ConfigManager.class.getDeclaredField("ioExecutor"); io.setAccessible(true);
            ExecutorService executor = (ExecutorService) io.get(manager);
            executor.shutdown(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        resetManager();
    }

    private static void resetManager() throws Exception {
        Field instance = ConfigManager.class.getDeclaredField("sInstance"); instance.setAccessible(true); instance.set(null, null);
    }

    private Bundle call(String method, String pkg, Bundle extras) { return provider.call(method, pkg, extras); }
    private Bundle stats(String json) { Bundle b = new Bundle(); b.putString("statsJson", json); return b; }
    private Bundle stats(String json, String processName, String sessionId) {
        Bundle b = stats(json); b.putString("processName", processName); b.putString("sessionId", sessionId); return b;
    }
    private static final String SESSION_A = "11111111111111111111111111111111";
    private static final String SESSION_B = "22222222222222222222222222222222";
    private static final String SESSION_C = "33333333333333333333333333333333";

    private void advanceStatsClock() { ShadowSystemClock.advanceBy(Duration.ofMillis(StatsRateLimiter.MIN_INTERVAL_MS)); }

    private void restartManager() throws Exception {
        assertTrue(manager.awaitPendingWrites());
        Field io = ConfigManager.class.getDeclaredField("ioExecutor"); io.setAccessible(true);
        ExecutorService executor = (ExecutorService) io.get(manager);
        executor.shutdown(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        resetManager();
        manager = ConfigManager.getInstance(context);
    }

    @Test public void absentReadsDoNotCreateConfigOrConfigFile() {
        assertNotNull(call(ConfigProvider.METHOD_GET_APP_CONFIG, HOST, null).getString("json"));
        assertTrue(manager.snapshotAppConfigs().isEmpty());
        AppConfig copy = manager.getAppConfigSnapshot(HOST); copy.setInspectorEnabled(true);
        assertNull(manager.findAppConfig(HOST));
        assertFalse(new File(context.getFilesDir(), "adhook_config.json").exists());
    }

    @Test public void runtimeSnapshotRequiresGrantAndOnlyExposesTheOwnedPackage() throws Exception {
        manager.getAppConfig(HOST).setHookEnabled(true);
        manager.getAppConfig(HOST).setFeatureEnabled("native_splash", false);
        manager.getAppConfig(OTHER).setHookEnabled(true);
        ShadowBinder.setCallingUid(HOST_UID);
        assertTrue(call(ConfigProvider.METHOD_GET_HOOK_RUNTIME, HOST, null).isEmpty());
        assertTrue(new HostAuthorizationStore(context).setUserAuthorized(HOST, true));
        JSONObject snapshot = new JSONObject(call(ConfigProvider.METHOD_GET_HOOK_RUNTIME, HOST, null).getString("json"));
        assertTrue(snapshot.getJSONObject("apps").getJSONObject(HOST).getBoolean("hookEnabled"));
        assertFalse(snapshot.getJSONObject("apps").getJSONObject(HOST)
                .getJSONObject("nativeFeatures").getBoolean("native_splash"));
        assertEquals(1, snapshot.getJSONObject("apps").length());
        assertFalse(snapshot.getJSONObject("apps").has(OTHER));
        assertTrue(call(ConfigProvider.METHOD_GET_HOOK_RUNTIME, OTHER, null).isEmpty());
    }

    @Test public void absentRuntimeReadDoesNotCreateAConfigEntryOrPersistDefaults() throws Exception {
        JSONObject snapshot = new JSONObject(call(ConfigProvider.METHOD_GET_HOOK_RUNTIME, HOST, null).getString("json"));
        assertEquals(0, snapshot.getJSONObject("apps").length());
        assertTrue(manager.snapshotAppConfigs().isEmpty());
        assertFalse(new File(context.getFilesDir(), "adhook_config.json").exists());
    }

    @Test public void storedConfigAndInspectorFlagDoNotAuthorizeHost() {
        manager.getAppConfig(HOST).setInspectorEnabled(true);
        ShadowBinder.setCallingUid(HOST_UID);
        assertTrue(call(ConfigProvider.METHOD_GET_APP_CONFIG, HOST, null).isEmpty());
        assertTrue(call(ConfigProvider.METHOD_GET_GLOBAL_CONFIG, null, null).isEmpty());
        assertFalse(new HostAuthorizationStore(context).isAuthorized(HOST, HOST_UID));
    }

    @Test public void explicitGrantIsOwnPackageOnlyAndCannotWriteGlobal() {
        HostAuthorizationStore grants = new HostAuthorizationStore(context);
        assertTrue(grants.setUserAuthorized(HOST, true));
        ShadowBinder.setCallingUid(HOST_UID);
        assertNotNull(call(ConfigProvider.METHOD_GET_APP_CONFIG, HOST, null).getString("json"));
        assertTrue(call(ConfigProvider.METHOD_GET_APP_CONFIG, OTHER, null).isEmpty());
        Bundle toggle = new Bundle(); toggle.putString("sdk", "rule_toast"); toggle.putBoolean("enabled", true);
        assertTrue(call(ConfigProvider.METHOD_SET_GLOBAL_SDK_ENABLED, null, toggle).isEmpty());
        assertFalse(manager.getGlobalConfigSnapshot().isRuleToastEnabled());
        assertFalse(grants.isAuthorized(HOST, HOST_UID + 1));
        assertTrue(grants.setUserAuthorized(HOST, false));
        assertTrue(call(ConfigProvider.METHOD_GET_APP_CONFIG, HOST, null).isEmpty());
    }

    @Test public void explicitHostWritesOnlyItsOwnPackage() {
        assertTrue(new HostAuthorizationStore(context).setUserAuthorized(HOST, true));
        ShadowBinder.setCallingUid(HOST_UID);
        Bundle extras = new Bundle(); extras.putString("featureId", "splash"); extras.putBoolean("enabled", false);
        assertTrue(call(ConfigProvider.METHOD_SET_FEATURE_ENABLED, HOST, extras).getBoolean("success"));
        assertTrue(call(ConfigProvider.METHOD_SET_FEATURE_ENABLED, OTHER, extras).isEmpty());
        assertFalse(manager.getAppConfigSnapshot(HOST).isFeatureEnabled("splash", true));
        assertNull(manager.findAppConfig(OTHER));
    }

    @Test public void malformedStatsAndWrongPackageNeverMutateOrPersist() throws Exception {
        assertTrue(new HostAuthorizationStore(context).setUserAuthorized(HOST, true));
        ShadowBinder.setCallingUid(HOST_UID);
        assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST, stats("{\"hits\":{\"bad\":-1}}")).isEmpty());
        assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, OTHER, stats("{\"hits\":{\"ok\":1}}")).isEmpty());
        assertNull(manager.getRuntimeStats(HOST)); assertNull(manager.getRuntimeStats(OTHER));
        assertFalse(context.getSharedPreferences("adhook_runtime_stats", Context.MODE_PRIVATE).contains(HOST));
        assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST, stats("{\"hits\":{\"ok\":1}}")).getBoolean("success"));
        assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST, stats("{\"hits\":{\"ok\":9}}")).isEmpty());
        assertEquals(Integer.valueOf(1), manager.getRuntimeStats(HOST).hitCounts.get("ok"));
        assertTrue(manager.awaitPendingWrites());
        JSONObject disk = new JSONObject(context.getSharedPreferences("adhook_runtime_stats", Context.MODE_PRIVATE).getString(HOST, ""));
        assertEquals(2, disk.getInt("schemaVersion"));
        assertEquals(1, disk.getJSONObject("legacySnapshot").getJSONObject("hits").getInt("ok"));
        assertEquals(0, disk.getJSONObject("processes").length());
        assertEquals(manager.getRuntimeStats(HOST).updatedAt, disk.getLong("updatedAt"));
    }

    @Test public void retiredStatsBroadcastHasNoExecutableReceiver() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.adhook.config.StatsReceiver"));
        Intent intent = new Intent("com.adhook.action.REPORT_STATS").putExtra("packageName", OTHER)
            .putExtra("statsJson", "{\"hits\":{\"fake\":123}}");
        intent.setClassName("com.adhook", "com.adhook.config.StatsReceiver");
        context.sendBroadcast(intent);
        assertNull(manager.getRuntimeStats(OTHER));
        assertFalse(context.getSharedPreferences("adhook_runtime_stats", Context.MODE_PRIVATE).contains(OTHER));
    }

    @Test public void returnIntentWithExistingIdOnlyNavigatesAndCannotOverwritePersistedRule() throws Exception {
        com.adhook.config.model.CustomRule saved = new com.adhook.config.model.CustomRule("saved", "res_id", "saved_target", "collapse");
        saved.setId("known-id");
        assertTrue(manager.upsertCustomRule(HOST, saved, 500));
        assertTrue(manager.awaitPendingWrites());
        Intent injected = new Intent().putExtra("package_name", HOST).putExtra("app_name", "Forged")
            .putExtra("pending_rule_json", "{\"id\":\"known-id\",\"type\":\"text\",\"targetValue\":\"Pay\",\"action\":\"click\"}");
        assertEquals(HOST, com.adhook.ui.data.DetailIntentPolicy.INSTANCE.packageName(injected));
        assertTrue(com.adhook.ui.data.DetailIntentPolicy.INSTANCE.consumePayloadHint(injected));
        com.adhook.config.model.CustomRule after = manager.getAppConfigSnapshot(HOST).getCustomRules().get(0);
        assertEquals("saved_target", after.getTargetValue());
        assertEquals("collapse", after.getAction());
        assertFalse(new HostAuthorizationStore(context).isAuthorized(HOST, HOST_UID));
    }

    @Test public void runtimeStatsSnapshotsDoNotLeakLiveMaps() {
        manager.updateRuntimeStats(HOST, new JSONObject()); // Invalid root cannot create an empty entry.
        assertNull(manager.getRuntimeStats(HOST));
        assertTrue(manager.acceptRuntimeStats(HOST, RuntimeStatsPolicy.parse("{\"hits\":{\"ok\":1}}")));
        manager.getRuntimeStats(HOST).hitCounts.put("poison", 100);
        assertFalse(manager.getRuntimeStats(HOST).hitCounts.containsKey("poison"));
        assertTrue(manager.acceptRuntimeStats(HOST, RuntimeStatsPolicy.parse("{\"hits\":{\"new\":2}}")));
        assertFalse(manager.getRuntimeStats(HOST).hitCounts.containsKey("ok"));
    }

    @Test public void invalidMetadataAndTruncationAreRejectedBeforeManagerInitialization() throws Exception {
        assertTrue(new HostAuthorizationStore(context).setUserAuthorized(HOST, true));
        ShadowBinder.setCallingUid(HOST_UID);
        resetManager();
        Field instance = ConfigManager.class.getDeclaredField("sInstance"); instance.setAccessible(true);
        String json = "{\"hits\":{\"splash\":1}}";
        List<Bundle> invalid = new ArrayList<>();
        Bundle b = stats(json); b.putString("processName", HOST); invalid.add(b);
        b = stats(json); b.putString("sessionId", SESSION_A); invalid.add(b);
        invalid.add(stats(json, null, SESSION_A));
        invalid.add(stats(json, HOST, null));
        invalid.add(stats(json, HOST + ":", SESSION_A));
        invalid.add(stats(json, OTHER + ":remote", SESSION_A));
        invalid.add(stats(json, HOST, "bad-session"));
        b = stats(json, HOST, SESSION_A); b.putInt("processName", 123); invalid.add(b);
        b = stats(json, HOST, SESSION_A); b.putBoolean("sessionId", true); invalid.add(b);
        b = stats(json, HOST, SESSION_A); b.putString("statsTruncated", "true"); invalid.add(b);
        b = stats(json, HOST, SESSION_A); b.putString("statsTruncated", null); invalid.add(b);
        b = stats(json); b.putInt("statsTruncated", 1); invalid.add(b);
        for (Bundle extras : invalid) {
            assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST, extras).isEmpty());
            assertNull(instance.get(null));
            assertNull(manager.getRuntimeStats(HOST));
            assertFalse(context.getSharedPreferences("adhook_runtime_stats", Context.MODE_PRIVATE).contains(HOST));
        }
    }

    @Test public void metadataCannotAuthorizeHostOrImpersonateAnotherPackage() {
        ShadowBinder.setCallingUid(HOST_UID);
        Bundle report = stats("{\"hits\":{\"splash\":1}}", HOST, SESSION_A);
        assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST, report).isEmpty());
        assertNull(manager.getRuntimeStats(HOST));
        assertTrue(new HostAuthorizationStore(context).setUserAuthorized(HOST, true));
        assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, OTHER,
                stats("{\"hits\":{\"splash\":1}}", OTHER, SESSION_A)).isEmpty());
        assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST,
                stats("{\"hits\":{\"splash\":1}}", OTHER, SESSION_A)).isEmpty());
        assertNull(manager.getRuntimeStats(OTHER));
        assertNull(manager.getRuntimeStats(HOST));
        assertTrue(manager.snapshotAppConfigs().isEmpty());
    }

    @Test public void namedProcessMetadataDoesNotWeakenInnerPacketValidationOrConsumeRateOnMalformedInput() {
        assertTrue(new HostAuthorizationStore(context).setUserAuthorized(HOST, true));
        ShadowBinder.setCallingUid(HOST_UID);
        for (String json : new String[]{"{\"hits\":{\"good\":1,\"bad\":-1}}", "{\"states\":{\"splash\":\"POISON\"}}",
                "{\"hits\":{\"splash\":\"1\"}}", "{\"hits\":{\"splash\":1},\"processName\":\"fake\"}"}) {
            assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST, stats(json, HOST, SESSION_A)).isEmpty());
            assertNull(manager.getRuntimeStats(HOST));
        }
        Bundle badFlag = stats("{\"hits\":{\"splash\":9}}", HOST, SESSION_A);
        badFlag.putInt("statsTruncated", 1);
        assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST, badFlag).isEmpty());
        assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST,
                stats("{\"hits\":{\"splash\":9}}", HOST, SESSION_A)).getBoolean("success"));
        assertEquals(Integer.valueOf(9), manager.getRuntimeStats(HOST).hitCounts.get("splash"));
        assertFalse(manager.getRuntimeStats(HOST).truncated);
    }

    @Test public void rateLimitRemainsPerUidAcrossProcessAndSessionNames() {
        assertTrue(new HostAuthorizationStore(context).setUserAuthorized(HOST, true));
        ShadowBinder.setCallingUid(HOST_UID);
        assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST,
                stats("{\"hits\":{\"splash\":9}}", HOST, SESSION_A)).getBoolean("success"));
        assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST,
                stats("{\"hits\":{\"splash\":3}}", HOST + ":remote", SESSION_B)).isEmpty());
        assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST,
                stats("{\"hits\":{\"splash\":1}}", HOST, SESSION_C)).isEmpty());
        advanceStatsClock();
        assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST,
                stats("{\"hits\":{\"splash\":3}}", HOST + ":remote", SESSION_B)).getBoolean("success"));
        assertEquals(Integer.valueOf(12), manager.getRuntimeStats(HOST).hitCounts.get("splash"));
        assertEquals(2, manager.getRuntimeStats(HOST).processCount);
    }

    @Test public void successfulStatsNotifyPackageUriButRejectedPacketsDoNot() {
        assertTrue(new HostAuthorizationStore(context).setUserAuthorized(HOST, true));
        ShadowBinder.setCallingUid(HOST_UID);
        Uri expected = ConfigProvider.CONTENT_URI.buildUpon().appendPath("runtime-stats").appendPath(HOST).build();
        List<Uri> notifications = new ArrayList<>();
        ContentObserver observer = new ContentObserver(null) {
            @Override public void onChange(boolean selfChange, Uri uri) { notifications.add(uri); }
        };
        context.getContentResolver().registerContentObserver(expected, false, observer);
        try {
            assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST,
                    stats("{\"hits\":{\"splash\":1}}", HOST, "invalid")).isEmpty());
            assertTrue(notifications.isEmpty());
            assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST,
                    stats("{\"hits\":{\"splash\":1}}", HOST, SESSION_A)).getBoolean("success"));
            assertEquals(1, notifications.size());
            assertEquals(expected, notifications.get(0));
            assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST,
                    stats("{\"hits\":{\"splash\":9}}", HOST, SESSION_A)).isEmpty());
            assertEquals(1, notifications.size());
        } finally {
            context.getContentResolver().unregisterContentObserver(observer);
        }
    }

    @Test public void processSnapshotsSurviveManagerRestartWithoutCreatingOrChangingRuleConfig() throws Exception {
        assertTrue(new HostAuthorizationStore(context).setUserAuthorized(HOST, true));
        ShadowBinder.setCallingUid(HOST_UID);
        Bundle main = stats("{\"states\":{\"splash\":\"OK\"},\"hits\":{\"splash\":7}}", HOST, SESSION_A);
        main.putBoolean("statsTruncated", true);
        assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST, main).getBoolean("success"));
        advanceStatsClock();
        assertTrue(call(ConfigProvider.METHOD_REPORT_RUNTIME_STATS, HOST,
                stats("{\"states\":{\"splash\":\"FAILED\"},\"hits\":{\"splash\":3}}", HOST + ":remote", SESSION_B))
                .getBoolean("success"));
        ConfigManager.AppRuntimeStats before = manager.getRuntimeStats(HOST);
        assertEquals(Integer.valueOf(10), before.hitCounts.get("splash"));
        assertEquals("FAILED", before.featureStates.get("splash"));
        assertTrue(before.truncated);
        assertTrue(manager.snapshotAppConfigs().isEmpty());
        restartManager();
        ConfigManager.AppRuntimeStats restored = manager.getRuntimeStats(HOST);
        assertNotNull(restored);
        assertEquals(before.updatedAt, restored.updatedAt);
        assertEquals(before.hitCounts, restored.hitCounts);
        assertEquals(before.featureStates, restored.featureStates);
        assertEquals(2, restored.processCount);
        assertTrue(restored.truncated);
        assertTrue(manager.acceptRuntimeStats(HOST, RuntimeStatsPolicy.parse("{\"hits\":{\"splash\":1}}"),
                HOST, SESSION_C));
        assertEquals(Integer.valueOf(4), manager.getRuntimeStats(HOST).hitCounts.get("splash"));
        assertFalse(manager.getRuntimeStats(HOST).truncated);
        assertTrue(manager.snapshotAppConfigs().isEmpty());
        assertFalse(new File(context.getFilesDir(), "adhook_config.json").exists());
        assertFalse(context.getSharedPreferences("adhook_config", Context.MODE_PRIVATE).contains("config_json"));
    }

    @Test public void oldPlainStatsAreLoadedReadOnlyAndInvalidEnvelopesAreNotRewritten() throws Exception {
        String legacy = "{\"states\":{\"splash\":\"OK\"},\"hits\":{\"splash\":9}}";
        RuntimeStatsEnvelope envelope = RuntimeStatsEnvelope.replace(OTHER, null,
                RuntimeStatsPolicy.parse("{\"hits\":{\"native\":2}}"), OTHER, SESSION_B, 15L);
        JSONObject corrupted = envelope.toJson();
        corrupted.getJSONObject("processes").getJSONObject(OTHER).getJSONObject("snapshot")
                .getJSONObject("hits").put("poison", -1);
        String invalid = corrupted.toString();
        assertTrue(context.getSharedPreferences("adhook_runtime_stats", Context.MODE_PRIVATE).edit()
                .putString(HOST, legacy).putString(OTHER, invalid).putString("not a package", legacy).commit());
        restartManager();
        assertEquals(Integer.valueOf(9), manager.getRuntimeStats(HOST).hitCounts.get("splash"));
        assertEquals(0L, manager.getRuntimeStats(HOST).updatedAt);
        assertEquals(0, manager.getRuntimeStats(HOST).processCount);
        assertFalse(manager.getRuntimeStats(HOST).truncated);
        assertNull(manager.getRuntimeStats(OTHER));
        assertEquals(invalid, context.getSharedPreferences("adhook_runtime_stats", Context.MODE_PRIVATE).getString(OTHER, ""));
        assertEquals(legacy, context.getSharedPreferences("adhook_runtime_stats", Context.MODE_PRIVATE).getString(HOST, ""));
        assertTrue(manager.snapshotAppConfigs().isEmpty());
        assertFalse(new File(context.getFilesDir(), "adhook_config.json").exists());
        assertTrue(manager.acceptRuntimeStats(HOST, RuntimeStatsPolicy.parse("{\"hits\":{\"splash\":1}}"), HOST, SESSION_A));
        assertEquals(Integer.valueOf(1), manager.getRuntimeStats(HOST).hitCounts.get("splash"));
    }

    @Test public void newAndLegacySnapshotsRetainWholeReplacementAndDetachedUiSemantics() {
        assertTrue(manager.acceptRuntimeStats(HOST, RuntimeStatsPolicy.parse("{\"hits\":{\"old\":5}}"), HOST, SESSION_A));
        assertTrue(manager.acceptRuntimeStats(HOST, RuntimeStatsPolicy.parse("{\"hits\":{\"remote\":3}}"), HOST + ":remote", SESSION_B));
        assertTrue(manager.acceptRuntimeStats(HOST, RuntimeStatsPolicy.parse("{\"hits\":{\"new\":1}}"), HOST, SESSION_A));
        assertFalse(manager.getRuntimeStats(HOST).hitCounts.containsKey("old"));
        assertEquals(Integer.valueOf(3), manager.getRuntimeStats(HOST).hitCounts.get("remote"));
        ConfigManager.AppRuntimeStats copy = manager.getRuntimeStats(HOST);
        copy.hitCounts.put("poison", 99); copy.processCount = 99; copy.truncated = true;
        assertFalse(manager.getRuntimeStats(HOST).hitCounts.containsKey("poison"));
        assertEquals(2, manager.getRuntimeStats(HOST).processCount);
        assertFalse(manager.getRuntimeStats(HOST).truncated);
        assertFalse(manager.acceptRuntimeStats(HOST, RuntimeStatsPolicy.parse("{\"hits\":{\"bad\":1}}"), HOST, null));
        assertEquals(2, manager.getRuntimeStats(HOST).processCount);
        assertTrue(manager.acceptRuntimeStats(HOST, RuntimeStatsPolicy.parse("{\"hits\":{\"legacy\":2}}")));
        assertEquals(1, manager.getRuntimeStats(HOST).hitCounts.size());
        assertEquals(Integer.valueOf(2), manager.getRuntimeStats(HOST).hitCounts.get("legacy"));
        assertEquals(0, manager.getRuntimeStats(HOST).processCount);
    }

    @Test public void failedEnqueueRollsBackProcessReplacementAndCannotCreateNewStats() throws Exception {
        assertTrue(manager.acceptRuntimeStats(HOST, RuntimeStatsPolicy.parse("{\"hits\":{\"splash\":5}}"), HOST, SESSION_A, true));
        assertTrue(manager.awaitPendingWrites());
        ConfigManager.AppRuntimeStats before = manager.getRuntimeStats(HOST);
        String disk = context.getSharedPreferences("adhook_runtime_stats", Context.MODE_PRIVATE).getString(HOST, "");
        Field io = ConfigManager.class.getDeclaredField("ioExecutor"); io.setAccessible(true);
        ExecutorService executor = (ExecutorService) io.get(manager);
        executor.shutdown(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertFalse(manager.acceptRuntimeStats(HOST, RuntimeStatsPolicy.parse("{\"hits\":{\"new\":1}}"), HOST, SESSION_C));
        assertFalse(manager.acceptRuntimeStats(OTHER, RuntimeStatsPolicy.parse("{\"hits\":{\"new\":1}}"), OTHER, SESSION_A));
        ConfigManager.AppRuntimeStats after = manager.getRuntimeStats(HOST);
        assertEquals(before.hitCounts, after.hitCounts);
        assertEquals(before.processCount, after.processCount);
        assertEquals(before.updatedAt, after.updatedAt);
        assertTrue(after.truncated);
        assertNull(manager.getRuntimeStats(OTHER));
        assertEquals(disk, context.getSharedPreferences("adhook_runtime_stats", Context.MODE_PRIVATE).getString(HOST, ""));
    }
}
