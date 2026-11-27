package com.adhook.engine;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.adhook.config.model.CustomRule;
import com.adhook.core.ViewCollapser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;


@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class CustomRuleEngineTest extends EngineTestSupport {
    @Test public void emptyAndDisabledRulesDoNotReadTreeChildren() throws Exception {
        CountingFrameLayout tree = new CountingFrameLayout(activity);
        root.addView(tree);
        label(tree, "广告");
        CustomRule disabled = rule("广告", 0);
        disabled.setEnabled(false);
        for (CustomRule candidate : new CustomRule[]{null, disabled}) {
            app.getCustomRules().clear();
            if (candidate != null) app.addCustomRule(candidate);
            publish();
            tree.childReads = 0;
            CustomRuleEngine.applyToSubtree(PACKAGE, tree);
            assertEquals("No actionable collapse rule should walk the View tree", 0, tree.childReads);
        }
    }

    @Test public void skippedEmptyTreeStillRespondsToLaterRulePublication() throws Exception {
        CountingFrameLayout tree = new CountingFrameLayout(activity);
        root.addView(tree);
        TextView text = label(tree, "广告");
        tree.childReads = 0;
        CustomRuleEngine.applyToSubtree(PACKAGE, tree);
        assertEquals(0, tree.childReads);
        app.addCustomRule(rule("广告", 0));
        publish();
        assertEquals(View.GONE, text.getVisibility());
        assertTrue("Known roots must still be reevaluated when rules become active", tree.childReads > 0);
    }

    @Test public void removingLastRuleSkipsTraversalWithoutReleasingNativeOwner() throws Exception {
        CountingFrameLayout tree = new CountingFrameLayout(activity);
        root.addView(tree);
        TextView text = label(tree, "广告");
        app.addCustomRule(rule("广告", 0));
        publish();
        Object nativeOwner = new Object();
        ViewCollapser.preventExpansion(text, nativeOwner);
        app.getCustomRules().clear();
        publish();
        tree.childReads = 0;
        CustomRuleEngine.applyToSubtree(PACKAGE, tree);
        assertEquals(0, tree.childReads);
        assertEquals(View.GONE, text.getVisibility());
        ViewCollapser.restore(text, nativeOwner);
        assertEquals(View.VISIBLE, text.getVisibility());
        assertEquals(44, text.getLayoutParams().height);
    }

    @Test public void remoteCollapseRulesStillTraverseWhenLocalSourceIsOff() throws Exception {
        CountingFrameLayout tree = new CountingFrameLayout(activity);
        root.addView(tree);
        TextView text = label(tree, "广告");
        CustomRule remote = rule("广告", 0);
        remote.setSource(CustomRule.SOURCE_REMOTE);
        app.addCustomRule(remote);
        global.setLocalRulesEnabled(false);
        publish();
        tree.childReads = 0;
        CustomRuleEngine.applyToSubtree(PACKAGE, tree);
        assertTrue("attached=" + tree.isAttachedToWindow() + ", window=" + tree.getWindowVisibility()
                + ", pending=" + RuleTreeTraversal.pendingCount() + ", logs=" + org.robolectric.shadows.ShadowLog.getLogs(), tree.childReads > 0);
        assertEquals(View.GONE, text.getVisibility());
    }

    @Test public void hookSwitchDisablesAndReleasesOnlyRuleLeasesThenReactivates() throws Exception {
        FrameLayout row = row();
        TextView text = label(row, "广告");
        app.addCustomRule(rule("广告", 1));
        publish();
        assertTrue(CustomRuleEngine.matchAndApply(PACKAGE, text));
        Object other = new Object();
        ViewCollapser.preventExpansion(row, other);
        app.setHookEnabled(false);
        publish();
        assertFalse(CustomRuleEngine.matchAndApply(PACKAGE, text));
        assertEquals(View.GONE, row.getVisibility());
        ViewCollapser.restore(row, other);
        assertEquals(View.VISIBLE, row.getVisibility());
        app.setHookEnabled(true);
        publish();
        assertTrue(CustomRuleEngine.matchAndApply(PACKAGE, text));
        assertEquals(View.GONE, row.getVisibility());
    }

    @Test public void unrelatedChildMissCannotRestoreAnotherSourcesTarget() throws Exception {
        FrameLayout row = row();
        TextView ad = label(row, "广告A");
        TextView ordinary = label(row, "正常内容");
        ordinary.setVisibility(View.INVISIBLE);
        app.addCustomRule(rule("广告", 1));
        publish();
        assertTrue(CustomRuleEngine.matchAndApply(PACKAGE, ad));
        assertFalse(CustomRuleEngine.matchAndApply(PACKAGE, ordinary));
        assertEquals(View.GONE, row.getVisibility());
        assertEquals(View.INVISIBLE, ordinary.getVisibility());
        ad.setText("正常B");
        assertFalse(CustomRuleEngine.matchAndApply(PACKAGE, ad));
        assertEquals(View.VISIBLE, row.getVisibility());
        assertEquals(120, row.getLayoutParams().height);
        assertEquals(23, row.getMinimumHeight());
    }

    @Test public void recyclerTransactionAdAToNormalBToAdCReleasesBeforeHostSetVisibility() throws Exception {
        FrameLayout row = row();
        TextView text = label(row, "");
        TextView deliberatelyHidden = label(row, "内部占位");
        deliberatelyHidden.setVisibility(View.GONE);
        deliberatelyHidden.getLayoutParams().height = 0;
        app.addCustomRule(rule("广告", CustomRule.PARENT_DEPTH_AUTO_ITEM));
        publish();

        // These are the exact bridge boundaries used by Adapter.bindViewHolder; Robolectric
        // intentionally does not pretend to run a native Xposed method interception.
        ReusableViewHooks.beginBinding(row);
        text.setText("广告A");
        text.setContentDescription("广告语义");
        assertFalse(CustomRuleEngine.matchAndApply(PACKAGE, text));
        ReusableViewHooks.finishBinding(PACKAGE, row, false);
        assertEquals(View.GONE, row.getVisibility());
        assertEquals(0, row.getLayoutParams().height);
        assertEquals(View.VISIBLE, text.getVisibility());

        ReusableViewHooks.beginBinding(row);
        assertEquals(View.VISIBLE, row.getVisibility());
        assertEquals(120, row.getLayoutParams().height);
        row.setVisibility(View.VISIBLE); // obsolete owner must be gone BEFORE this host write
        row.getLayoutParams().height = 167;
        text.setText("正常B");
        // The old contentDescription still says ad: no mid-bind reacquisition is permitted.
        assertFalse(CustomRuleEngine.matchAndApply(PACKAGE, text));
        text.setContentDescription("正常语义");
        ReusableViewHooks.finishBinding(PACKAGE, row, false);
        assertEquals(View.VISIBLE, row.getVisibility());
        assertEquals(167, row.getLayoutParams().height);
        assertEquals(View.GONE, deliberatelyHidden.getVisibility());
        assertEquals(0, deliberatelyHidden.getLayoutParams().height);

        ReusableViewHooks.beginBinding(row);
        text.setText("广告C");
        ReusableViewHooks.finishBinding(PACKAGE, row, false);
        assertEquals(View.GONE, row.getVisibility());
        ReusableViewHooks.releaseRecycled(row);
        assertEquals(View.VISIBLE, row.getVisibility());
        assertEquals(167, row.getLayoutParams().height); // B's new layout, not A's old snapshot
    }

    @Test public void listViewScrapReleasePrecedesAdapterWrites() throws Exception {
        FrameLayout row = row();
        TextView text = label(row, "广告A");
        app.addCustomRule(rule("广告", CustomRule.PARENT_DEPTH_AUTO_ITEM));
        publish();
        ReusableViewHooks.beginBinding(row);
        ReusableViewHooks.finishBinding(PACKAGE, row, false);
        assertEquals(View.GONE, row.getVisibility());

        ReusableViewHooks.beginBinding(null); // AbsListView.obtainView begins
        ReusableViewHooks.releaseRecycled(row); // RecycleBin.getScrapView returns convertView
        assertEquals(View.VISIBLE, row.getVisibility());
        assertEquals(120, row.getLayoutParams().height);
        row.setVisibility(View.VISIBLE);
        text.setText("正常B");
        ReusableViewHooks.finishBinding(PACKAGE, row, false);
        assertEquals(View.VISIBLE, row.getVisibility());
        ReusableViewHooks.releaseRecycled(row);
        assertEquals(120, row.getLayoutParams().height);
    }

    @Test public void detachedFreshlyBoundAutoItemCollapsesTheItemNotItsLabel() throws Exception {
        FrameLayout detached = new FrameLayout(activity);
        detached.setLayoutParams(new FrameLayout.LayoutParams(220, 90));
        TextView text = label(detached, "广告");
        app.addCustomRule(rule("广告", CustomRule.PARENT_DEPTH_AUTO_ITEM));
        publish();
        ReusableViewHooks.beginBinding(detached);
        ReusableViewHooks.finishBinding(PACKAGE, detached, false);
        assertEquals(View.GONE, detached.getVisibility());
        assertEquals(View.VISIBLE, text.getVisibility());
        ReusableViewHooks.releaseRecycled(detached);
        assertEquals(90, detached.getLayoutParams().height);
    }

    @Test public void distinctSourcesSharingTargetAreReleasedIndependently() throws Exception {
        FrameLayout row = row();
        TextView first = label(row, "广告");
        TextView second = label(row, "赞助");
        CustomRule ad = rule("广告", 1);
        CustomRule sponsor = rule("赞助", 1);
        app.addCustomRule(ad);
        app.addCustomRule(sponsor);
        publish();
        CustomRuleEngine.applyToSubtree(PACKAGE, row);
        first.setText("正常");
        CustomRuleEngine.matchAndApply(PACKAGE, first);
        assertEquals(View.GONE, row.getVisibility());
        app.removeCustomRule(sponsor.getId());
        publish();
        assertEquals(View.VISIBLE, row.getVisibility());
        assertEquals(120, row.getLayoutParams().height);
        assertEquals(View.VISIBLE, second.getVisibility());
    }

    @Test public void allMatchingRulesOnOneSourceOwnTheTargetUntilLastRuleIsDisabled() throws Exception {
        FrameLayout row = row();
        TextView text = label(row, "广告和赞助");
        CustomRule first = rule("广告", 1);
        CustomRule second = rule("赞助", 1);
        app.addCustomRule(first);
        app.addCustomRule(second);
        publish();
        CustomRuleEngine.matchAndApply(PACKAGE, text);
        first.setEnabled(false);
        publish();
        assertEquals(View.GONE, row.getVisibility());
        second.setEnabled(false);
        publish();
        assertEquals(View.VISIBLE, row.getVisibility());
        assertEquals(7, ((FrameLayout.LayoutParams) row.getLayoutParams()).topMargin);
        assertEquals(11, ((FrameLayout.LayoutParams) row.getLayoutParams()).bottomMargin);
    }

    @Test public void deletingRuleNeverResurrectsNativePermanentCollapse() throws Exception {
        FrameLayout row = row();
        TextView text = label(row, "广告");
        ViewCollapser.preventExpansion(row);
        app.addCustomRule(rule("广告", 1));
        publish();
        CustomRuleEngine.matchAndApply(PACKAGE, text);
        app.getCustomRules().clear();
        publish();
        assertEquals(View.GONE, row.getVisibility());
        assertEquals(0, row.getLayoutParams().height);
        ViewCollapser.reset(row);
        assertEquals(View.VISIBLE, row.getVisibility());
        assertEquals(120, row.getLayoutParams().height);
    }

    @Test public void editingSameRuleIdReleasesOldTargetAndReevaluatesNewDepth() throws Exception {
        FrameLayout row = row();
        TextView text = label(row, "广告");
        CustomRule rule = rule("广告", 0);
        app.addCustomRule(rule);
        publish();
        CustomRuleEngine.matchAndApply(PACKAGE, text);
        assertEquals(View.GONE, text.getVisibility());
        rule.setParentDepth(1);
        publish();
        assertEquals(View.VISIBLE, text.getVisibility());
        assertEquals(44, text.getLayoutParams().height);
        assertEquals(View.GONE, row.getVisibility());
        rule.setTargetValue("不再匹配");
        publish();
        assertEquals(View.VISIBLE, row.getVisibility());
        assertEquals(120, row.getLayoutParams().height);
    }

    @Test public void localSwitchControlsPickerButRemoteSurvives() throws Exception {
        FrameLayout row = row();
        TextView picker = label(row, "本地广告");
        TextView remote = label(row, "远程广告");
        CustomRule localRule = rule("本地广告", 0);
        CustomRule remoteRule = rule("远程广告", 0);
        remoteRule.setSource(CustomRule.SOURCE_REMOTE);
        app.addCustomRule(localRule);
        app.addCustomRule(remoteRule);
        global.setLocalRulesEnabled(false);
        publish();
        CustomRuleEngine.applyToActivity(PACKAGE, activity);
        assertEquals(View.VISIBLE, picker.getVisibility());
        assertEquals(View.GONE, remote.getVisibility());
        global.setLocalRulesEnabled(true);
        publish();
        assertEquals(View.GONE, picker.getVisibility());
        global.setLocalRulesEnabled(false);
        publish();
        assertEquals(View.VISIBLE, picker.getVisibility());
        assertEquals(View.GONE, remote.getVisibility());
        remoteRule.setEnabled(false);
        publish();
        assertEquals(View.VISIBLE, remote.getVisibility());
    }

    @Test public void addingRulesAfterAnEmptySnapshotReevaluatesExistingTree() throws Exception {
        FrameLayout row = row();
        TextView text = label(row, "广告");
        assertFalse(CustomRuleEngine.matchAndApply(PACKAGE, text));
        app.addCustomRule(rule("广告", 1));
        publish();
        assertEquals(View.GONE, row.getVisibility());
        app.getCustomRules().clear();
        publish();
        assertEquals(View.VISIBLE, row.getVisibility());
    }

    @Test public void detachingSourceReleasesOnlyItsRuleLease() throws Exception {
        FrameLayout row = row();
        TextView text = label(row, "广告");
        app.addCustomRule(rule("广告", 1));
        publish();
        assertTrue(text.isAttachedToWindow());
        CustomRuleEngine.matchAndApply(PACKAGE, text);
        root.removeView(row);
        assertEquals(View.VISIBLE, row.getVisibility());
        assertEquals(120, row.getLayoutParams().height);
    }

    @Test public void preciseTextPropertiesAndActivityFilterDoNotChangeLegacyDefaults() throws Exception {
        TextView text = new TextView(activity);
        text.setText("  前缀广告后缀  ");
        text.setContentDescription("广告");
        CustomRule legacy = rule("广告", 0);
        assertTrue(matches(text, legacy));
        CustomRule exact = legacy.copy();
        exact.setTextProperty("text");
        exact.setTextMatchMode("equals");
        exact.setTrimText(false);
        assertFalse(matches(text, exact));
        text.setText(" 广告 ");
        assertFalse(matches(text, exact));
        exact.setTrimText(true);
        assertTrue(matches(text, exact));
        exact.setTextProperty("desc");
        text.setText("正常");
        assertTrue(matches(text, exact));
        exact.setTextProperty("text");
        assertFalse(matches(text, exact));
        text.setText("广告");
        exact.setActivityFilter("Activity");
        assertTrue(matches(text, exact));
        exact.setExactActivityFilter(true);
        assertFalse(matches(text, exact));
        exact.setActivityFilter("other.Activity," + activity.getClass().getName());
        assertTrue(matches(text, exact));
    }

    @Test public void exactResourceIdIncludesPackageAndHasSeparateCacheFromLegacy() throws Exception {
        ResourceView view = new ResourceView(activity);
        view.setId(0x7f001234);
        view.resources = new Resources(activity.getAssets(), activity.getResources().getDisplayMetrics(),
                activity.getResources().getConfiguration()) {
            @Override public String getResourceEntryName(int id) { return "banner"; }
            @Override public String getResourceName(int id) { return "actual.pkg:id/banner"; }
        };
        CustomRule rule = new CustomRule("id", CustomRule.TYPE_RES_ID, "other.pkg:id/banner", CustomRule.ACTION_COLLAPSE);
        assertTrue(matches(view, rule)); // historical entry-name matching remains available
        rule.setExactResourceId(true);
        assertFalse(matches(view, rule));
        rule.setTargetValue("actual.pkg:id/banner");
        assertTrue(matches(view, rule));
    }

    private CustomRule rule(String text, int depth) {
        return new CustomRule(text, CustomRule.TYPE_TEXT, text, CustomRule.ACTION_COLLAPSE, depth);
    }

    private TextView label(FrameLayout parent, String value) {
        TextView text = new TextView(activity);
        text.setText(value);
        parent.addView(text, new FrameLayout.LayoutParams(200, 44));
        return text;
    }

    private boolean matches(View view, CustomRule rule) throws Exception {
        app.getCustomRules().clear();
        app.addCustomRule(rule.copy());
        publish();
        return CustomRuleEngine.matchAndApply(PACKAGE, view);
    }

    private static final class ResourceView extends View {
        Resources resources;
        ResourceView(Context context) { super(context); }
        @Override public Resources getResources() { return resources != null ? resources : super.getResources(); }
    }

    private static final class CountingFrameLayout extends FrameLayout {
        int childReads;
        CountingFrameLayout(Context context) { super(context); }
        @Override public int getChildCount() { childReads++; return super.getChildCount(); }
        @Override public View getChildAt(int index) { childReads++; return super.getChildAt(index); }
    }
}
