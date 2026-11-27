package com.adhook.core;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class ViewCollapserTest {
    private final List<View> views = new ArrayList<>();
    private final List<Object> owners = new ArrayList<>();
    private boolean originalHookInstalled;

    @Before public void setUp() throws Exception {
        Field installed = ViewCollapser.class.getDeclaredField("visibilityHookInstalled");
        installed.setAccessible(true);
        originalHookInstalled = installed.getBoolean(null);
        // Robolectric has no LSPosed native bridge. Exercise the hook's shared predicate instead.
        installed.setBoolean(null, true);
    }

    @After public void tearDown() throws Exception {
        for (View view : views) {
            for (Object owner : owners) ViewCollapser.reset(view, owner);
            ViewCollapser.reset(view);
        }
        Field installed = ViewCollapser.class.getDeclaredField("visibilityHookInstalled");
        installed.setAccessible(true);
        installed.setBoolean(null, originalHookInstalled);
    }

    @Test public void restoresOriginalVisibilityDimensionsMarginsAndMinima() {
        View view = viewWithLayout();
        view.setVisibility(View.INVISIBLE);
        Object owner = owner();
        ViewCollapser.preventExpansion(view, owner);
        assertEquals(View.GONE, view.getVisibility());
        assertEquals(0, view.getLayoutParams().height);
        assertEquals(0, view.getMinimumHeight());
        assertTrue(ViewCollapser.isSuppressed(view));

        ViewCollapser.reset(view, owner);
        assertEquals(View.INVISIBLE, view.getVisibility());
        assertOriginalLayout(view);
        assertFalse(ViewCollapser.isSuppressed(view));
    }

    @Test public void overlappingOwnersAreIndependentAndComparedByIdentity() {
        View view = viewWithLayout();
        Object first = new String("equal-value");
        Object second = new String("equal-value");
        owners.add(first);
        owners.add(second);
        ViewCollapser.preventExpansion(view, first);
        ViewCollapser.preventExpansion(view, second);
        ViewCollapser.reset(view, first);
        assertEquals(View.GONE, view.getVisibility());
        assertTrue(ViewCollapser.isSuppressed(view));
        ViewCollapser.restore(view, second);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertOriginalLayout(view);
        assertFalse(ViewCollapser.isSuppressed(view));
    }

    @Test public void releasingRulesDoesNotReleaseNativeLegacyCollapse() {
        View view = viewWithLayout();
        Object ruleOwner = owner();
        ViewCollapser.preventExpansion(view);
        ViewCollapser.preventExpansion(view, ruleOwner);
        ViewCollapser.reset(view, ruleOwner);
        assertEquals(View.GONE, view.getVisibility());
        assertTrue(ViewCollapser.isSuppressed(view));
        ViewCollapser.reset(view);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertOriginalLayout(view);
    }

    @Test public void legacyResetAndRestoreDoNotReleaseRuleOwners() {
        View view = viewWithLayout();
        Object ruleOwner = owner();
        ViewCollapser.collapse(view, ruleOwner);
        ViewCollapser.collapse(view);
        ViewCollapser.reset(view);
        ViewCollapser.restore(view);
        assertTrue(ViewCollapser.isSuppressed(view));
        assertEquals(View.GONE, view.getVisibility());
        ViewCollapser.reset(view, ruleOwner);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertOriginalLayout(view);
    }

    @Test public void ownerResetOnUnownedViewIsStrictNoOp() {
        View view = viewWithLayout();
        view.getLayoutParams().height = 0;
        view.setVisibility(View.GONE);
        ViewCollapser.reset(view, owner());
        assertEquals(0, view.getLayoutParams().height);
        assertEquals(View.GONE, view.getVisibility());
        // Existing native integrations keep their historical untracked fallback.
        ViewCollapser.reset(view);
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, view.getLayoutParams().height);
        assertEquals(View.VISIBLE, view.getVisibility());
    }

    @Test public void noLayoutParamsStillHasRestorableVisibilityAndMinimumHeight() {
        View view = new View(RuntimeEnvironment.getApplication());
        views.add(view);
        view.setVisibility(View.INVISIBLE);
        view.setMinimumHeight(19);
        Object owner = owner();
        ViewCollapser.collapse(view, owner);
        assertTrue(ViewCollapser.isSuppressed(view));
        ViewCollapser.reset(view, owner);
        assertNull(view.getLayoutParams());
        assertEquals(View.INVISIBLE, view.getVisibility());
        assertEquals(19, view.getMinimumHeight());
    }

    @Test public void originallyGoneIsNotMadeVisibleAndRepeatedCollapseIsIdempotent() {
        CountingView view = new CountingView(RuntimeEnvironment.getApplication());
        views.add(view);
        view.setLayoutParams(new ViewGroup.LayoutParams(100, 80));
        view.setVisibility(View.GONE);
        Object owner = owner();
        ViewCollapser.collapse(view, owner);
        int calls = view.layoutWrites;
        ViewCollapser.collapse(view, owner);
        ViewCollapser.preventExpansion(view, owner);
        assertEquals(calls, view.layoutWrites);
        ViewCollapser.reset(view, owner);
        assertEquals(View.GONE, view.getVisibility());
        assertEquals(80, view.getLayoutParams().height);
    }

    @Test public void layoutGuardNeverAcquiresAnAccidentalLegacyOwner() {
        View view = viewWithLayout();
        view.layout(0, 0, 240, 120);
        Object owner = owner();
        ViewCollapser.preventExpansion(view, owner);
        view.getLayoutParams().height = 180;
        view.setVisibility(View.VISIBLE); // Native hook is intentionally absent in Robolectric.
        view.layout(0, 0, 240, 180);
        assertEquals(View.GONE, view.getVisibility());
        assertEquals(0, view.getLayoutParams().height);
        ViewCollapser.reset(view, owner);
        assertFalse(ViewCollapser.isSuppressed(view));
        assertOriginalLayout(view);
        assertEquals(View.VISIBLE, view.getVisibility());
    }

    @Test public void stateDoesNotRetainViewOrLayoutParamsAndCollectedOwnerIsReleased() throws Exception {
        View view = viewWithLayout();
        Object owner = owner();
        ViewCollapser.preventExpansion(view, owner);
        Field statesField = ViewCollapser.class.getDeclaredField("SAVED_STATES");
        statesField.setAccessible(true);
        Object state = ((Map<?, ?>) statesField.get(null)).get(view);
        assertNotNull(state);
        for (Field field : state.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(state);
            assertFalse("State must not retain its weak View key", value instanceof View);
            assertFalse("RecyclerView params may point back to the ViewHolder", value instanceof ViewGroup.LayoutParams);
        }
        Field ownersField = state.getClass().getDeclaredField("owners");
        ownersField.setAccessible(true);
        Object storedOwner = ((List<?>) ownersField.get(state)).get(0);
        assertTrue(storedOwner instanceof WeakReference<?>);
        // Deterministically simulate a collected owner; no flaky System.gc() expectation.
        ((WeakReference<?>) storedOwner).clear();
        assertFalse(ViewCollapser.isSuppressed(view));
        assertEquals(View.VISIBLE, view.getVisibility());
        assertOriginalLayout(view);
    }

    @Test public void collapseOnceReportsOnlyANewEffectiveSuppression() {
        View view = viewWithLayout();
        Object first = owner();
        Object second = owner();
        assertTrue(ViewCollapser.collapseOnce(view, first));
        assertFalse(ViewCollapser.collapseOnce(view, first));
        assertFalse(ViewCollapser.collapseOnce(view, second));
        ViewCollapser.restore(view, first);
        assertEquals(View.GONE, view.getVisibility());
        ViewCollapser.restore(view, second);
        assertTrue(ViewCollapser.collapseOnce(view, first));
    }

    @Test public void alreadyGoneAndNullViewsNeverReportAnEffectiveCollapse() {
        View view = viewWithLayout();
        view.setVisibility(View.GONE);
        assertFalse(ViewCollapser.collapseOnce(view, owner()));
        assertFalse(ViewCollapser.collapseOnce(null, owner()));
        assertFalse(ViewCollapser.collapseOnce(view, null));
    }

    private Object owner() {
        Object result = new Object();
        owners.add(result);
        return result;
    }

    private View viewWithLayout() {
        View view = new View(RuntimeEnvironment.getApplication());
        ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(240, 120);
        params.setMargins(3, 7, 5, 11);
        view.setLayoutParams(params);
        view.setMinimumHeight(23);
        view.setMinimumWidth(29);
        views.add(view);
        return view;
    }

    private void assertOriginalLayout(View view) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        assertEquals(120, params.height);
        assertEquals(240, params.width);
        assertEquals(3, params.leftMargin);
        assertEquals(7, params.topMargin);
        assertEquals(5, params.rightMargin);
        assertEquals(11, params.bottomMargin);
        assertEquals(23, view.getMinimumHeight());
        assertEquals(29, view.getMinimumWidth());
    }

    private static final class CountingView extends View {
        int layoutWrites;
        CountingView(Context context) { super(context); }
        @Override public void setLayoutParams(ViewGroup.LayoutParams params) {
            layoutWrites++;
            super.setLayoutParams(params);
        }
    }
}
