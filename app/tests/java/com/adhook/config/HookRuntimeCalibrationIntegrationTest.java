package com.adhook.config;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.SystemClock;

import com.adhook.config.model.AppConfig;
import com.adhook.config.model.CustomRule;
import com.adhook.config.model.GlobalConfig;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class HookRuntimeCalibrationIntegrationTest {
    private static final String PKG = "com.calibration.host";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Field runtimeField;
    private Object previousRuntime;
    private Set<String> runtimePackages;
    private Set<String> previousPackages;
    private HookProviderCalibration calibration;
    private long previousNext;
    private int previousFailures;
    private int previousCursor;
    private final AtomicInteger calls = new AtomicInteger();
    private Context context;
    private HookRuntimeSnapshot staleRemote;
    private String providerJson;

    private Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private JSONObject root(boolean hook, long version) throws Exception {
        AppConfig app = new AppConfig(PKG);
        app.setHookEnabled(hook);
        app.setFeatureEnabled("native_splash", false);
        return new JSONObject().put("version", 1).put("updated_at", version)
                .put("global_sdk", new GlobalConfig().toJson())
                .put("apps", new JSONObject().put(PKG, app.toJson()));
    }

    @Before public void setUp() throws Exception {
        runtimeField = field(ConfigClient.class, "sHookRuntime");
        previousRuntime = runtimeField.get(null);
        runtimeField.set(null, HookRuntimeSnapshot.empty());
        @SuppressWarnings("unchecked") Set<String> packages =
                (Set<String>) field(ConfigClient.class, "sRuntimePackages").get(null);
        runtimePackages = packages;
        previousPackages = new HashSet<>(packages);
        packages.clear();
        packages.add(PKG);
        calibration = (HookProviderCalibration) field(ConfigClient.class, "sProviderCalibration").get(null);
        previousNext = field(HookProviderCalibration.class, "nextAllowedAt").getLong(calibration);
        previousFailures = field(HookProviderCalibration.class, "failures").getInt(calibration);
        previousCursor = field(HookProviderCalibration.class, "cursor").getInt(calibration);
        field(HookProviderCalibration.class, "nextAllowedAt").setLong(calibration, 0L);
        field(HookProviderCalibration.class, "failures").setInt(calibration, 0);
        field(HookProviderCalibration.class, "cursor").setInt(calibration, 0);
        context = RuntimeEnvironment.getApplication();
        providerJson = root(false, 101L).toString();
        ConfigProvider provider = new ConfigProvider() {
            @Override public Bundle call(String method, String arg, Bundle extras) {
                calls.incrementAndGet();
                assertEquals(ConfigProvider.METHOD_GET_HOOK_RUNTIME, method);
                assertEquals(PKG, arg);
                Bundle result = new Bundle();
                result.putString("json", providerJson);
                return result;
            }
        };
        ProviderInfo info = new ProviderInfo();
        info.authority = ConfigProvider.AUTHORITY;
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(ConfigProvider.AUTHORITY, provider);
        staleRemote = HookRuntimeSnapshot.parseFull(root(true, 100L));
    }

    @After public void tearDown() throws Exception {
        worker.shutdown();
        assertTrue(worker.awaitTermination(5L, TimeUnit.SECONDS));
        runtimeField.set(null, previousRuntime);
        runtimePackages.clear();
        runtimePackages.addAll(previousPackages);
        field(HookProviderCalibration.class, "nextAllowedAt").setLong(calibration, previousNext);
        field(HookProviderCalibration.class, "failures").setInt(calibration, previousFailures);
        field(HookProviderCalibration.class, "cursor").setInt(calibration, previousCursor);
    }

    @Test public void validOldRemoteCannotHideTheNewAuthorizedLocalCloseOrRestoreItLater() throws Exception {
        long now = SystemClock.elapsedRealtime();
        worker.submit(() -> ConfigClient.finishHookRuntimeRefresh(staleRemote, context, now)).get(5L, TimeUnit.SECONDS);
        assertEquals(1, calls.get());
        assertFalse(ConfigClient.isHookEnabledCached(PKG));
        assertEquals(101L, ((HookRuntimeSnapshot) runtimeField.get(null)).updatedAt);
        worker.submit(() -> ConfigClient.finishHookRuntimeRefresh(staleRemote, context, now + 5000L)).get(5L, TimeUnit.SECONDS);
        assertEquals(1, calls.get());
        assertFalse(ConfigClient.isHookEnabledCached(PKG));
        worker.submit(() -> ConfigClient.finishHookRuntimeRefresh(staleRemote, context, now + 30000L)).get(5L, TimeUnit.SECONDS);
        assertEquals(2, calls.get());
        assertFalse(ConfigClient.isHookEnabledCached(PKG));
    }

    @Test public void recoveredSameVersionFullRootCanFillPackagesAfterScopedCalibration() throws Exception {
        long now = SystemClock.elapsedRealtime();
        worker.submit(() -> ConfigClient.finishHookRuntimeRefresh(staleRemote, context, now)).get(5L, TimeUnit.SECONDS);
        assertFalse(((HookRuntimeSnapshot) runtimeField.get(null)).complete);
        JSONObject recovered = root(false, 101L);
        AppConfig other = new AppConfig("com.calibration.other");
        other.setHookEnabled(true);
        other.setFeatureEnabled("native_splash", false);
        recovered.getJSONObject("apps").put(other.getPackageName(), other.toJson());
        HookRuntimeSnapshot full = HookRuntimeSnapshot.parseFull(recovered);
        worker.submit(() -> ConfigClient.finishHookRuntimeRefresh(full, context, now + 5000L)).get(5L, TimeUnit.SECONDS);
        assertTrue(((HookRuntimeSnapshot) runtimeField.get(null)).complete);
        assertFalse(ConfigClient.isHookEnabledCached(PKG));
        assertTrue(ConfigClient.isHookEnabledCached(other.getPackageName()));
        assertFalse(ConfigClient.getAppConfigCached(other.getPackageName()).isFeatureEnabled("native_splash", true));
        assertEquals(1, calls.get());
    }

    @Test public void sameVersionScopedFullCompletesLaunchRulesWithoutChangingIndependentSwitches() throws Exception {
        JSONObject full = root(true, 101L);
        CustomRule rule = new CustomRule("ad", CustomRule.TYPE_RES_ID, "ad_container",
                CustomRule.ACTION_COLLAPSE, 0);
        rule.setId("calibration-rule");
        full.getJSONObject("apps").getJSONObject(PKG).getJSONArray("customRules").put(rule.toJson());
        AppConfig other = new AppConfig("com.calibration.other");
        other.setHookEnabled(false);
        other.setInspectorEnabled(true);
        full.getJSONObject("apps").put(other.getPackageName(), other.toJson());
        runtimeField.set(null, HookRuntimeSnapshot.parseLaunch(HookLaunchSnapshot.fromConfig(full)));
        assertTrue(ConfigClient.getAppConfigCached(PKG).getCustomRules().isEmpty());
        assertFalse(((HookRuntimeSnapshot) runtimeField.get(null)).isPackageComplete(PKG));
        // An authorized, package-scoped response carries full rules at exactly the launch version.
        full.getJSONObject("apps").remove(other.getPackageName());
        providerJson = full.toString();
        long now = SystemClock.elapsedRealtime();
        worker.submit(() -> ConfigClient.finishHookRuntimeRefresh(null, context, now)).get(5L, TimeUnit.SECONDS);
        HookRuntimeSnapshot calibrated = (HookRuntimeSnapshot) runtimeField.get(null);
        assertEquals(1, calls.get());
        assertEquals("calibration-rule", ConfigClient.getAppConfigCached(PKG).getCustomRules().get(0).getId());
        assertTrue(ConfigClient.isHookEnabledCached(PKG));
        assertFalse(ConfigClient.getAppConfigCached(PKG).isFeatureEnabled("native_splash", true));
        assertTrue(calibrated.isPackageComplete(PKG));
        assertFalse(calibrated.complete);
        assertFalse(calibrated.isPackageComplete(other.getPackageName()));
        assertFalse(ConfigClient.isHookEnabledCached(other.getPackageName()));
        worker.submit(() -> ConfigClient.finishHookRuntimeRefresh(null, context, now + 30000L)).get(5L, TimeUnit.SECONDS);
        assertEquals(2, calls.get());
        assertSame("An already complete same-version package is not republished", calibrated, runtimeField.get(null));
    }

    @Test public void sameVersionAuthorizedAbsenceRemovesLaunchEntryAndIsRememberedAsComplete() throws Exception {
        runtimeField.set(null, HookRuntimeSnapshot.parseLaunch(HookLaunchSnapshot.fromConfig(root(true, 101L))));
        JSONObject empty = root(false, 101L);
        empty.getJSONObject("apps").remove(PKG);
        providerJson = empty.toString();
        long now = SystemClock.elapsedRealtime();
        worker.submit(() -> ConfigClient.finishHookRuntimeRefresh(null, context, now)).get(5L, TimeUnit.SECONDS);
        HookRuntimeSnapshot calibrated = (HookRuntimeSnapshot) runtimeField.get(null);
        assertNull(ConfigClient.getAppConfigCached(PKG));
        assertFalse(ConfigClient.isHookEnabledCached(PKG));
        assertTrue(calibrated.isPackageComplete(PKG));
        worker.submit(() -> ConfigClient.finishHookRuntimeRefresh(null, context, now + 30000L)).get(5L, TimeUnit.SECONDS);
        assertSame(calibrated, runtimeField.get(null));
    }

    @Test public void deniedProviderResponseCannotUpgradeLaunchCompletenessOrToggle() throws Exception {
        HookRuntimeSnapshot launch = HookRuntimeSnapshot.parseLaunch(HookLaunchSnapshot.fromConfig(root(false, 101L)));
        runtimeField.set(null, launch);
        providerJson = null;
        long now = SystemClock.elapsedRealtime();
        worker.submit(() -> ConfigClient.finishHookRuntimeRefresh(null, context, now)).get(5L, TimeUnit.SECONDS);
        assertEquals(1, calls.get());
        assertSame(launch, runtimeField.get(null));
        assertFalse(launch.isPackageComplete(PKG));
        assertFalse(ConfigClient.isHookEnabledCached(PKG));
    }

    @Test public void mainThreadRefreshCannotCallProviderOrConsumeTheWorkerBudget() throws Exception {
        long now = SystemClock.elapsedRealtime();
        ConfigClient.finishHookRuntimeRefresh(staleRemote, context, now);
        assertEquals(0, calls.get());
        worker.submit(() -> ConfigClient.finishHookRuntimeRefresh(staleRemote, context, now)).get(5L, TimeUnit.SECONDS);
        assertEquals(1, calls.get());
        assertFalse(ConfigClient.isHookEnabledCached(PKG));
    }
}
