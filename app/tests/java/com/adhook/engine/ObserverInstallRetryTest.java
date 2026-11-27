package com.adhook.engine;

import android.app.Activity;
import android.view.View;
import android.widget.AbsListView;
import android.widget.TextView;
import com.adhook.core.FeatureGuard;
import com.adhook.runtime.HookBridge;
import com.adhook.runtime.HookHelpers;
import com.adhook.runtime.HostContext;
import com.adhook.runtime.NativeApi101TestFramework;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** Recording API101 installs the real production methods; no install implementation is shadowed. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class ObserverInstallRetryTest {
    private static final String PKG = "com.engine.retry";
    private NativeApi101TestFramework framework;
    private Object previousPackage;
    @Before public void setUp() throws Exception {
        framework = new NativeApi101TestFramework(); framework.reset(); framework.initialize();
        Field pkg = FeatureGuard.class.getDeclaredField("sCurrentPackage"); pkg.setAccessible(true);
        previousPackage = pkg.get(null); pkg.set(null, PKG);
        setInstalled(false);
    }
    @After public void tearDown() throws Exception {
        framework.reset(); setInstalled(false);
        Field pkg = FeatureGuard.class.getDeclaredField("sCurrentPackage"); pkg.setAccessible(true); pkg.set(null, previousPackage);
    }
    private static void setInstalled(boolean value) throws Exception {
        Field field = CustomRuleEngine.class.getDeclaredField("sInstalled"); field.setAccessible(true); field.set(null, value);
        // Only fixture lifecycle reset; production readiness is exercised unchanged inside install().
        for (String name : new String[]{"sInstallAttempted", "sObserversReady"}) {
            Field readiness = CustomRuleEngine.class.getDeclaredField(name); readiness.setAccessible(true); readiness.setBoolean(null, false);
        }
    }
    private static boolean installed() throws Exception {
        Field field = CustomRuleEngine.class.getDeclaredField("sInstalled"); field.setAccessible(true); return field.getBoolean(null);
    }
    private void installEngine() throws Throwable {
        HostContext host = new HostContext(PKG, PKG, getClass().getClassLoader(), null, true);
        HookBridge.withRegistrationScope(host, () -> CustomRuleEngine.install(host.classLoader, PKG));
    }

    @Test public void firstSetTextFailureDoesNotSkipSecondAndRetryOnlyFillsMissingMember() throws Throwable {
        Method first = HookHelpers.findMethodExact(TextView.class, "setText", CharSequence.class);
        Method second = HookHelpers.findMethodExact(TextView.class, "setText", CharSequence.class, TextView.BufferType.class);
        Method attached = HookHelpers.findMethodExact(View.class, "onAttachedToWindow");
        Method resumed = HookHelpers.findMethodExact(Activity.class, "onResume");
        framework.failOnce(first);
        assertThrows(IllegalStateException.class, this::installEngine);
        assertFalse(installed()); assertEquals(0, framework.installCount(first));
        assertEquals(1, framework.installCount(second)); assertEquals(1, framework.installCount(attached));
        assertEquals(1, framework.installCount(resumed));
        installEngine(); assertTrue(installed());
        assertEquals(1, framework.installCount(first)); assertEquals(1, framework.installCount(second));
        assertEquals(1, framework.installCount(attached)); assertEquals(1, framework.installCount(resumed));
        installEngine(); assertEquals(1, framework.installCount(first)); assertEquals(1, framework.installCount(second));
    }

    @Test public void failedListBindingDoesNotHideSuccessfulRecycleAndSecondInstallRepairsOnlyIt() throws Throwable {
        Method obtain = HookHelpers.findMethodExact(AbsListView.class, "obtainView", int.class, boolean[].class);
        Class<?> bin = Class.forName("android.widget.AbsListView$RecycleBin");
        Method scrap = HookHelpers.findMethodExact(bin, "getScrapView", int.class);
        framework.failOnce(obtain);
        assertThrows(IllegalStateException.class, () -> ReusableViewHooks.install(getClass().getClassLoader(), PKG));
        assertEquals(0, framework.installCount(obtain)); assertEquals(1, framework.installCount(scrap));
        ReusableViewHooks.install(getClass().getClassLoader(), PKG);
        assertEquals(1, framework.installCount(obtain)); assertEquals(1, framework.installCount(scrap));
        ReusableViewHooks.install(getClass().getClassLoader(), PKG);
        assertEquals(1, framework.installCount(obtain)); assertEquals(1, framework.installCount(scrap));
    }
}
