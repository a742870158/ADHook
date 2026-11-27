package com.adhook.sdk;

import android.view.View;

import com.adhook.core.FeatureGuard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit SDK action accounting. Installing a hook, caching a listener or creating a proxy is not a hit. */
public final class SdkRuntime {
    public static final String SHARED_FEATURE = "sdk-block";
    private static final Map<String, LinkedHashMap<String, Owner>> OWNERS = new LinkedHashMap<>();
    private static final int MAX_OWNERS_PER_LAYER = 64;

    private SdkRuntime() { }

    /**
     * Remember the first installation's owner. A later unscoped/multidex retry cannot turn a
     * feature-owned installation into an unconditional one. Explicit new feature owners may join.
     * This registry owns no SDK objects, callbacks, Views or ClassLoaders.
     */
    public static void bind(String layer) {
        String pkg = FeatureGuard.currentPackage();
        String feature = FeatureGuard.currentRegistrationFeature();
        if (feature != null) {
            feature = FeatureGuard.canonicalFeatureId(pkg, feature);
            if (!FeatureGuard.declaredIdsOf(pkg).contains(feature)) feature = null;
        }
        Owner added = null;
        synchronized (OWNERS) {
            LinkedHashMap<String, Owner> owners = OWNERS.computeIfAbsent(layer, ignored -> new LinkedHashMap<>());
            if (feature == null && !owners.isEmpty()) return;
            String key = feature == null ? SHARED_FEATURE : feature;
            if (!owners.containsKey(key) && owners.size() < MAX_OWNERS_PER_LAYER) {
                added = new Owner(pkg, key, feature == null);
                owners.put(key, added);
            }
        }
        if (added != null) FeatureGuard.track(added.pkg, added.feature);
    }

    public static Decision decision(String layer) {
        List<Owner> owners;
        synchronized (OWNERS) {
            Map<String, Owner> installed = OWNERS.get(layer);
            owners = installed == null ? List.of() : new ArrayList<>(installed.values());
        }
        // Direct SDK entry points bind on installation. This fallback keeps isolated callbacks safe
        // in compatibility paths without manufacturing a new permanent unconditional owner.
        if (owners.isEmpty()) {
            String pkg = FeatureGuard.currentPackage();
            return com.adhook.config.ConfigClient.isHookEnabledCached(pkg)
                    ? new Decision(pkg, SHARED_FEATURE) : null;
        }
        for (Owner owner : owners) {
            if (com.adhook.config.ConfigClient.isHookEnabledCached(owner.pkg)
                    && (owner.independent || FeatureGuard.isEnabled(owner.pkg, owner.feature))) {
                return new Decision(owner.pkg, owner.feature);
            }
        }
        return null;
    }

    public static Decision featureDecision(String packageName, String featureId) {
        String pkg = FeatureGuard.runtimePackageFor(packageName);
        String canonical = FeatureGuard.canonicalFeatureId(pkg, featureId);
        return canonical != null && FeatureGuard.isEnabled(pkg, canonical) ? new Decision(pkg, canonical) : null;
    }

    static boolean collapse(String sdk, View view) {
        Decision action = decision(sdk);
        return action != null && FeatureGuard.collapse(action.pkg, action.feature, view);
    }

    /** A decision is captured before acting, so concurrent preference changes cannot misattribute the hit. */
    public static final class Decision {
        private final String pkg;
        private final String feature;

        private Decision(String pkg, String feature) { this.pkg = pkg; this.feature = feature; }

        public String featureId() { return feature; }

        public void hit() {
            try { FeatureGuard.hit(FeatureGuard.canonicalFeatureId(pkg, feature)); }
            catch (Throwable ignored) { /* Telemetry must not affect the host's no-ad callback. */ }
        }
    }

    private static final class Owner {
        final String pkg;
        final String feature;
        final boolean independent;
        Owner(String pkg, String feature, boolean independent) {
            this.pkg = pkg; this.feature = feature; this.independent = independent;
        }
    }
}
