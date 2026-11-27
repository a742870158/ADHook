package com.adhook.core;

import android.app.Activity;
import android.widget.TextView;
import com.adhook.engine.CustomRuleEngine;
import com.adhook.inspector.VisualInspector;
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
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** Production component orchestration and real engine/inspector native installs, without install shadows. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class HookDispatcherInstallRetryTest {
    private static final String PKG = "com.dispatch.retry";
    private NativeApi101TestFramework framework;
    private HostContext host;
    private Method generic, setter, inspector;
    private Object previousPackage;

    @Before public void setUp() throws Exception {
        framework = new NativeApi101TestFramework(); framework.reset(); framework.initialize(); resetRuntimeFixture();
        Field pkg = FeatureGuard.class.getDeclaredField("sCurrentPackage"); pkg.setAccessible(true);
        previousPackage = pkg.get(null); pkg.set(null, PKG);
        host = new HostContext(PKG, PKG, getClass().getClassLoader(), null, true);
        generic = HookDispatcher.class.getDeclaredMethod("runGenericHook", HostContext.class);
        generic.setAccessible(true);
        setter = HookHelpers.findMethodExact(TextView.class, "setText", CharSequence.class);
        inspector = HookHelpers.findMethodExact(Activity.class, "onPostResume");
    }

    @After public void tearDown() throws Exception {
        framework.reset(); resetRuntimeFixture();
        Field pkg = FeatureGuard.class.getDeclaredField("sCurrentPackage"); pkg.setAccessible(true); pkg.set(null, previousPackage);
    }

    private static void resetRuntimeFixture() throws Exception {
        // Reset only this recording backend's lifecycle; do not weaken any production readiness condition.
        for (String name : new String[]{"sInstalled", "sInstallAttempted", "sObserversReady"}) {
            Field field = CustomRuleEngine.class.getDeclaredField(name); field.setAccessible(true); field.setBoolean(null, false);
        }
        Field posted = CustomRuleEngine.class.getDeclaredField("sRefreshPosted"); posted.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) posted.get(null)).set(false);
        Field inspectorInstalled = VisualInspector.class.getDeclaredField("sInstalled"); inspectorInstalled.setAccessible(true); inspectorInstalled.setBoolean(null, false);
    }

    private void invokeGeneric() throws Throwable {
        FeatureGuard.withRegistrationOwner(PKG, null, () -> HookBridge.withRegistrationScope(host, () -> {
            try { generic.invoke(null, host); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }));
    }

    @Test public void genericEngineFailureCannotSkipIndependentInspectorRegistration() throws Throwable {
        framework.failOnce(setter);
        assertThrows(IllegalStateException.class, this::invokeGeneric);
        assertEquals(0, framework.installCount(setter)); assertEquals(1, framework.installCount(inspector));
        invokeGeneric(); assertEquals(1, framework.installCount(setter)); assertEquals(1, framework.installCount(inspector));
    }
}
