package com.adhook.inspector;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import com.adhook.config.ConfigClient;
import com.adhook.config.model.AppConfig;
import com.adhook.core.ViewCollapser;
import com.adhook.sdk.HookTestShadows;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = {InspectorRuntimeGateTest.Client.class, HookTestShadows.Bridge.class})
public class InspectorRuntimeGateTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private boolean visibilityHookInstalled;

    @Implements(value = ConfigClient.class, isInAndroidSdk = false)
    public static class Client {
        static AppConfig app;
        @Implementation protected static boolean isHookEnabledCached(String pkg) {
            return app != null && app.isHookEnabled();
        }
        @Implementation protected static AppConfig getAppConfigCached(String pkg) { return app; }
        @Implementation protected static boolean isInspectorEnabledFresh(String pkg) {
            throw new AssertionError("Inspector callback must not use config transport");
        }
        @Implementation protected static AppConfig getAppConfig(String pkg) {
            throw new AssertionError("Inspector callback must not use config transport");
        }
    }

    @Before public void setUp() {
        visibilityHookInstalled = ReflectionHelpers.getStaticField(ViewCollapser.class, "visibilityHookInstalled");
        ReflectionHelpers.setStaticField(ViewCollapser.class, "visibilityHookInstalled", true);
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        Client.app = new AppConfig(activity.getPackageName());
        Client.app.setInspectorEnabled(true);
    }

    @After public void tearDown() {
        VisualInspector.dismissAll(activity);
        ReflectionHelpers.setStaticField(VisualInspector.class, "sLastScheduledActivityRef", null);
        ReflectionHelpers.setStaticField(ViewCollapser.class, "visibilityHookInstalled", visibilityHookInstalled);
        Client.app = null;
        controller.pause().stop().destroy();
    }

    @Test public void unknownConfigOrInspectorDisabledNeverAttachesOverlay() {
        String pkg = activity.getPackageName();
        Client.app = null;
        VisualInspector.scheduleAttachFab(activity, pkg, true);
        VisualInspector.startInspectMode(activity, pkg);
        assertNull(ReflectionHelpers.getStaticField(VisualInspector.class, "sCurrentOverlay"));
        assertNull(ReflectionHelpers.getStaticField(VisualInspector.class, "sCurrentFab"));
        Client.app = new AppConfig(pkg);
        Client.app.setInspectorEnabled(false);
        VisualInspector.startInspectMode(activity, pkg);
        assertNull(ReflectionHelpers.getStaticField(VisualInspector.class, "sCurrentOverlay"));
    }

    @Test public void previewExecutionIsGatedAndDismissalReleasesOnlyItsOwnLease() throws Exception {
        Class<?> overlayType = Class.forName("com.adhook.inspector.VisualInspector$InspectorOverlayView");
        Constructor<?> constructor = overlayType.getDeclaredConstructor(Activity.class, String.class);
        constructor.setAccessible(true);
        Object overlay = constructor.newInstance(activity, activity.getPackageName());
        View selected = new View(activity);
        selected.setLayoutParams(new ViewGroup.LayoutParams(100, 80));
        ReflectionHelpers.setField(overlay, "selectedView", selected);
        Method toggle = overlayType.getDeclaredMethod("toggleCollapseTest");
        toggle.setAccessible(true);
        toggle.invoke(overlay); // Hook is on by default and the inspector switch is enabled.
        assertEquals(View.GONE, selected.getVisibility());
        Object otherOwner = new Object();
        ViewCollapser.preventExpansion(selected, otherOwner);
        ReflectionHelpers.setStaticField(VisualInspector.class, "sCurrentOverlay", overlay);
        VisualInspector.dismissOverlay(activity);
        assertEquals(View.GONE, selected.getVisibility());
        ViewCollapser.restore(selected, otherOwner);
        assertEquals(View.VISIBLE, selected.getVisibility());
        assertEquals(80, selected.getLayoutParams().height);
    }
}
