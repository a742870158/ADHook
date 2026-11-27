package com.adhook.sdk;

import android.app.Activity;

import com.adhook.core.AdHookLog;
import com.adhook.hook.support.SplashActivityCompletion;

import com.adhook.runtime.HookCallback;

/** SDK activities execute their complete onCreate/super before any owned, gated finish operation. */
final class SdkActivityLifecycle {
    private SdkActivityLifecycle() { }

    /** Compatibility helper for the lifecycle contract tests. Production paths supply the SDK key. */
    static HookCallback finishAfterCreate(String tag) { return finishAfterCreate(null, tag); }

    static HookCallback finishAfterCreate(final String sdk, final String tag) {
        return new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                SdkRuntime.Decision accounting = sdk == null ? null : SdkRuntime.decision(sdk);
                if (sdk != null && accounting == null) return;
                if (finishCreatedActivity(param.thisObject, tag) && accounting != null) accounting.hit();
            }
        };
    }

    /** Return true only after finish completed; an observation, duplicate or failed finish is not a hit. */
    static boolean finishCreatedActivity(Object target, String tag) {
        if (!(target instanceof Activity activity)) return false;
        // Without a verified host route, only an existing non-root task may safely be uncovered.
        if (!SplashActivityCompletion.finishAfterCreate(activity, null, null)) return false;
        AdHookLog.d("[" + tag + "] Finished SDK AdActivity: " + activity.getClass().getName());
        return true;
    }
}
