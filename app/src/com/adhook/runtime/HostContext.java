package com.adhook.runtime;

import android.app.Application;
import android.content.pm.ApplicationInfo;

/** Immutable framework-independent host identity; no Unsafe or legacy callback objects. */
public final class HostContext {
    public final String packageName;
    public final String processName;
    public final ClassLoader classLoader;
    public final ApplicationInfo appInfo;
    public final boolean isFirstApplication;

    public HostContext(String packageName, String processName, ClassLoader classLoader,
                       ApplicationInfo appInfo, boolean isFirstApplication) {
        if (packageName == null || packageName.isEmpty()) throw new IllegalArgumentException("Host package required");
        this.packageName = packageName;
        this.processName = processName == null ? packageName : processName;
        this.classLoader = classLoader;
        this.appInfo = appInfo;
        this.isFirstApplication = isFirstApplication;
    }

    /** Bootstrap lookup only: no Binder and no dependency on android.app.AndroidAppHelper stubs. */
    public static Application currentApplication() {
        try {
            Class<?> thread = HookHelpers.findClass("android.app.ActivityThread", null);
            Object app = HookHelpers.callStaticMethod(thread, "currentApplication");
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable unavailable) { return null; }
    }
}
