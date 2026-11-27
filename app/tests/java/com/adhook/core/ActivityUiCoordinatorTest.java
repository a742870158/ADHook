package com.adhook.core;

import android.app.Activity;
import android.os.Looper;

import com.adhook.config.ConfigClient;

import org.junit.Before;
import org.junit.Test;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, shadows = ActivityUiCoordinatorTest.Client.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ActivityUiCoordinatorTest {
    @Implements(value = ConfigClient.class, isInAndroidSdk = false)
    public static class Client {
        static Boolean enabled;
        @Implementation protected static boolean isHookEnabledCached(String pkg) {
            return Boolean.TRUE.equals(enabled);
        }
    }

    @Before public void enableHook() { Client.enabled = true; }

    @Test public void queuedUiWorkRechecksOffAndUnknownWithoutStoppingObservation() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            ActivityUiCoordinator.Registry registry = new ActivityUiCoordinator.Registry(activity.getPackageName());
            registry.activate();
            AtomicInteger calls = new AtomicInteger();
            registry.register("ui", Activity.class, (a, decor) -> calls.incrementAndGet());
            shadowOf(Looper.getMainLooper()).idle();
            registry.resumed(activity);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, calls.get());
            Client.enabled = false;
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertEquals(1, calls.get());
            Client.enabled = null;
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(1, calls.get());
            registry.inactive(activity);
            Client.enabled = true;
            registry.resumed(activity);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
            assertEquals(5, calls.get());
        }
    }

    @Test public void lateRegistrationCatchesCurrentActivityAndHasBoundedRetries() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            ActivityUiCoordinator.Registry registry = new ActivityUiCoordinator.Registry(activity.getPackageName());
            registry.activate();
            AtomicInteger calls = new AtomicInteger();
            registry.resumed(activity);
            registry.register("ui", Activity.class, (a, decor) -> calls.incrementAndGet());
            registry.resumed(activity); // Duplicate snapshot/lifecycle cannot enqueue a second round.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
            assertEquals(4, calls.get());
        }
    }

    @Test public void pauseCancelsOldRetriesAndNewResumeStartsOneNewRound() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            ActivityUiCoordinator.Registry registry = new ActivityUiCoordinator.Registry(activity.getPackageName());
            registry.activate();
            AtomicInteger calls = new AtomicInteger();
            registry.register("ui", Activity.class, (a, decor) -> calls.incrementAndGet());
            shadowOf(Looper.getMainLooper()).idle();
            registry.resumed(activity);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, calls.get());
            registry.inactive(activity);
            registry.resumed(activity);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
            assertEquals(5, calls.get());
        }
    }

    @Test public void incompleteLifecycleObservationDoesNotEnableUiTasks() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            ActivityUiCoordinator.Registry registry = new ActivityUiCoordinator.Registry(activity.getPackageName());
            AtomicInteger calls = new AtomicInteger();
            registry.register("ui", Activity.class, (a, decor) -> calls.incrementAndGet());
            registry.resumed(activity);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
            assertEquals(0, calls.get());
            registry.activate();
            registry.resumed(activity);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
            assertEquals(4, calls.get());
        }
    }

    @Test public void foreignPackageAndDestroyedActivitiesAreNotProcessed() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get();
        AtomicInteger calls = new AtomicInteger();
        ActivityUiCoordinator.Registry foreign = new ActivityUiCoordinator.Registry("com.other.app");
        foreign.activate();
        foreign.register("ui", Activity.class, (a, decor) -> calls.incrementAndGet());
        foreign.resumed(activity);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertEquals(0, calls.get());
        ActivityUiCoordinator.Registry registry = new ActivityUiCoordinator.Registry(activity.getPackageName());
            registry.activate();
        registry.register("ui", Activity.class, (a, decor) -> calls.incrementAndGet());
        registry.resumed(activity);
        controller.pause().stop().destroy();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertEquals(0, calls.get());
    }
}
