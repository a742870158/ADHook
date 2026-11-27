package com.adhook.hook.support;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SplashActivityCompletionTest {
    @Test public void routesOnlyAfterCreateAndThenFinishes() {
        RecordingActivity activity = create();
        assertTrue(activity.created);
        assertTrue(SplashActivityCompletion.finishAfterCreate(activity, getClass().getClassLoader(), MainActivity.class.getName()));
        assertTrue(activity.isFinishing());
        assertNotNull(activity.launched);
        assertEquals(0, activity.launched.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Test public void routeFailureKeepsActivityAndOriginalLifecycle() {
        RecordingActivity activity = create();
        activity.rejectRoute = true;
        assertFalse(SplashActivityCompletion.finishAfterCreate(activity, getClass().getClassLoader(), MainActivity.class.getName()));
        assertTrue(activity.created);
        assertFalse(activity.isFinishing());
    }

    @Test public void missingMainClassDoesNotBlindlyFinish() {
        RecordingActivity activity = create();
        assertFalse(SplashActivityCompletion.finishAfterCreate(activity, getClass().getClassLoader(), "com.adhook.hook.support.MissingMain"));
        assertFalse(activity.isFinishing());
    }

    @Test public void rootWithoutKnownRouteIsLeftToHost() {
        RecordingActivity activity = create();
        activity.taskRoot = true;
        assertFalse(SplashActivityCompletion.finishAfterCreate(activity, getClass().getClassLoader(), null));
        assertFalse(activity.isFinishing());
    }

    @Test public void nonRootSplashMayReturnToExistingTask() {
        RecordingActivity activity = create();
        activity.taskRoot = false;
        assertTrue(SplashActivityCompletion.finishAfterCreate(activity, getClass().getClassLoader(), null));
        assertTrue(activity.isFinishing());
    }

    @Test public void alreadyFinishedActivityIsNotRerouted() {
        RecordingActivity activity = create();
        activity.finish();
        assertFalse(SplashActivityCompletion.finishAfterCreate(activity, getClass().getClassLoader(), MainActivity.class.getName()));
        assertNull(activity.launched);
    }

    @Test public void unknownTaskIdentityNeverEscapesOrClosesTheActivity() {
        RecordingActivity activity = create(); activity.unknownRoot = true;
        assertFalse(SplashActivityCompletion.finishAfterCreate(activity, null, null));
        assertFalse(activity.isFinishing()); assertNull(activity.launched);
    }

    @Test public void transitionFailureStillReportsCompletedFinish() {
        RecordingActivity activity = create(); activity.taskRoot = false; activity.throwTransition = true;
        assertTrue(SplashActivityCompletion.finishAfterCreate(activity, null, null));
        assertTrue(activity.isFinishing());
    }

    @Test public void ignoredFinishIsNotACompletedExit() {
        RecordingActivity activity = Robolectric.buildActivity(NoOpFinishActivity.class).create().get();
        activity.taskRoot = false;
        assertFalse(SplashActivityCompletion.finishAfterCreate(activity, null, null));
        assertFalse(activity.isFinishing());
    }

    private RecordingActivity create() { return Robolectric.buildActivity(RecordingActivity.class).create().get(); }

    public static class RecordingActivity extends Activity {
        boolean created;
        boolean taskRoot = true;
        boolean rejectRoute, unknownRoot, throwTransition;
        Intent launched;
        @Override public void onCreate(Bundle state) { super.onCreate(state); created = true; }
        @Override public boolean isTaskRoot() {
            if (unknownRoot) throw new IllegalStateException("task identity unavailable");
            return taskRoot;
        }
        @Override public void overridePendingTransition(int enter, int exit) {
            if (throwTransition) throw new IllegalStateException("transition unavailable");
            super.overridePendingTransition(enter, exit);
        }
        @Override public String getPackageName() { return "com.adhook.hook.support"; }
        @Override public void startActivity(Intent intent) {
            if (rejectRoute) throw new ActivityNotFoundException("fixture: route unavailable");
            launched = intent;
        }
    }
    public static class NoOpFinishActivity extends RecordingActivity {
        @Override public void finish() { }
    }
    public static class MainActivity extends Activity {}
}
