package com.adhook.engine;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.adhook.config.model.CustomRule;
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
import static org.junit.Assert.*;

/** Actual observer callbacks run through the API101 recorder over real Android setter/bind methods. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class ObserverReadinessTest extends EngineTestSupport {
    private NativeApi101TestFramework framework;
    @Before public void setUpNative() throws Exception {
        framework = new NativeApi101TestFramework(); framework.reset(); framework.initialize(); resetReadiness();
    }
    @After public void tearDownNative() throws Exception { framework.reset(); resetReadiness(); }
    private void resetReadiness() throws Exception {
        // A new fixture lifetime must not inherit the previous native backend's registration state.
        for (String name : new String[]{"sInstalled", "sInstallAttempted", "sObserversReady"}) setStatic(CustomRuleEngine.class, name, false);
    }
    private void install() throws Throwable {
        HostContext host = new HostContext(PACKAGE, PACKAGE, getClass().getClassLoader(), null, true);
        HookBridge.withRegistrationScope(host, () -> CustomRuleEngine.install(host.classLoader, PACKAGE));
    }
    private void addRule() throws Exception { app.addCustomRule(new CustomRule("ad", CustomRule.TYPE_TEXT, "广告", CustomRule.ACTION_COLLAPSE, 1)); publish(); }
    private TextView label(FrameLayout row) {
        TextView text = new TextView(activity); row.addView(text, new FrameLayout.LayoutParams(200, 44)); return text;
    }

    @Test public void registeredTextObserverCannotCollapseWhileBindingHookIsIncompleteThenRecovers() throws Throwable {
        addRule(); FrameLayout row = row(); TextView text = label(row);
        java.lang.reflect.Method obtain = HookHelpers.findMethodExact(AbsListView.class, "obtainView", int.class, boolean[].class);
        java.lang.reflect.Method setter = HookHelpers.findMethodExact(TextView.class, "setText", CharSequence.class);
        framework.failOnce(obtain);
        assertThrows(IllegalStateException.class, this::install);
        assertFalse(CustomRuleEngine.canApplyObservedRules());
        framework.call(setter, text, "广告A");
        assertEquals(View.VISIBLE, row.getVisibility());
        CustomRuleEngine.notifyRulesChanged(); drain();
        assertEquals("Queued refresh is cleanup-only before complete observation", View.VISIBLE, row.getVisibility());
        install(); assertTrue(CustomRuleEngine.canApplyObservedRules());
        framework.call(setter, text, "广告B");
        assertEquals(View.GONE, row.getVisibility());
        assertEquals(1, framework.installCount(setter)); assertEquals(1, framework.installCount(obtain));
    }

    @Test public void installedAfterBindCallbackIsCleanupOnlyUntilAllRequiredObserversAreReady() throws Throwable {
        addRule();
        FrameLayout item = new FrameLayout(activity); item.setLayoutParams(new ViewGroup.LayoutParams(240, 120)); TextView text = label(item);
        ListView list = new ListView(activity);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return 1; }
            @Override public Object getItem(int position) { return "广告"; }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) { text.setText("广告"); return item; }
        });
        java.lang.reflect.Method id = HookHelpers.findMethodExact(View.class, "setId", int.class);
        java.lang.reflect.Method obtain = HookHelpers.findMethodExact(AbsListView.class, "obtainView", int.class, boolean[].class);
        framework.failOnce(id); assertThrows(IllegalStateException.class, this::install);
        assertSame(item, framework.call(obtain, list, 0, new boolean[1]));
        assertFalse(ReusableViewHooks.isBinding()); assertEquals(View.VISIBLE, item.getVisibility());
        install();
        assertSame(item, framework.call(obtain, list, 0, new boolean[1]));
        assertFalse(ReusableViewHooks.isBinding()); assertEquals(View.GONE, item.getVisibility());
        ReusableViewHooks.releaseRecycled(item); assertEquals(View.VISIBLE, item.getVisibility());
    }

    private static final IllegalStateException ORIGINAL = new IllegalStateException("original setter failed");
    public static class ThrowingText extends TextView {
        boolean armed;
        public ThrowingText(Context context) { super(context); }
        @Override public void setText(CharSequence text, BufferType type) {
            super.setText(text, type); if (armed) throw ORIGINAL;
        }
    }
    @Test public void originalSetterThrowableIsPreservedAndAfterObserverCannotAddCollapse() throws Throwable {
        addRule(); FrameLayout row = row(); ThrowingText text = new ThrowingText(activity);
        row.addView(text, new FrameLayout.LayoutParams(200, 44)); install(); text.armed = true;
        java.lang.reflect.Method setter = HookHelpers.findMethodExact(TextView.class, "setText", CharSequence.class);
        Throwable failure = assertThrows(Throwable.class, () -> framework.call(setter, text, "广告"));
        assertSame(ORIGINAL, failure); assertEquals("广告", text.getText().toString());
        assertEquals(View.VISIBLE, row.getVisibility());
    }
}
