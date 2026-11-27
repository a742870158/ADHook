package com.adhook.hook.support;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import com.adhook.core.ViewListenerRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class ViewListenerRegistryTest {
    @Test public void repeatedResumeRegistersOneListenerAndCoalescesScans() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        CountingView view = new CountingView(activity);
        activity.setContentView(view);
        idle();
        AtomicInteger calls = new AtomicInteger();
        assertTrue(ViewListenerRegistry.register(view, "home", ignored -> calls.incrementAndGet()));
        for (int i = 0; i < 30; i++) {
            assertFalse(ViewListenerRegistry.register(view, "home", ignored -> calls.addAndGet(100)));
        }
        assertEquals(1, view.layoutAdds);
        assertEquals(1, view.attachAdds);
        idle();
        assertEquals(1, calls.get());
        for (int i = 0; i < 30; i++) view.listener.onLayoutChange(view, 0, 0, i, i, 0, 0, 0, 0);
        idle();
        assertEquals(2, calls.get());
        ViewListenerRegistry.unregister(view, "home");
    }

    @Test public void detachReleasesListenersAndCancelsQueuedScanThenAllowsRegisterAgain() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        CountingView view = new CountingView(activity);
        activity.setContentView(view);
        idle();
        AtomicInteger calls = new AtomicInteger();
        ViewListenerRegistry.register(view, "mine", ignored -> calls.incrementAndGet());
        activity.setContentView(new FrameLayout(activity));
        assertEquals(1, view.layoutRemoves);
        assertEquals(1, view.attachRemoves);
        idle();
        assertEquals(0, calls.get());
        activity.setContentView(view);
        idle();
        assertTrue(ViewListenerRegistry.register(view, "mine", ignored -> calls.incrementAndGet()));
        idle();
        assertEquals(1, calls.get());
        assertEquals(2, view.layoutAdds);
        ViewListenerRegistry.unregister(view, "mine");
    }

    @Test public void differentViewsAndFeatureKeysRemainIndependent() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        FrameLayout root = new FrameLayout(activity);
        CountingView first = new CountingView(activity);
        CountingView second = new CountingView(activity);
        root.addView(first);
        root.addView(second);
        activity.setContentView(root);
        idle();
        AtomicInteger calls = new AtomicInteger();
        assertTrue(ViewListenerRegistry.register(first, "home", ignored -> calls.incrementAndGet()));
        assertTrue(ViewListenerRegistry.register(first, "mine", ignored -> calls.incrementAndGet()));
        assertTrue(ViewListenerRegistry.register(second, "home", ignored -> calls.incrementAndGet()));
        idle();
        assertEquals(3, calls.get());
        ViewListenerRegistry.unregister(first, "home");
        ViewListenerRegistry.unregister(first, "mine");
        ViewListenerRegistry.unregister(second, "home");
    }

    private static void idle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); }

    private static class CountingView extends FrameLayout {
        int layoutAdds, attachAdds, layoutRemoves, attachRemoves;
        View.OnLayoutChangeListener listener;
        CountingView(Context context) { super(context); }
        @Override public void addOnLayoutChangeListener(View.OnLayoutChangeListener value) {
            layoutAdds++; listener = value; super.addOnLayoutChangeListener(value);
        }
        @Override public void removeOnLayoutChangeListener(View.OnLayoutChangeListener value) {
            layoutRemoves++; super.removeOnLayoutChangeListener(value);
        }
        @Override public void addOnAttachStateChangeListener(View.OnAttachStateChangeListener value) {
            attachAdds++; super.addOnAttachStateChangeListener(value);
        }
        @Override public void removeOnAttachStateChangeListener(View.OnAttachStateChangeListener value) {
            attachRemoves++; super.removeOnAttachStateChangeListener(value);
        }
    }
}
