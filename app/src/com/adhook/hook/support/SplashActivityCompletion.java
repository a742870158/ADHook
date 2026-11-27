package com.adhook.hook.support;

import android.app.Activity;
import android.content.Intent;

import com.adhook.core.AdHookLog;

/** Shared by host-specific AFTER-onCreate hooks; never replaces the Android lifecycle. */
public final class SplashActivityCompletion {
    private SplashActivityCompletion() {}

    /**
     * Only finish after a known main route was accepted, or when an existing task is underneath.
     * A task-root splash without a verified route must be left to the host.
     */
    public static boolean finishAfterCreate(Activity activity, ClassLoader loader, String mainActivity) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
            if (mainActivity != null) {
                Class<?> target = Class.forName(mainActivity, false, loader);
                if (!Activity.class.isAssignableFrom(target) || target == activity.getClass()
                        || !mainActivity.startsWith(activity.getPackageName() + ".")) {
                    return false;
                }
                Intent intent = new Intent(activity, target);
                // Stay in the current task; NEW_TASK could leave the user on an empty task.
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                activity.startActivity(intent);
            } else if (activity.isTaskRoot()) {
                AdHookLog.w("[SplashActivityCompletion] No verified main route; keeping task-root splash");
                return false;
            }
            activity.finish();
            boolean completed = activity.isFinishing() || activity.isDestroyed();
            // A cosmetic transition failure must not hide an already completed finish from accounting.
            try { activity.overridePendingTransition(0, 0); }
            catch (Throwable ignored) { }
            return completed;
        } catch (Throwable failure) {
            // In particular, never finish after a failed startActivity/class lookup.
            AdHookLog.w("[SplashActivityCompletion] Route/finish failed; leaving lifecycle intact: "
                    + failure.getClass().getSimpleName());
            return false;
        }
    }
}
