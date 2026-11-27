package com.adhook.engine;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.adhook.config.model.CustomRule;
import com.adhook.core.FeatureGuard;
import com.adhook.core.ViewCollapser;
import com.adhook.runtime.HookBridge;
import com.adhook.runtime.HookHelpers;
import com.adhook.runtime.HostContext;
import com.adhook.runtime.NativeApi101TestFramework;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class CustomRuleTraversalTest extends EngineTestSupport {
    private LongSupplier previousClock;
    @Before public void deterministicWorkSlices() { previousClock = RuleTreeTraversal.clock; RuleTreeTraversal.clock = () -> 0L; }
    @After public void restoreClock() { RuleTreeTraversal.cancelAll(); RuleTreeTraversal.clock = previousClock; }
    private FrameLayout tree(int count) {
        FrameLayout tree = new FrameLayout(activity);
        tree.setLayoutParams(new FrameLayout.LayoutParams(300, 300));
        for (int i = 0; i < count; i++) {
            TextView label = new TextView(activity); label.setText("广告 " + i);
            tree.addView(label, new FrameLayout.LayoutParams(200, 40));
        }
        return tree;
    }
    private void activate() throws Exception {
        app.addCustomRule(new CustomRule("ad", CustomRule.TYPE_TEXT, "广告", CustomRule.ACTION_COLLAPSE));
        publish();
    }
    private int hidden(FrameLayout tree) {
        int count = 0;
        for (int i = 0; i < tree.getChildCount(); i++) if (tree.getChildAt(i).getVisibility() == View.GONE) count++;
        return count;
    }
    private void finishFrames() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS); }

    @Test public void largeTreeYieldsAndRepeatedRequestsCoalesceWithoutLosingTheTail() throws Exception {
        FrameLayout tree = tree(200); activate();
        CustomRuleEngine.applyToSubtree(PACKAGE, tree);
        int first = hidden(tree);
        assertTrue(first > 0 && first < 200); assertEquals(1, RuleTreeTraversal.pendingCount());
        CustomRuleEngine.applyToSubtree(PACKAGE, tree);
        assertEquals(first, hidden(tree)); assertEquals(1, RuleTreeTraversal.pendingCount());
        finishFrames(); assertEquals(200, hidden(tree)); assertEquals(0, RuleTreeTraversal.pendingCount());
    }

    @Test public void reuseCancelsOldTailAndReleasesOnlyItsRuleOwners() throws Exception {
        FrameLayout tree = tree(200); activate();
        Object other = new Object(); View retained = tree.getChildAt(0);
        ViewCollapser.preventExpansion(retained, other);
        CustomRuleEngine.applyToSubtree(PACKAGE, tree);
        CustomRuleEngine.releaseForReuse(tree);
        finishFrames(); assertEquals(1, hidden(tree));
        ViewCollapser.restore(retained, other); assertEquals(0, hidden(tree));
    }

    @Test public void configurationChangeCancelsTheTailAndReevaluatesDetachedSourcesByRoot() throws Exception {
        FrameLayout tree = tree(200); activate();
        CustomRuleEngine.applyToSubtree(PACKAGE, tree);
        assertTrue(hidden(tree) > 0);
        app.getCustomRules().get(0).setTargetValue("normal"); publish(); finishFrames();
        assertEquals(0, hidden(tree)); assertEquals(0, RuleTreeTraversal.pendingCount());
    }

    @Test public void nodeLimitStopsInsteadOfSchedulingAnUnboundedTail() throws Exception {
        FrameLayout tree = tree(RuleTreeTraversal.MAX_NODES + 100); activate();
        CustomRuleEngine.applyToSubtree(PACKAGE, tree);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS);
        assertEquals(RuleTreeTraversal.MAX_NODES - 1, hidden(tree));
        assertEquals(0, RuleTreeTraversal.pendingCount());
    }

    @Test public void unknownDeepSubtreeIsNotTraversedBeyondTheBudget() throws Exception {
        FrameLayout tree = tree(0), cursor = tree;
        for (int i = 0; i < RuleTreeTraversal.MAX_DEPTH + 5; i++) {
            FrameLayout next = new FrameLayout(activity); cursor.addView(next); cursor = next;
        }
        TextView tail = new TextView(activity); tail.setText("广告"); cursor.addView(tail);
        activate(); CustomRuleEngine.applyToSubtree(PACKAGE, tree); finishFrames();
        assertEquals(View.VISIBLE, tail.getVisibility()); assertEquals(0, RuleTreeTraversal.pendingCount());
    }

    @Test public void productionPauseAndResumeObserversCancelAndReopenTraversal() throws Throwable {
        FrameLayout tree = tree(150); activate();
        NativeApi101TestFramework framework = new NativeApi101TestFramework(); framework.reset(); framework.initialize();
        boolean installed = (boolean) getStatic(CustomRuleEngine.class, "sInstalled");
        setStatic(CustomRuleEngine.class, "sInstalled", false);
        try {
            HostContext host = new HostContext(PACKAGE, PACKAGE, getClass().getClassLoader(), null, true);
            FeatureGuard.withRegistrationOwner(PACKAGE, null, () -> HookBridge.withRegistrationScope(host,
                    () -> CustomRuleEngine.install(host.classLoader, PACKAGE)));
            drain(); CustomRuleEngine.applyToSubtree(PACKAGE, tree); int first = hidden(tree);
            assertTrue(first > 0 && first < 150);
            framework.call(HookHelpers.findMethodExact(Activity.class, "onPause"), activity);
            assertEquals(0, RuleTreeTraversal.pendingCount());
            CustomRuleEngine.applyToSubtree(PACKAGE, tree); finishFrames(); assertEquals(first, hidden(tree));
            framework.call(HookHelpers.findMethodExact(Activity.class, "onResume"), activity);
            CustomRuleEngine.applyToSubtree(PACKAGE, tree); finishFrames(); assertEquals(150, hidden(tree));
        } finally {
            framework.reset(); setStatic(CustomRuleEngine.class, "sInstalled", installed);
            setStatic(CustomRuleEngine.class, "sInstallAttempted", false);
            setStatic(CustomRuleEngine.class, "sObserversReady", false);
        }
    }
}
