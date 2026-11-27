package com.adhook.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class RuleHitToastNotifierTest {

    private Context context;
    private AtomicLong clock;
    private List<String> shown;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        clock = new AtomicLong(1_000L);
        shown = new ArrayList<>();
        RuleHitToastNotifier.resetForTest();
        RuleHitToastNotifier.setTestHooks(clock::get, (ctx, msg) -> shown.add(msg), false);
    }

    @After
    public void tearDown() {
        RuleHitToastNotifier.resetForTest();
    }

    @Test
    public void cooldownThrottlesSameAppForTwoSeconds() {
        installApp("com.example.demo", "示例应用");

        RuleHitToastNotifier.notifyHit(context, "com.example.demo");
        assertEquals(List.of("示例应用触发了Hook规则"), shown);

        // 1999ms 后仍在冷却期内，不重复弹出
        clock.addAndGet(RuleHitToastNotifier.COOLDOWN_MS - 1L);
        RuleHitToastNotifier.notifyHit(context, "com.example.demo");
        assertEquals(1, shown.size());

        // 满 2000ms 后可再次弹出
        clock.addAndGet(1L);
        RuleHitToastNotifier.notifyHit(context, "com.example.demo");
        assertEquals(2, shown.size());
    }

    @Test
    public void packagesHaveIndependentCooldowns() {
        installApp("com.example.alpha", "应用甲");
        installApp("com.example.beta", "应用乙");

        RuleHitToastNotifier.notifyHit(context, "com.example.alpha");
        RuleHitToastNotifier.notifyHit(context, "com.example.beta");
        RuleHitToastNotifier.notifyHit(context, "com.example.alpha");

        assertEquals(2, shown.size());
        assertEquals("应用甲触发了Hook规则", shown.get(0));
        assertEquals("应用乙触发了Hook规则", shown.get(1));
    }

    @Test
    public void mainProcessCheckFiltersSubProcesses() {
        assertTrue(RuleHitToastNotifier.isMainProcess("com.example.demo", "com.example.demo"));
        assertTrue(RuleHitToastNotifier.isMainProcess("com.example.demo", null));
        assertTrue(RuleHitToastNotifier.isMainProcess("com.example.demo", ""));
        assertFalse(RuleHitToastNotifier.isMainProcess("com.example.demo", "com.example.demo:push"));
        assertFalse(RuleHitToastNotifier.isMainProcess("com.example.demo", "com.example.demo:remote"));
        assertFalse(RuleHitToastNotifier.isMainProcess(null, "com.example.demo"));
        assertFalse(RuleHitToastNotifier.isMainProcess("", "com.example.demo"));
    }

    @Test
    public void unknownPackageFallsBackToPackageName() {
        RuleHitToastNotifier.notifyHit(context, "com.example.uninstalled");
        assertEquals(List.of("com.example.uninstalled触发了Hook规则"), shown);
    }

    private void installApp(String pkg, String label) {
        ShadowPackageManager spm = Shadows.shadowOf(context.getPackageManager());
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = pkg;
        ai.nonLocalizedLabel = label;
        pi.applicationInfo = ai;
        spm.installPackage(pi);
    }
}
