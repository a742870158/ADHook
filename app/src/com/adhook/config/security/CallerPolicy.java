package com.adhook.config.security;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure permission matrix. A configuration entry is deliberately not an input.
 * Android UID/package ownership and explicit authorization are resolved once by the Provider.
 */
public final class CallerPolicy {
    private final boolean self;
    private final Set<String> authorizedOwnedPackages;

    public CallerPolicy(int callingUid, int selfUid, Set<String> ownedPackages,
                        Set<String> authorizedPackages) {
        self = callingUid >= 0 && callingUid == selfUid;
        Set<String> packages = new HashSet<>();
        if (callingUid >= 0 && ownedPackages != null && authorizedPackages != null) {
            for (String pkg : ownedPackages) {
                if (ConfigInputPolicy.isPackageName(pkg) && authorizedPackages.contains(pkg)) packages.add(pkg);
            }
        }
        authorizedOwnedPackages = Collections.unmodifiableSet(packages);
    }

    public boolean canReadGlobal() {
        return self || !authorizedOwnedPackages.isEmpty();
    }

    public boolean canWriteGlobal() {
        return self;
    }

    /** Applies equally to reads, writes and statistics: one authorized sibling is not a blanket grant. */
    public boolean canAccessPackage(String pkg) {
        return ConfigInputPolicy.isPackageName(pkg) && (self || authorizedOwnedPackages.contains(pkg));
    }
}
