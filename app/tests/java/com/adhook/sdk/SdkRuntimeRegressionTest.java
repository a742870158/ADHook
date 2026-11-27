package com.adhook.sdk;

import android.app.Activity;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Method;

import com.adhook.runtime.HookCallback;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SdkRuntimeRegressionTest {
    @Test public void onCreateAndSuperRunBeforeTheProductionHookFinishesActivity() throws Exception {
        for (String sdk : new String[]{"MintegralBlocker", "TopOnBlocker", "SigmobBlocker"}) {
            ActivityController<TrackingActivity> controller = Robolectric.buildActivity(TrackingActivity.class);
            TrackingActivity activity = controller.get();
            HookCallback hook = SdkActivityLifecycle.finishAfterCreate(sdk);
            HookCallback.MethodHookParam param = newParam(activity);

            // A before override is forbidden: Xposed would skip onCreate/super if it sets a result.
            for (Method method : hook.getClass().getDeclaredMethods()) {
                assertNotEquals("beforeHookedMethod", method.getName());
            }
            invokeHook(hook, "beforeHookedMethod", param);
            assertFalse(activity.isFinishing());
            assertFalse(param.isReturnEarly());

            controller.create(); // Robolectric checks the real Activity super lifecycle contract.
            assertTrue(activity.createCompleted);
            invokeHook(hook, "afterHookedMethod", param);
            assertTrue(activity.isFinishing());
            assertEquals(1, activity.finishCalls);
            assertFalse(activity.finishedBeforeCreate);
            assertFalse(param.isReturnEarly());
            invokeHook(hook, "afterHookedMethod", param);
            assertEquals("already finishing Activity must not be finished again", 1, activity.finishCalls);
            controller.destroy();
        }
    }

    @Test public void rootAndUnknownTaskAreLeftIntactAfterNormalCreate() throws Exception {
        for (boolean unknown : new boolean[]{false, true}) {
            try (ActivityController<TrackingActivity> controller = Robolectric.buildActivity(TrackingActivity.class).create()) {
                TrackingActivity activity = controller.get();
                activity.taskRoot = true; activity.unknownTaskRoot = unknown;
                HookCallback.MethodHookParam param = newParam(activity);
                invokeHook(SdkActivityLifecycle.finishAfterCreate("SigmobBlocker"), "afterHookedMethod", param);
                assertTrue(activity.createCompleted);
                assertFalse(activity.isFinishing()); assertEquals(0, activity.finishCalls);
                assertFalse(param.hasThrowable()); assertFalse(param.isReturnEarly());
            }
        }
    }

    @Test public void transitionFailureDoesNotHideCompletedFinish() throws Exception {
        try (ActivityController<TrackingActivity> controller = Robolectric.buildActivity(TrackingActivity.class).create()) {
            TrackingActivity activity = controller.get(); activity.throwTransition = true;
            HookCallback.MethodHookParam param = newParam(activity);
            invokeHook(SdkActivityLifecycle.finishAfterCreate("TopOnBlocker"), "afterHookedMethod", param);
            assertTrue(activity.isFinishing()); assertEquals(1, activity.finishCalls);
            assertFalse(param.hasThrowable()); assertFalse(param.isReturnEarly());
        }
    }

    @Test public void originalOnCreateExceptionIsPreservedWithoutFinishingHalfCreatedActivity() throws Exception {
        TrackingActivity activity = Robolectric.buildActivity(TrackingActivity.class).get();
        HookCallback.MethodHookParam param = newParam(activity);
        Throwable original = new IllegalStateException("SDK onCreate failed");
        param.setThrowable(original);
        boolean originalEarlyState = param.isReturnEarly();
        invokeHook(SdkActivityLifecycle.finishAfterCreate("SigmobBlocker"), "afterHookedMethod", param);
        assertSame(original, param.getThrowable());
        assertEquals(0, activity.finishCalls);
        assertEquals("Hook must preserve the original exceptional outcome", originalEarlyState, param.isReturnEarly());
    }

    @Test public void destroyedActivitiesAreNotFinishedAgain() {
        ActivityController<TrackingActivity> controller = Robolectric.buildActivity(TrackingActivity.class)
                .create().start().resume().pause().stop().destroy();
        TrackingActivity activity = controller.get();
        assertTrue(activity.isDestroyed());
        SdkActivityLifecycle.finishCreatedActivity(activity, "TopOnBlocker");
        assertEquals(0, activity.finishCalls);
    }

    @Test public void finishFailureCannotEscapeIntoTheHostLifecycle() throws Exception {
        ActivityController<ThrowingFinishActivity> controller = Robolectric.buildActivity(ThrowingFinishActivity.class).create();
        HookCallback.MethodHookParam param = newParam(controller.get());
        invokeHook(SdkActivityLifecycle.finishAfterCreate("MintegralBlocker"), "afterHookedMethod", param);
        assertFalse(param.hasThrowable());
        assertFalse(param.isReturnEarly());
        controller.destroy();
    }

    @Test public void nonActivityTargetsAreIgnored() {
        SdkActivityLifecycle.finishCreatedActivity(null, "test");
        SdkActivityLifecycle.finishCreatedActivity(new Object(), "test");
    }

    @Test public void cachedParamAndFieldListenerReceivesOneFailureNotThree() throws Exception {
        TestAd ad = new TestAd();
        TestListener listener = new TestListener();
        ad.listener = listener;
        HookCallback.MethodHookParam param = newParam(ad);
        param.method = TestAd.class.getDeclaredMethod("loadAd", TestListener.class);
        param.args = new Object[]{listener};
        assertTrue(AdCallbackHelper.notifyCachedCallbacks(param, listener, 4001, getClass().getClassLoader()));
        assertEquals(1, listener.calls);
        assertEquals(4001, listener.lastCode);
    }

    @Test public void expiredCacheStillUsesLiveFieldOrParameterForFailure() throws Exception {
        TestAd ad = new TestAd();
        TestListener fieldListener = new TestListener();
        ad.listener = fieldListener;
        HookCallback.MethodHookParam param = newParam(ad);
        param.method = TestAd.class.getDeclaredMethod("loadAd", TestListener.class);
        param.args = new Object[]{null};
        assertTrue(AdCallbackHelper.notifyCachedCallbacks(param, null, -1, getClass().getClassLoader()));
        assertEquals(1, fieldListener.calls);

        ad.listener = null;
        TestListener paramListener = new TestListener();
        param.args = new Object[]{paramListener};
        assertTrue(AdCallbackHelper.notifyCachedCallbacks(param, null, 600000, getClass().getClassLoader()));
        assertEquals(1, paramListener.calls);
        assertEquals(600000, paramListener.lastCode);
    }

    private static HookCallback.MethodHookParam newParam(Object target) {
        HookCallback.MethodHookParam param = ReflectionHelpers.callConstructor(HookCallback.MethodHookParam.class);
        param.thisObject = target;
        param.args = new Object[0];
        return param;
    }

    private static void invokeHook(HookCallback hook, String name, HookCallback.MethodHookParam param) throws Exception {
        Method method = HookCallback.class.getDeclaredMethod(name, HookCallback.MethodHookParam.class);
        method.setAccessible(true);
        method.invoke(hook, param);
    }

    public static class TrackingActivity extends Activity {
        boolean createCompleted;
        boolean finishedBeforeCreate;
        int finishCalls;
        boolean taskRoot, unknownTaskRoot, throwTransition;

        @Override public boolean isTaskRoot() {
            if (unknownTaskRoot) throw new IllegalStateException("task root unavailable");
            return taskRoot;
        }
        @Override public void overridePendingTransition(int enter, int exit) {
            if (throwTransition) throw new IllegalStateException("vendor transition unavailable");
            super.overridePendingTransition(enter, exit);
        }

        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            createCompleted = true;
        }

        @Override public void finish() {
            finishCalls++;
            finishedBeforeCreate |= !createCompleted;
            super.finish();
        }
    }

    public static class ThrowingFinishActivity extends Activity {
        @Override public void finish() { throw new IllegalStateException("vendor finish failure"); }
    }

    private static class TestAd {
        TestListener listener;
        public void loadAd(TestListener callback) { }
    }

    private static class TestListener {
        int calls;
        int lastCode;
        public void onNoAD(int code, String reason) { calls++; lastCode = code; }
    }
}
