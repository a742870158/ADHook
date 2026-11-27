package com.adhook.core;

/** Package-ready callbacks include libraries from other UIDs, not just injected applications. */
final class HookPackagePolicy {
    private HookPackagePolicy() { }

    static boolean isHostApplication(boolean firstApplication, Integer applicationUid, int processUid) {
        // The first application also covers isolated processes whose runtime UID differs from appInfo.
        // Missing identity is not enough evidence to reject a legitimate legacy/shared-process host.
        return firstApplication || applicationUid == null || applicationUid < 0 || applicationUid == processUid;
    }
}
