package com.adhook.core;

import android.view.View;

import com.adhook.config.ConfigClient;
import com.adhook.config.model.AppConfig;
import com.adhook.config.model.FeatureMeta;
import com.adhook.config.security.RuntimeStatsPolicy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.adhook.runtime.HookCallback;

/**
 * Isolates feature registration and records actual runtime actions separately.
 * OK means only that the registration action returned, never that an ad was blocked.
 */
public final class FeatureGuard {
    public enum State { OK, FAILED, DISABLED }
    public interface Action { void run() throws Throwable; }

    private static final Map<String, Map<String, String>> ALIASES = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> PREFIX_ALIASES = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Boolean>> DEFAULTS = new ConcurrentHashMap<>();
    private static final Map<String, String> CATALOG_PRIMARY = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> HIT_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_HIT_TIMES = new ConcurrentHashMap<>();
    private static final Map<String, State> GLOBAL_STATES = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, State>> REGISTRATION_STATES = new ConcurrentHashMap<>();
    /** Opaque, process-long feature owners. Values never reference Views/Activities. */
    private static final Map<String, Object> VIEW_OWNERS = new ConcurrentHashMap<>();
    private static final int MAX_COUNTERS = 512;
    private static volatile String sCurrentPackage;
    private static volatile String sPrimaryPackage;
    private static volatile String sProcessName;
    private static final ThreadLocal<String> REGISTRATION_FEATURE = new ThreadLocal<>();
    private static final ThreadLocal<String> REGISTRATION_PACKAGE = new ThreadLocal<>();

    public static String currentRegistrationFeature() { return REGISTRATION_FEATURE.get(); }

    /** Retain the complete owner across nested, deferred and cross-package registration callbacks. */
    public static void withRegistrationOwner(String packageName, String feature, Action action) throws Throwable {
        String previousPackage = REGISTRATION_PACKAGE.get();
        String previousFeature = REGISTRATION_FEATURE.get();
        try {
            if (packageName == null) REGISTRATION_PACKAGE.remove();
            else REGISTRATION_PACKAGE.set(packageName);
            if (feature == null) REGISTRATION_FEATURE.remove();
            else REGISTRATION_FEATURE.set(feature);
            action.run();
        } finally {
            if (previousPackage == null) REGISTRATION_PACKAGE.remove();
            else REGISTRATION_PACKAGE.set(previousPackage);
            if (previousFeature == null) REGISTRATION_FEATURE.remove();
            else REGISTRATION_FEATURE.set(previousFeature);
        }
    }

    private final String packageName;
    private final Map<String, State> states = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, String> failures = Collections.synchronizedMap(new LinkedHashMap<>());

    public FeatureGuard(String packageName) {
        setCurrentPackage(packageName);
        this.packageName = packageName == null ? "unknown" : runtimePackageFor(packageName);
    }

    public static void setCurrentPackage(String packageName) {
        if (packageName == null || packageName.isEmpty() || "unknown".equals(packageName)
                || "android".equals(packageName)) return;
        // A secondary implementation (e.g. health/wearable) must not overwrite the injected identity.
        if (sCurrentPackage == null) sCurrentPackage = packageName;
    }

    public static void configureHost(String packageName, String primaryPackage, String processName,
                                     List<FeatureMeta> features) {
        setCurrentPackage(packageName);
        registerCatalog(packageName, primaryPackage, features);
        if (!java.util.Objects.equals(sCurrentPackage, packageName)) return;
        sPrimaryPackage = primaryPackage;
        sProcessName = processName != null ? processName : packageName;
    }

    /** Metadata only: safe in the control app, never sets the injected package or installs a Hook. */
    public static void registerCatalog(String packageName, String primaryPackage, List<FeatureMeta> features) {
        if (packageName == null) return;
        if (primaryPackage != null) CATALOG_PRIMARY.put(packageName, primaryPackage);
        Map<String, Boolean> defaults = DEFAULTS.computeIfAbsent(packageName, ignored -> new ConcurrentHashMap<>());
        if (features != null) for (FeatureMeta feature : features) {
            if (feature != null && feature.getId() != null) defaults.put(feature.getId(), feature.isDefaultEnabled());
        }
    }

    /** Remap only the current adapter's primary package, never an arbitrary unrelated host. */
    public static String runtimePackageFor(String requestedPackage) {
        String pkg = currentPackage();
        String primary = pkg == null ? null : CATALOG_PRIMARY.get(pkg);
        if (primary == null && java.util.Objects.equals(pkg, sCurrentPackage)) primary = sPrimaryPackage;
        return pkg != null && requestedPackage != null && requestedPackage.equals(primary) ? pkg : requestedPackage;
    }

    public static String currentPackage() {
        String registered = REGISTRATION_PACKAGE.get();
        return registered != null ? registered : sCurrentPackage;
    }
    public static String currentProcessName() { return sProcessName != null ? sProcessName : currentPackage(); }

    public static void registerAlias(String packageName, String declaredId, String... internalIds) {
        if (packageName == null || declaredId == null || internalIds == null) return;
        String pkg = runtimePackageFor(packageName);
        Map<String, String> exact = ALIASES.computeIfAbsent(pkg, ignored -> new ConcurrentHashMap<>());
        Map<String, String> prefix = PREFIX_ALIASES.computeIfAbsent(pkg, ignored -> new ConcurrentHashMap<>());
        for (String id : internalIds) {
            if (id == null || id.isEmpty() || id.equals(declaredId)) continue;
            if (id.endsWith("-")) prefix.put(id, declaredId);
            else exact.put(id, declaredId);
        }
    }

    public static String canonicalFeatureId(String packageName, String featureId) {
        if (packageName == null || featureId == null) return featureId;
        String pkg = runtimePackageFor(packageName);
        Map<String, String> exact = ALIASES.get(pkg);
        if (exact == null) exact = ALIASES.get(CATALOG_PRIMARY.getOrDefault(pkg, pkg));
        if (exact != null && exact.containsKey(featureId)) return exact.get(featureId);
        // A declared ID is never captured by a broad internal prefix alias.
        Map<String, Boolean> defaults = DEFAULTS.get(pkg);
        if (defaults != null && defaults.containsKey(featureId)) return featureId;
        Map<String, String> prefixes = PREFIX_ALIASES.get(pkg);
        if (prefixes == null) prefixes = PREFIX_ALIASES.get(CATALOG_PRIMARY.getOrDefault(pkg, pkg));
        String canonical = featureId;
        int longest = -1;
        if (prefixes != null) for (Map.Entry<String, String> entry : prefixes.entrySet()) {
            if (featureId.startsWith(entry.getKey()) && entry.getKey().length() > longest) {
                longest = entry.getKey().length();
                canonical = entry.getValue();
            }
        }
        return canonical;
    }

    public static Set<String> declaredIdsOf(String packageName) {
        String pkg = runtimePackageFor(packageName);
        if (pkg == null) return Collections.emptySet();
        Set<String> result = new LinkedHashSet<>();
        Map<String, Boolean> defaults = DEFAULTS.get(pkg);
        if (defaults != null) result.addAll(defaults.keySet());
        Map<String, String> exact = ALIASES.get(pkg);
        Map<String, String> prefix = PREFIX_ALIASES.get(pkg);
        if (exact != null) result.addAll(exact.values());
        if (prefix != null) result.addAll(prefix.values());
        return result;
    }

    /** Explicit canonical preference wins; only absent canonical values consult registered legacy aliases. */
    public static boolean configuredEnabled(AppConfig config, String packageName, String featureId, boolean fallback) {
        String pkg = runtimePackageFor(packageName);
        String canonical = canonicalFeatureId(pkg, featureId);
        Map<String, Boolean> defaults = pkg == null ? null : DEFAULTS.get(pkg);
        boolean defaultValue = defaults == null ? fallback : defaults.getOrDefault(canonical, fallback);
        if (config == null) return defaultValue;
        Boolean explicit = config.getNativeFeatures().get(canonical);
        if (explicit != null) return explicit;
        // On conflicting old preferences retain a disable; never override an explicit new preference.
        Boolean legacy = null;
        for (Map.Entry<String, Boolean> entry : config.getNativeFeatures().entrySet()) {
            if (!entry.getKey().equals(canonical)
                    && canonical.equals(canonicalFeatureId(pkg, entry.getKey()))) {
                if (Boolean.FALSE.equals(entry.getValue())) return false;
                if (Boolean.TRUE.equals(entry.getValue())) legacy = true;
            }
        }
        return legacy != null ? legacy : defaultValue;
    }

    public static boolean isEnabled(String packageName, String featureId) {
        return isEnabled(packageName, featureId, true);
    }

    public static boolean isEnabled(String packageName, String featureId, boolean fallback) {
        if (featureId == null) return false;
        String pkg = runtimePackageFor(packageName);
        // Capture once: total Hook and native preference must belong to the same published app snapshot.
        AppConfig config = ConfigClient.getAppConfigCached(pkg);
        return config != null && config.isHookEnabled() && configuredEnabled(config, pkg, featureId, fallback);
    }

    public boolean run(String featureId, Action action) { return run(featureId, true, action); }

    public boolean run(String featureId, boolean defaultEnabled, Action action) {
        String previousPackage = REGISTRATION_PACKAGE.get();
        String previousRegistration = REGISTRATION_FEATURE.get();
        try {
            REGISTRATION_PACKAGE.set(packageName);
            String canonical = canonicalFeatureId(packageName, featureId);
            REGISTRATION_FEATURE.set(declaredIdsOf(packageName).contains(canonical) ? canonical : null);
            action.run();
            recordState(featureId, State.OK);
            return true;
        } catch (Throwable failure) {
            recordState(featureId, State.FAILED);
            failures.put(featureId, describe(failure));
            AdHookLog.w("[" + packageName + "] Feature unavailable: " + featureId + " -> " + describe(failure));
            return false;
        } finally {
            try {
                syncStats();
            } finally {
                if (previousPackage == null) REGISTRATION_PACKAGE.remove();
                else REGISTRATION_PACKAGE.set(previousPackage);
                if (previousRegistration == null) REGISTRATION_FEATURE.remove();
                else REGISTRATION_FEATURE.set(previousRegistration);
            }
        }
    }

    private void recordState(String featureId, State state) {
        states.put(featureId, state);
        String canonical = canonicalFeatureId(packageName, featureId);
        Map<String, State> parts = REGISTRATION_STATES.computeIfAbsent(canonical, ignored -> new LinkedHashMap<>());
        synchronized (parts) {
            if (state == State.DISABLED && featureId.equals(canonical)) parts.clear();
            if (parts.size() < MAX_COUNTERS || parts.containsKey(featureId)) parts.put(featureId, state);
            State merged = parts.containsValue(State.FAILED) ? State.FAILED
                    : parts.containsValue(State.OK) ? State.OK : State.DISABLED;
            GLOBAL_STATES.put(canonical, merged);
        }
    }

    public void markDisabled(String featureId) {
        recordState(featureId, State.DISABLED);
        syncStats();
    }

    /** Declare instrumentation capability, not a hit or proof of a successful installation. */
    public static void track(String packageName, String... featureIds) {
        if (featureIds == null) return;
        for (String id : featureIds) counter(canonicalFeatureId(packageName, id));
        syncStats();
    }

    private static AtomicInteger counter(String id) {
        if (!RuntimeStatsPolicy.isStatsKey(id)) return null;
        AtomicInteger existing = HIT_COUNTS.get(id);
        if (existing != null) return existing;
        synchronized (HIT_COUNTS) {
            existing = HIT_COUNTS.get(id);
            if (existing != null) return existing;
            if (HIT_COUNTS.size() >= MAX_COUNTERS) {
                // Native counters get a reserved escape from a large managed-rule set.
                if (id.startsWith("adhook:v2:")) return null;
                String managed = HIT_COUNTS.keySet().stream().filter(key -> key.startsWith("adhook:v2:")).findFirst().orElse(null);
                if (managed == null) return null;
                HIT_COUNTS.remove(managed);
                LAST_HIT_TIMES.remove(managed);
            }
            AtomicInteger created = new AtomicInteger();
            HIT_COUNTS.put(id, created);
            return created;
        }
    }

    public static int hit(String featureId) { return hit(featureId, 1); }

    /** Count only completed actions. Lists supply their actual removed count after committing the result. */
    public static int hit(String featureId, int amount) {
        String canonical = canonicalFeatureId(currentPackage(), featureId);
        AtomicInteger counter = counter(canonical);
        if (counter == null) return 0;
        if (amount <= 0) return counter.get();
        int count = counter.updateAndGet(value -> (int) Math.min(RuntimeStatsPolicy.MAX_HIT_COUNT, (long) value + amount));
        LAST_HIT_TIMES.put(canonical, System.currentTimeMillis());
        // A live-gated callback may become active without another registration (e.g. sport tabs).
        // Its completed action supersedes an earlier DISABLED registration, but never hides failures.
        GLOBAL_STATES.replace(canonical, State.DISABLED, State.OK);
        syncStats();
        RuleHitToastNotifier.notifyHookHit();
        return count;
    }

    /** A constant result with a live feature gate; merely creating/registering this callback is not a hit. */
    public static HookCallback block(String packageName, String featureId, Object result) {
        track(packageName, featureId);
        return new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!isEnabled(packageName, featureId)) return;
                if (!(param.method instanceof java.lang.reflect.Method method)) return;
                // A defensive guard, not a lifecycle bypass helper.
                if ("onCreate".equals(method.getName())
                        && android.app.Activity.class.isAssignableFrom(method.getDeclaringClass())) return;
                Object value = blockResult(method.getReturnType(), result);
                if (value == INCOMPATIBLE_RESULT) return;
                param.setResult(value);
                hit(canonicalFeatureId(packageName, featureId));
            }
        };
    }

    private static final Object INCOMPATIBLE_RESULT = new Object();

    static Object blockResult(Class<?> type, Object requested) {
        if (type == void.class) return null;
        if (!type.isPrimitive()) return requested == null || type.isInstance(requested) ? requested : INCOMPATIBLE_RESULT;
        if (type == boolean.class) return requested == null ? false : requested instanceof Boolean ? requested : INCOMPATIBLE_RESULT;
        if (type == char.class) return requested == null ? '\0' : requested instanceof Character ? requested : INCOMPATIBLE_RESULT;
        if (requested == null || requested instanceof Number number && number.doubleValue() == 0d) {
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
        }
        if (type == byte.class && requested instanceof Byte
                || type == short.class && requested instanceof Short
                || type == int.class && requested instanceof Integer
                || type == long.class && requested instanceof Long
                || type == float.class && requested instanceof Float
                || type == double.class && requested instanceof Double) return requested;
        return INCOMPATIBLE_RESULT;
    }

    private static Object viewOwner(String packageName, String featureId) {
        String key = runtimePackageFor(packageName) + ":" + canonicalFeatureId(packageName, featureId);
        return VIEW_OWNERS.computeIfAbsent(key, ignored -> new Object());
    }

    public static boolean collapse(String packageName, String featureId, View view) {
        if (view == null) return false;
        Object owner = viewOwner(packageName, featureId);
        if (!isEnabled(packageName, featureId)) {
            ViewCollapser.restore(view, owner);
            return false;
        }
        boolean changed = ViewCollapser.collapseOnce(view, owner);
        if (changed) hit(canonicalFeatureId(packageName, featureId));
        return changed;
    }

    public static void preventExpansion(String packageName, String featureId, View view) {
        if (view == null) return;
        if (isEnabled(packageName, featureId)) ViewCollapser.preventExpansion(view, viewOwner(packageName, featureId));
        else restore(packageName, featureId, view);
    }

    public static void restore(String packageName, String featureId, View view) {
        ViewCollapser.restore(view, viewOwner(packageName, featureId));
    }

    public static int getHitCount(String featureId) {
        String canonical = canonicalFeatureId(currentPackage(), featureId);
        AtomicInteger value = canonical == null ? null : HIT_COUNTS.get(canonical);
        return value == null ? 0 : value.get();
    }

    public static long getLastHitTime(String featureId) {
        String canonical = canonicalFeatureId(currentPackage(), featureId);
        return canonical == null ? 0L : LAST_HIT_TIMES.getOrDefault(canonical, 0L);
    }

    public static Map<String, Integer> getAllHitCounts() {
        Map<String, Integer> copy = new TreeMap<>();
        HIT_COUNTS.forEach((id, count) -> copy.put(id, count.get()));
        return Collections.unmodifiableMap(copy);
    }

    public void logSummary() {
        synchronized (states) { AdHookLog.i("[" + packageName + "] Registration states: " + states); }
        synchronized (failures) { failures.forEach((id, error) -> AdHookLog.w("  ! " + id + ": " + error)); }
        syncStats();
    }

    /** Configuration publication may arrive on a worker; View leases must be released on main. */
    public static void onHookConfigChanged(String packageName) {
        String pkg = runtimePackageFor(packageName);
        if (pkg == null) return;
        android.os.Looper main = android.os.Looper.getMainLooper();
        if (main == null) return;
        new android.os.Handler(main).post(() -> {
            String prefix = pkg + ":";
            for (Map.Entry<String, Object> entry : VIEW_OWNERS.entrySet()) {
                if (entry.getKey().startsWith(prefix)
                        && !isEnabled(pkg, entry.getKey().substring(prefix.length()))) {
                    ViewCollapser.releaseOwner(entry.getValue());
                }
            }
        });
    }

    public static void onContextReady() { syncStats(); }

    /** Producer coalescing/ACK retry is owned by ConfigClient; every change marks the latest snapshot dirty. */
    private static void syncStats() {
        String pkg = currentPackage();
        if (pkg == null) return;
        ConfigClient.scheduleRuntimeStats(pkg, FeatureGuard::statsSnapshot);
    }

    private static org.json.JSONObject statsSnapshot() {
        try {
            Map<String, String> states = new TreeMap<>();
            GLOBAL_STATES.forEach((id, state) -> states.put(id, state.name()));
            return new org.json.JSONObject().put("states", new org.json.JSONObject(states))
                    .put("hits", new org.json.JSONObject(getAllHitCounts()));
        } catch (org.json.JSONException ignored) { return null; }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
