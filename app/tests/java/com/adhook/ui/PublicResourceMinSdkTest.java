package com.adhook.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.view.ContextThemeWrapper;
import android.view.WindowManager;
import com.adhook.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class PublicResourceMinSdkTest {
    @Test public void supportedThemeRetainsCutoutAndTransparentSystemBarAttributes() {
        Context themed = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_ADHook);
        TypedArray attributes = themed.obtainStyledAttributes(new int[]{android.R.attr.windowLayoutInDisplayCutoutMode,
                android.R.attr.statusBarColor, android.R.attr.navigationBarColor});
        try {
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, attributes.getInt(0, -1));
            assertEquals(0, attributes.getColor(1, 1)); assertEquals(0, attributes.getColor(2, 1));
        } finally { attributes.recycle(); }
    }
}
