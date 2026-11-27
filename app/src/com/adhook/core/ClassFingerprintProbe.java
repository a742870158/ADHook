package com.adhook.core;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexFile;

/** Bounded, non-initializing class discovery. A signature is not proof of business semantics. */
public final class ClassFingerprintProbe {
    private static final String TAG = "ClassFingerprintProbe";
    private static final Map<CacheKey, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final Set<CacheKey> NEGATIVE_CLASS_CACHE = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<CacheKey, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<CacheKey, Field> FIELD_CACHE = new ConcurrentHashMap<>();

    private static final String[] IGNORED_CLASS_PREFIXES = {
            "android.", "androidx.", "kotlin.", "kotlinx.", "java.", "javax.", "sun.", "dalvik.",
            "com.google.", "org.apache.", "org.json.", "org.jetbrains.", "org.intellij.",
            "io.reactivex.", "okhttp3.", "okio.", "retrofit2."
    };

    public interface ClassPredicate { boolean matches(Class<?> clazz) throws Throwable; }
    public interface MethodPredicate { boolean matches(Method method) throws Throwable; }
    public interface FieldPredicate { boolean matches(Field field) throws Throwable; }

    /** Limits all enumerated entries, reflected candidates, and elapsed time (between reflection calls). */
    public static final class ScanBudget {
        public final int maxEntries;
        public final int maxCandidates;
        public final long maxMillis;

        public ScanBudget(int maxEntries, int maxCandidates, long maxMillis) {
            if (maxEntries <= 0 || maxCandidates <= 0 || maxMillis <= 0 || maxMillis > 30_000) {
                throw new IllegalArgumentException("Probe budgets must be positive and at most 30 seconds");
            }
            this.maxEntries = maxEntries;
            this.maxCandidates = maxCandidates;
            this.maxMillis = maxMillis;
        }
    }

    public static final ScanBudget HOST_BUDGET = new ScanBudget(120_000, 1_500, 120);
    private static final ScanBudget LEGACY_BUDGET = new ScanBudget(250_000, 20_000, 500);

    private ClassFingerprintProbe() {}

    /**
     * Legacy first-match API, retained for callers not migrated in this change.
     * Empty prefix still means top-level short obfuscated names, never all classes.
     * Predicate INSTANCE identity is part of the key: captured arguments may differ for the same lambda class.
     */
    public static Class<?> findClass(ClassLoader loader, String packagePrefix, ClassPredicate predicate) {
        return findClass(loader, new String[]{packagePrefix}, predicate);
    }

    public static Class<?> findClass(ClassLoader loader, String[] packagePrefixes, ClassPredicate predicate) {
        if (!valid(loader, packagePrefixes, predicate)) return null;
        // Legacy predicate identities must not retain captured host Views/Activities forever.
        for (CacheKey expired : CLASS_CACHE.keySet()) if (expired.hasClearedPredicate()) CLASS_CACHE.remove(expired);
        for (CacheKey expired : NEGATIVE_CLASS_CACHE) if (expired.hasClearedPredicate()) NEGATIVE_CLASS_CACHE.remove(expired);
        String[] prefixes = packagePrefixes.clone();
        CacheKey key = new CacheKey(loader, "legacy-class", Arrays.asList(prefixes), new IdentityToken(predicate));
        Class<?> cached = CLASS_CACHE.get(key);
        if (cached != null) return cached;
        if (NEGATIVE_CLASS_CACHE.contains(key)) return null;
        return scan(loader, prefixes, predicate, LEGACY_BUDGET, false, key);
    }

    /**
     * Explicit, versioned fingerprint ID. Require a host semantic anchor plus independent constraints.
     * Returns null on ambiguity, incomplete reflection, or budget exhaustion; never returns a partial first hit.
     * Results are NOT cached: dex may be added to a live loader, invalidating uniqueness or a previous miss.
     */
    public static Class<?> findUniqueClass(ClassLoader loader, String[] packagePrefixes, String fingerprintId,
                                          ScanBudget budget, ClassPredicate predicate) {
        if (!valid(loader, packagePrefixes, predicate) || fingerprintId == null || fingerprintId.isEmpty()
                || budget == null) return null;
        return scan(loader, packagePrefixes.clone(), predicate, budget, true,
                new CacheKey(loader, "unique-class:" + fingerprintId, Arrays.asList(packagePrefixes.clone())));
    }

    private static boolean valid(ClassLoader loader, String[] prefixes, ClassPredicate predicate) {
        if (loader == null || prefixes == null || prefixes.length == 0 || predicate == null) return false;
        for (String prefix : prefixes) if (prefix != null) return true;
        return false;
    }

    private static Class<?> scan(ClassLoader loader, String[] prefixes, ClassPredicate predicate,
                                 ScanBudget budget, boolean unique, CacheKey key) {
        long start = System.nanoTime();
        int entryCount = 0;
        int candidateCount = 0;
        Class<?> match = null;
        Set<String> visited = new HashSet<>();
        try {
            Object pathList = readObjectField(loader, "pathList");
            Object[] elements = (Object[]) readObjectField(pathList, "dexElements");
            if (elements == null) return null;
            // Legacy preserves prefix priority. Unique mode scans the union once, not once per prefix.
            int passes = unique ? 1 : prefixes.length;
            for (int pass = 0; pass < passes; pass++) {
                for (Object element : elements) {
                    DexFile dex = (DexFile) readObjectField(element, "dexFile");
                    if (dex == null) continue; // Directory/resource-only elements are not class sources.
                    Enumeration<String> entries = dex.entries();
                    while (entries.hasMoreElements()) {
                        if (++entryCount > budget.maxEntries || expired(start, budget)) return incomplete(key, "budget");
                        String name = entries.nextElement();
                        if (name == null || isIgnoredFrameworkClass(name)) continue;
                        if (unique ? !matchesAnyPrefix(name, prefixes) : !matchesPrefix(name, prefixes[pass])) continue;
                        if (!visited.add(name)) continue;
                        if (++candidateCount > budget.maxCandidates) return incomplete(key, "candidate budget");
                        try {
                            Class<?> candidate = Class.forName(name, false, loader);
                            if (unique) {
                                // A selector may catch reflective errors internally. Preflight all member types
                                // so an unreadable candidate cannot silently invalidate the uniqueness proof.
                                candidate.getDeclaredMethods();
                                candidate.getDeclaredFields();
                            }
                            if (!predicate.matches(candidate)) continue;
                            if (expired(start, budget)) return incomplete(key, "predicate budget");
                            if (!unique) {
                                CLASS_CACHE.put(key, candidate);
                                return candidate;
                            }
                            if (match != null && match != candidate) return incomplete(key, "ambiguous");
                            match = candidate;
                        } catch (Throwable unresolved) {
                            if (unique) return incomplete(key, "unresolved candidate");
                        }
                    }
                }
            }
            if (expired(start, budget)) return incomplete(key, "budget");
            if (!unique && match == null) NEGATIVE_CLASS_CACHE.add(key);
            return match;
        } catch (Throwable failure) {
            return incomplete(key, "unavailable dex metadata");
        }
    }

    private static Class<?> incomplete(CacheKey key, String reason) {
        AdHookLog.w("[" + TAG + "] " + key.selector + " fail-open: " + reason);
        return null;
    }

    private static boolean expired(long start, ScanBudget budget) {
        return System.nanoTime() - start >= budget.maxMillis * 1_000_000L;
    }

    private static boolean matchesAnyPrefix(String name, String[] prefixes) {
        for (String prefix : prefixes) if (matchesPrefix(name, prefix)) return true;
        return false;
    }

    private static boolean matchesPrefix(String name, String prefix) {
        return prefix != null && (prefix.isEmpty() ? isShortObfuscatedClassName(name) : name.startsWith(prefix));
    }

    private static boolean isIgnoredFrameworkClass(String name) {
        for (String prefix : IGNORED_CLASS_PREFIXES) if (name.startsWith(prefix)) return true;
        return false;
    }

    private static boolean isShortObfuscatedClassName(String name) {
        if (name.isEmpty() || name.length() > 16 || name.indexOf('$') >= 0) return false;
        String[] parts = name.split("\\.", -1);
        if (parts.length > 3) return false;
        for (String part : parts) if (part.isEmpty() || part.length() > 5) return false;
        return true;
    }

    /** Only declared non-bridge/non-synthetic methods; a second match always disables this fingerprint. */
    public static Method findUniqueMethod(Class<?> owner, String fingerprintId, MethodPredicate predicate) {
        if (owner == null || fingerprintId == null || fingerprintId.isEmpty() || predicate == null) return null;
        // Predicates may close over host types. Do not cache by textual ID alone.
        try {
            Method found = null;
            Method[] methods = owner.getDeclaredMethods();
            if (methods.length > 512) return null;
            for (Method method : methods) {
                if (method.isBridge() || method.isSynthetic() || !predicate.matches(method)) continue;
                if (found != null) return null;
                found = method;
            }
            if (found != null) found.setAccessible(true);
            return found;
        } catch (Throwable unresolved) {
            return null;
        }
    }

    /** Unique field across the hierarchy, with a small reflection budget. */
    public static Field findUniqueField(Class<?> owner, String fingerprintId, FieldPredicate predicate) {
        if (owner == null || fingerprintId == null || fingerprintId.isEmpty() || predicate == null) return null;
        try {
            Field found = null;
            int inspected = 0;
            for (Class<?> current = owner; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (++inspected > 512) return null;
                    if (field.isSynthetic() || !predicate.matches(field)) continue;
                    if (found != null) return null;
                    found = field;
                }
            }
            if (found != null) found.setAccessible(true);
            return found;
        } catch (Throwable unresolved) {
            return null;
        }
    }

    public static Field findFieldByType(Class<?> owner, Class<?> fieldType) {
        if (owner == null || fieldType == null) return null;
        CacheKey key = new CacheKey(owner, "field-type", fieldType);
        return firstField(owner, key, field -> fieldType.isAssignableFrom(field.getType()));
    }

    public static Field findFieldByTypeName(Class<?> owner, String suffix) {
        if (owner == null || suffix == null) return null;
        CacheKey key = new CacheKey(owner, "field-suffix", suffix);
        return firstField(owner, key, field -> field.getType().getName().endsWith(suffix));
    }

    private static Field firstField(Class<?> owner, CacheKey key, FieldPredicate predicate) {
        Field cached = FIELD_CACHE.get(key);
        if (cached != null) return cached;
        try {
            for (Class<?> current = owner; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (!predicate.matches(field)) continue;
                    field.setAccessible(true);
                    FIELD_CACHE.put(key, field);
                    return field;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Method findMethodByParamName(Class<?> owner, String suffix) {
        if (owner == null || suffix == null) return null;
        return firstMethod(owner, new CacheKey(owner, "method-param-suffix", suffix), method -> {
            for (Class<?> parameter : method.getParameterTypes()) if (parameter.getName().endsWith(suffix)) return true;
            return false;
        });
    }

    public static Method findMethodByReturnAndParamCount(Class<?> owner, Class<?> returnType, int count) {
        if (owner == null) return null;
        return firstMethod(owner, new CacheKey(owner, "method-return-count", returnType, count), method ->
                method.getParameterTypes().length == count
                        && (returnType == null || returnType.isAssignableFrom(method.getReturnType())));
    }

    public static Class<?> findClassImplementing(ClassLoader loader, String prefix, Class<?> targetInterface) {
        if (targetInterface == null) return null;
        return findClass(loader, prefix, clazz -> targetInterface.isAssignableFrom(clazz) && !clazz.isInterface());
    }

    public static Method findMethodBySignature(Class<?> owner, Class<?> returnType, Class<?>... paramTypes) {
        if (owner == null) return null;
        Class<?>[] parameters = paramTypes == null ? new Class<?>[0] : paramTypes.clone();
        return firstMethod(owner, new CacheKey(owner, "method-signature", returnType, Arrays.asList(parameters)), method -> {
            if (returnType != null && !returnType.isAssignableFrom(method.getReturnType())) return false;
            Class<?>[] actual = method.getParameterTypes();
            if (actual.length != parameters.length) return false;
            for (int i = 0; i < actual.length; i++) {
                if (parameters[i] != null && !parameters[i].isAssignableFrom(actual[i])) return false;
            }
            return true;
        });
    }

    private static Method firstMethod(Class<?> owner, CacheKey key, MethodPredicate predicate) {
        Method cached = METHOD_CACHE.get(key);
        if (cached != null) return cached;
        try {
            for (Class<?> current = owner; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (!predicate.matches(method)) continue;
                    method.setAccessible(true);
                    METHOD_CACHE.put(key, method);
                    return method;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object readObjectField(Object object, String name) {
        if (object == null) return null;
        for (Class<?> current = object.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException missing) {
                // Walk inherited Android class-loader metadata.
            } catch (Throwable inaccessible) {
                throw new IllegalStateException("Unreadable class-loader metadata", inaccessible);
            }
        }
        throw new IllegalStateException("Missing class-loader metadata: " + name);
    }

    /** Owners are actual Class/ClassLoader identities, never only their names or identity hash strings. */
    private static final class CacheKey {
        final Object owner;
        final String selector;
        final Object[] constraints;

        CacheKey(Object owner, String selector, Object... constraints) {
            this.owner = owner;
            this.selector = selector;
            this.constraints = constraints.clone();
        }

        @Override public int hashCode() {
            return 31 * (31 * System.identityHashCode(owner) + selector.hashCode()) + Arrays.hashCode(constraints);
        }

        boolean hasClearedPredicate() {
            for (Object value : constraints) {
                if (value instanceof IdentityToken token && token.reference.get() == null) return true;
            }
            return false;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof CacheKey key)) return false;
            return owner == key.owner && selector.equals(key.selector) && Arrays.equals(constraints, key.constraints);
        }
    }

    private static final class IdentityToken {
        final WeakReference<Object> reference;
        final int hash;
        IdentityToken(Object value) {
            reference = new WeakReference<>(value);
            hash = System.identityHashCode(value);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object value = reference.get();
            return value != null && other instanceof IdentityToken token && value == token.reference.get();
        }
    }
}
